package mc.xalitoria.chat.announce

import mc.xalitoria.chat.announce.AnnouncementStore.Pending
import mc.xalitoria.chat.protocol.Entry
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import kotlin.concurrent.thread

/**
 * Shares the announcements of an [AnnouncementStore] through Redis, which holds the set every server and the proxy
 * agree on, so nobody has to be online for it to be had.
 *
 * Redis keeps a hash from each key to `<modified>|<json>` (nothing after the bar is a removal) and a channel that is
 * told the key whenever one is taken. The newest save of a key wins, and Redis itself says what time a save was: a
 * sender gives only how long ago it saved (a length of time on its own clock, so nobody's clock being wrong matters)
 * and Redis counts back from its own. The decision is made inside Redis, so two saves at once can't both win.
 *
 * On connecting (startup, a reload, and after a lost connection) the holder first sends the saves Redis
 * hasn't taken yet ([flush]), so every stamp is on Redis's clock before any is compared, then reads the whole hash and
 * applies what [server] should hold, then offers everything it has settled, which heals a hash that was emptied.
 *
 * [server] names whose announcements to take: null, for the proxy, takes all of them. [apply] is called with the
 * entries to apply, which may be none: a first start uses the answer to create its file. Everything here runs on
 * threads of its own, and a Redis that can't be reached is retried until [close]. A closed sync stays quiet for good;
 * a new one takes its place when the settings change.
 */
class AnnouncementSync(
    private val settings: RedisSettings,
    private val store: AnnouncementStore,
    private val server: () -> String?,
    private val apply: (List<Entry>) -> Unit,
    private val log: Log,
    private val clock: () -> Long = System::currentTimeMillis,
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

    @Volatile
    private var connection: Thread? = null

    /** Whether Redis has been reached, or has failed to be, since this started; what decides how loudly a save made without it is logged. */
    @Volatile
    private var attempted = false

    /** One flush at a time, so a save can't be sent twice or stamped with an older answer. */
    private val flushing = Any()

    fun start() {
        val login = if (settings.password.isEmpty()) "no password" else "password" + settings.username.let { if (it.isEmpty()) "" else " for $it" }
        log.info("Sharing announcements through Redis at ${settings.host}:${settings.port} (prefix '${settings.prefix}', ${if (settings.ssl) "TLS" else "no TLS"}, $login)")
        connection = thread(isDaemon = true, name = "xalichat-redis") { connectLoop() }
    }

    /**
     * Sends the saves and removals the store has made that Redis hasn't taken, each stamped by Redis. One it already
     * holds a newer copy of is turned down, and that copy is applied in its place. While disconnected they wait, and
     * go with the connection's own sync. [quiet] leaves the result to the caller.
     */
    fun flush(quiet: Boolean = false) {
        synchronized(flushing) { send(quiet) }
    }

    private fun send(quiet: Boolean) {
        if (!running) return
        val pending = store.pending()
        if (pending.isEmpty()) return
        val client = client
        if (client == null) {
            val message = "Not connected to Redis; ${describe(pending.map { it.key to (it.json != null) })} will be sent when it is back"
            if (attempted && !quiet) log.info(message) else log.debug(message)
            return
        }
        val sent = ArrayList<Pending>()
        val newer = ArrayList<Entry>()
        try {
            for (save in pending) {
                // How long ago it was saved by this clock; never from the future, which only a clock set back could say.
                val age = (clock() - save.saved).coerceAtLeast(0)
                when (val result = client.eval(ASSIGN, listOf(hash, channel), listOf(save.key, age.toString(), save.json.orEmpty()))) {
                    is Long -> {
                        store.settle(save, result)
                        sent += save
                    }
                    else -> {
                        store.settle(save, 0)
                        text(result)?.let { decode(save.key, it) }?.let { newer += it }
                        log.warn("Redis already holds a newer '${save.key}' than the one saved here, so it replaces this one")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Could not send announcements to Redis; they go with the next sync", e)
        }
        if (sent.isNotEmpty() && !quiet) log.info("Sent ${describe(sent.map { it.key to (it.json != null) })} to Redis")
        if (newer.isNotEmpty() && running) apply(store.relevant(server(), newer))
    }

    /**
     * Reads the shared set again and offers everything held; for a reload. While disconnected it only says so, as
     * the connection syncs by itself once it is there.
     */
    fun sync() {
        val client = client
        if (client == null || !running) {
            if (running) log.info("Not connected to Redis, so there is nothing to sync yet; this happens by itself once it is back")
            return
        }
        try {
            // First, so that everything saved here is on Redis's clock before it is compared with what Redis holds.
            flush(quiet = true)
            val master = client.hgetAll(hash).mapNotNull { (key, value) -> decode(key, value) }
            val wanted = store.relevant(server(), master)
            apply(wanted)
            val restored = restore(store.settledEntries())
            val summary = "Redis holds ${master.count { it.json != null }} announcements, ${wanted.count { it.json != null }} of them for this server" +
                if (restored > 0) "; gave it $restored it lacked" else ""
            log.info("Synced with Redis: $summary")
        } catch (e: Exception) {
            log.warn("Could not sync announcements with Redis", e)
        }
    }

    /** Gives Redis the [entries] it lacks or holds older, with the times they already have; returns how many it took. */
    private fun restore(entries: List<Entry>): Int {
        val client = client ?: return 0
        return entries.count {
            client.eval(RESTORE, listOf(hash, channel), listOf(it.key, it.modified.toString(), it.json.orEmpty())) == 1L
        }
    }

    /** "2 changed announcements (a, -b)" for the log; a removal has a minus. */
    private fun describe(changed: List<Pair<String, Boolean>>): String {
        val keys = changed.take(5).joinToString(", ") { change -> if (change.second) change.first else "-${change.first}" }
        return "${changed.size} changed announcement${if (changed.size == 1) "" else "s"} ($keys${if (changed.size > 5) ", …" else ""})"
    }

    private fun connectLoop() {
        var wait = 1L
        var down = false
        var attempt = 0
        var connected = false
        while (running) {
            attempt++
            log.debug("Connecting to Redis at ${settings.host}:${settings.port} (attempt $attempt)")
            val config = DefaultJedisClientConfig.builder()
                .user(settings.username.ifEmpty { null })
                .password(settings.password.ifEmpty { null })
                .ssl(settings.ssl)
                .connectionTimeoutMillis(5_000)
            val commands = JedisPooled(address, config.socketTimeoutMillis(5_000).build())
            try {
                // Subscribing holds its connection, which waits on the channel for as long as it likes.
                Jedis(address, config.socketTimeoutMillis(0).build()).use { subscriber ->
                    val pubsub = object : JedisPubSub() {
                        override fun onSubscribe(channel: String, subscribedChannels: Int) {
                            // Closed while connecting: this one is not wanted any more.
                            if (!running) return unsubscribe()
                            // After subscribing, so that nothing saved in between is missed.
                            client = commands
                            wait = 1
                            down = false
                            attempt = 0
                            connected = true
                            attempted = true
                            log.info("Connected to Redis at ${settings.host}:${settings.port}, listening on $channel")
                            sync()
                        }

                        override fun onMessage(channel: String, message: String) {
                            if (!running) return
                            try {
                                log.debug("Redis says '$message' changed")
                                val master = listOfNotNull(commands.hget(hash, message)?.let { decode(message, it) })
                                apply(store.relevant(server(), master))
                            } catch (e: Exception) {
                                log.warn("Could not read $message from Redis", e)
                            }
                        }
                    }
                    if (!running) return
                    subscription = pubsub
                    subscriber.subscribe(pubsub, channel)
                }
            } catch (e: Exception) {
                if (running) {
                    // An outage is worth one warning; the retries after it are only noise until it ends.
                    val where = "${settings.host}:${settings.port}"
                    val why = generateSequence<Throwable>(e) { it.cause }.last().message ?: e.javaClass.simpleName
                    val rejected = Regex("WRONGPASS|NOAUTH|NOPERM|invalid username-password|AUTH", RegexOption.IGNORE_CASE).containsMatchIn(why)
                    if (connected) log.warn("Lost the connection to Redis at $where ($why); reconnecting, and quietly after that until it is back")
                    else if (down) log.debug("Redis at $where is still not reachable ($why); trying again in ${wait}s")
                    else if (rejected) log.warn("Redis at $where turned down the login ($why); check redis.username and redis.password. Trying again in ${wait}s, and quietly after that")
                    else log.warn("Redis at $where is not reachable ($why); trying again in ${wait}s, and quietly after that until it is back")
                    down = true
                    connected = false
                    attempted = true
                }
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
        if (running && client != null) log.info("Disconnecting from Redis")
        running = false
        runCatching { subscription?.unsubscribe() }
        connection?.interrupt()
    }

    private fun text(reply: Any?): String? = when (reply) {
        is String -> reply
        is ByteArray -> String(reply)
        else -> null
    }

    companion object {
        /**
         * Takes a save that happened ARGV[2] milliseconds ago by the sender's clock, counted back from Redis's own,
         * if that is newer than the copy held (a removal also if just as new), and tells the channel. Returns the
         * time it was given, or the copy held when that is newer. The same rule as [AnnouncementStore.apply].
         */
        private const val ASSIGN = """
            local time = redis.call('TIME')
            local modified = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) - tonumber(ARGV[2])
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            local held = 0
            if current then held = tonumber(string.match(current, '^(%d+)|')) or 0 end
            if ARGV[3] == '' then
                if modified < held then return current end
            elseif modified <= held then
                return current
            end
            redis.call('HSET', KEYS[1], ARGV[1], modified .. '|' .. ARGV[3])
            redis.call('PUBLISH', KEYS[2], ARGV[1])
            return modified
        """

        /** Takes an entry with the time it already has, if that is newer than the copy held. Returns 1 when it was taken. */
        private const val RESTORE = """
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

        /** "updated a, b; removed c" for the log: what [entries] did, a removal being one without json. */
        fun summarize(entries: List<Entry>): String {
            fun keys(of: List<Entry>) = of.take(5).joinToString(", ") { it.key } + if (of.size > 5) ", … (${of.size} in all)" else ""
            val removed = entries.filter { it.json == null }
            val updated = entries.filter { it.json != null }
            return listOfNotNull(
                updated.takeIf { it.isNotEmpty() }?.let { "updated ${keys(it)}" },
                removed.takeIf { it.isNotEmpty() }?.let { "removed ${keys(it)}" },
            ).joinToString("; ")
        }

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
