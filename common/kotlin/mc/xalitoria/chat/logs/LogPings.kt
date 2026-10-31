package mc.xalitoria.chat.logs

import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.announce.RedisSettings
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tells the other servers and the proxy, through Redis, that logs were written to the shared database, and hears the
 * same from them, so a log page needn't read the database to find out whether anything is new.
 *
 * [written] is called once a batch has reached the database. It counts in [changes] at once, and sends one ping within
 * [window] milliseconds of the first write after the last one, so a busy chat sends a ping a second and never one per
 * message. A ping carries no log, only that there is something to read. The channel is `<prefix>:logs:changed`, with the
 * prefix of the announcements, so the same `redis` settings serve both.
 *
 * Connected, [LogChanges.trusted] is true and [changes] is also bumped every [HEARTBEAT_SECONDS], so a ping that got
 * lost makes viewers read the database again soon after instead of never. A Redis that can't be reached is retried until
 * [close], and counts as unconnected meanwhile. Nothing is started when `redis.enable` or `redis.log-pings` is off, and
 * [written] still counts for this process alone.
 */
class LogPings(
    private val settings: RedisSettings,
    /** Names this process in debug output; pings from this process are never taken as news. */
    private val origin: String,
    private val changes: LogChanges,
    private val log: AnnouncementSync.Log,
    private val window: Long = 1_000,
) : AutoCloseable {
    private val channel = "${settings.prefix}:logs:changed"
    private val address = HostAndPort(settings.host, settings.port)
    private val id = UUID.randomUUID().toString()

    @Volatile
    private var running = true

    @Volatile
    private var client: JedisPooled? = null

    @Volatile
    private var subscription: JedisPubSub? = null

    @Volatile
    private var connection: Thread? = null

    private val scheduled = AtomicBoolean()

    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "xalichat-log-pings").apply { isDaemon = true }
    }

    val active: Boolean get() = settings.enable && settings.logPings

    fun start() {
        if (!active) return
        log.info("Sharing log changes through Redis at ${settings.host}:${settings.port} (channel $channel)")
        connection = thread(isDaemon = true, name = "xalichat-log-pings-redis") { connectLoop() }
        timer.scheduleWithFixedDelay({ if (changes.trusted) changes.bump() }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
    }

    /** A batch of log entries has been written to the database. */
    fun written() {
        changes.bump()
        if (!active || !running || !scheduled.compareAndSet(false, true)) return
        try {
            timer.schedule({ scheduled.set(false); publish() }, window, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            scheduled.set(false)
        }
    }

    private fun publish() {
        val client = client ?: return log.debug("Not connected to Redis, so the log ping is not sent")
        try {
            client.publish(channel, "$id|$origin")
        } catch (e: Exception) {
            log.debug("Could not send a log ping: ${e.message}")
        }
    }

    private fun connectLoop() {
        var wait = 1L
        var down = false
        var connected = false
        while (running) {
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
                            if (!running) return unsubscribe()
                            client = commands
                            wait = 1
                            down = false
                            connected = true
                            // Pings from before now were missed, so whatever is shown is worth checking.
                            changes.trusted = true
                            changes.bump()
                            log.debug("Listening for log pings on $channel")
                        }

                        override fun onMessage(channel: String, message: String) {
                            if (!running || message.substringBefore('|') == id) return
                            log.debug("Log ping from ${message.substringAfter('|')}")
                            changes.bump()
                        }
                    }
                    if (!running) return
                    subscription = pubsub
                    subscriber.subscribe(pubsub, channel)
                }
            } catch (e: Exception) {
                if (running) {
                    val where = "${settings.host}:${settings.port}"
                    val why = generateSequence<Throwable>(e) { it.cause }.last().message ?: e.javaClass.simpleName
                    if (connected) log.warn("Lost the connection to Redis at $where ($why); log changes are read from the database until it is back")
                    else if (down) log.debug("Redis at $where is still not reachable for log pings ($why); trying again in ${wait}s")
                    else log.warn("Redis at $where is not reachable for log pings ($why); log changes are read from the database until it is, trying again in ${wait}s")
                    down = true
                    connected = false
                }
            } finally {
                changes.trusted = false
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
        changes.trusted = false
        timer.shutdownNow()
        runCatching { subscription?.unsubscribe() }
        connection?.interrupt()
    }

    private companion object {
        const val HEARTBEAT_SECONDS = 30L
    }
}
