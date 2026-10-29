package mc.xalitoria.chat.announce

import mc.xalitoria.chat.protocol.Entry
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shares the announcements of an [AnnouncementStore] through Redis, which holds the set every server and the proxy
 * agree on, so nobody has to be online for it to be had.
 *
 * Redis keeps a hash from each key to `<modified>|<json>` (nothing after the bar is a removal) and a channel that is
 * told the key whenever one is taken. A save is taken only if it is newer than the hash's copy, the same rule
 * [AnnouncementStore.apply] has, decided inside Redis so two saves at once can't both win. On connecting (and every
 * few minutes, in case a notification was lost) the holder reads the whole hash and applies what [server] should hold,
 * then offers everything it has to the hash, which heals a hash that was emptied.
 *
 * [server] names whose announcements to take: null, for the proxy, takes all of them. [apply] is called with the
 * entries to apply, which may be none: a first start uses the answer to create its file. Everything here runs on
 * threads of its own, and a Redis that can't be reached is retried until [close].
 */
class AnnouncementSync(
    private val settings: RedisSettings,
    private val store: AnnouncementStore,
    private val server: () -> String?,
    private val apply: (List<Entry>) -> Unit,
    private val log: Log,
) : AutoCloseable {
    interface Log {
        fun info(message: String)
        fun warn(message: String, error: Throwable? = null)
        fun debug(message: String)
    }

    private val hash = "${settings.prefix}:announcements"
    private val channel = "${settings.prefix}:announcements:changed"
    private val address = HostAndPort(settings.host, settings.port)

    @Volatile
    private var running = true

    /** Commands go through this; null while there is no connection. */
    @Volatile
    private var client: JedisPooled? = null

    @Volatile
    private var subscription: JedisPubSub? = null

    private val resync = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "xalichat-redis-resync").apply { isDaemon = true }
    }

    fun start() {
        thread(isDaemon = true, name = "xalichat-redis") { connectLoop() }
        resync.scheduleWithFixedDelay({ sync() }, RESYNC_MINUTES, RESYNC_MINUTES, TimeUnit.MINUTES)
    }

    /** Reads the shared set again and offers everything held; for a reload. Does nothing while disconnected. */
    fun sync() {
        val client = client ?: return
        try {
            val master = client.hgetAll(hash).mapNotNull { (key, value) -> decode(key, value) }
            apply(store.relevant(server(), master))
            push(store.allEntries())
        } catch (e: Exception) {
            log.warn("Could not sync announcements with Redis", e)
        }
    }

    /** Offers [entries] to the shared set, which keeps each that is newer than its copy and tells the others. */
    fun push(entries: List<Entry>) {
        val client = client ?: return
        if (entries.isEmpty()) return
        try {
            var kept = 0
            entries.forEach {
                val result = client.eval(PUSH, listOf(hash, channel), listOf(it.key, it.modified.toString(), it.json.orEmpty()))
                if (result == 1L) kept++
            }
            log.debug("Offered ${entries.size} announcements to Redis, ${kept} newer than its copy")
        } catch (e: Exception) {
            log.warn("Could not send announcements to Redis; they go with the next sync", e)
        }
    }

    private fun connectLoop() {
        var wait = 1L
        while (running) {
            val config = DefaultJedisClientConfig.builder()
                .user(settings.username.ifEmpty { null })
                .password(settings.password.ifEmpty { null })
                .ssl(settings.ssl)
                .connectionTimeoutMillis(5_000)
            val commands = JedisPooled(address, config.socketTimeoutMillis(5_000).build())
            try {
                // Subscribing holds its connection, which waits on the channel for as long as it likes.
                Jedis(address, config.socketTimeoutMillis(0).build()).use { connection ->
                    val pubsub = object : JedisPubSub() {
                        override fun onSubscribe(channel: String, subscribedChannels: Int) {
                            // After subscribing, so that nothing saved in between is missed.
                            client = commands
                            wait = 1
                            log.info("Connected to Redis at ${settings.host}:${settings.port}")
                            sync()
                        }

                        override fun onMessage(channel: String, message: String) {
                            try {
                                val master = listOfNotNull(commands.hget(hash, message)?.let { decode(message, it) })
                                apply(store.relevant(server(), master))
                            } catch (e: Exception) {
                                log.warn("Could not read $message from Redis", e)
                            }
                        }
                    }
                    subscription = pubsub
                    connection.subscribe(pubsub, channel)
                }
            } catch (e: Exception) {
                if (running) log.warn("Redis at ${settings.host}:${settings.port} is not reachable; trying again in ${wait}s", e)
            } finally {
                client = null
                subscription = null
                runCatching { commands.close() }
            }
            if (!running) return
            try {
                Thread.sleep(wait * 1000)
            } catch (e: InterruptedException) {
                return
            }
            wait = minOf(wait * 2, 30)
        }
    }

    override fun close() {
        running = false
        resync.shutdownNow()
        runCatching { subscription?.unsubscribe() }
    }

    companion object {
        private const val RESYNC_MINUTES = 5L

        /** Takes a newer save, or a removal as new as the copy, and tells the channel. Returns 1 when it was taken. */
        private const val PUSH = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            local held = 0
            if current then held = tonumber(string.match(current, '^(%d+)|')) or 0 end
            local modified = tonumber(ARGV[2])
            if ARGV[3] == '' then
                if modified < held then return 0 end
            elseif modified <= held then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2] .. '|' .. ARGV[3])
            redis.call('PUBLISH', KEYS[2], ARGV[1])
            return 1
        """

        /** The form of a hash value: `<modified>|<json>`, with nothing after the bar for a removal. */
        fun encode(entry: Entry): String = "${entry.modified}|${entry.json.orEmpty()}"

        /** The entry [key] holds as [value]; null for a value that isn't in that form. */
        fun decode(key: String, value: String): Entry? {
            val bar = value.indexOf('|')
            val modified = value.substring(0, bar.coerceAtLeast(0)).toLongOrNull() ?: return null
            return Entry(key, modified, value.substring(bar + 1).ifEmpty { null })
        }
    }
}
