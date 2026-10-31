import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import mc.xalitoria.chat.announce.Action
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.announce.RedisSettings
import redis.clients.jedis.Jedis
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs against a real Redis named by `XALI_REDIS_TEST` (`host:port`, password in `XALI_REDIS_PASSWORD`); skipped without one. */
class AnnouncementSync {
    private val address = System.getenv("XALI_REDIS_TEST")?.split(":")
    private val password = System.getenv("XALI_REDIS_PASSWORD").orEmpty()
    private val prefix = "test-" + UUID.randomUUID()
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))
    private val serializer = MapSerializer(String.serializer(), Announcement.serializer())
    private val quiet = object : AnnouncementSync.Log {
        override fun info(message: String) {}
        override fun warn(message: String, error: Throwable?) { error?.printStackTrace() }
        override fun debug(message: String) {}
    }

    private fun redis(): Jedis = Jedis(address!![0], address[1].toInt()).also { if (password.isNotEmpty()) it.auth(password) }

    /**
     * One participant: a store in a folder of its own, and a sync that writes what it is told into the file. Its clock
     * is [skew] milliseconds off, which is what its file's save times and its idea of "ago" go by.
     */
    private inner class Holder(val server: String?, val skew: Long = 0) : AutoCloseable {
        val directory = createTempDirectory("sync")
        val file = directory.resolve("announcements.yml")
        val store = AnnouncementStore(file, directory.resolve("state.json"))
        var sync = create()

        private fun now() = System.currentTimeMillis() + skew

        private fun create() = AnnouncementSync(
            RedisSettings(enable = true, host = address!![0], port = address[1].toInt(), password = password, prefix = prefix),
            store, { server },
            { entries -> synchronized(store) { store.scan(); entries.count { store.apply(it) }; store.write() } },
            quiet, ::now,
        )

        /** Saves [announcement] under [key] the way an editor would, [ago] milliseconds ago by this clock, and sends it. */
        fun edit(key: String, announcement: Announcement, ago: Long = 0) {
            val all = store.announcements + (key to announcement)
            file.writeText(yaml.encodeToString(serializer, all))
            file.setLastModifiedTime(FileTime.fromMillis(now() - ago))
            synchronized(store) { store.scan() }
            sync.flush()
        }

        /** What `/xalichat reload` does to the connection: closes it and opens a new one. */
        fun reconnect() {
            sync.close()
            sync = create().also { it.start() }
        }

        override fun close() = sync.close()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        repeat(100) { if (check()) return; Thread.sleep(50) }
        assertTrue(check(), what)
    }

    private fun Holder.messages(key: String) = store.announcements[key]?.actions?.filterIsInstance<Action.Message>()?.flatMap { it.lines }

    @Test
    fun serversAndProxyShareTheNewestSave() {
        if (address == null) return
        try {
            Holder(null).use { proxy ->
                proxy.sync.start()
                eventually("proxy connects") { proxy.file.exists() }
                proxy.edit("everywhere", Announcement(actions = [Action.Message(["hi"])]))
                proxy.edit("skyblock-only", Announcement(servers = ["skyblock-*"], actions = [Action.Message(["sky"])]))

                // A server that has never run gets its file from what Redis holds, with no player or proxy message.
                Holder("survival-1").use { survival ->
                    assertTrue(!survival.file.exists())
                    survival.sync.start()
                    eventually("survival gets its file") { survival.file.exists() }
                    eventually("survival gets its key") { survival.store.announcements.keys == setOf("everywhere") }

                    Holder("skyblock-2").use { skyblock ->
                        skyblock.sync.start()
                        eventually("skyblock gets both") { skyblock.store.announcements.keys == setOf("everywhere", "skyblock-only") }

                        // A save on a server reaches the proxy and a server it names, live.
                        survival.edit("everywhere", Announcement(actions = [Action.Message(["edited"])]))
                        eventually("proxy sees the edit") { proxy.messages("everywhere") == listOf("edited") }
                        eventually("skyblock sees the edit") { skyblock.messages("everywhere") == listOf("edited") }

                        // A save made a while ago, such as one from before the server was down, is older than that.
                        skyblock.edit("everywhere", Announcement(actions = [Action.Message(["stale"])]), ago = 60_000)
                        eventually("skyblock takes Redis's copy back") { skyblock.messages("everywhere") == listOf("edited") }
                        assertEquals(listOf("edited"), proxy.messages("everywhere"))

                        // Reloading opens a new connection, which works as the old one did.
                        skyblock.reconnect()
                        proxy.edit("everywhere", Announcement(actions = [Action.Message(["after reload"])]))
                        eventually("skyblock hears it on the new connection") { skyblock.messages("everywhere") == listOf("after reload") }
                    }
                }
            }
        } finally {
            redis().use { redis -> redis.keys("$prefix*").forEach { redis.del(it) } }
        }
    }

    @Test
    fun aWrongClockDoesNotDecideWhoseSaveIsNewest() {
        if (address == null) return
        try {
            // The second server's clock is an hour behind, so by the time on its file its save is older than the first's.
            Holder("survival-1").use { first ->
                Holder("survival-2", skew = -3_600_000).use { second ->
                    first.sync.start()
                    second.sync.start()
                    eventually("both connect") { first.file.exists() && second.file.exists() }

                    first.edit("news", Announcement(actions = [Action.Message(["first"])]))
                    eventually("second hears it") { second.messages("news") == listOf("first") }
                    Thread.sleep(200)
                    second.edit("news", Announcement(actions = [Action.Message(["second"])]))
                    eventually("first hears the later save") { first.messages("news") == listOf("second") }

                    // And the other way round: a clock an hour ahead doesn't win either.
                    Holder("survival-3", skew = 3_600_000).use { ahead ->
                        ahead.sync.start()
                        eventually("third hears it") { ahead.messages("news") == listOf("second") }
                        Thread.sleep(200)
                        first.edit("news", Announcement(actions = [Action.Message(["third"])]))
                        eventually("ahead hears the later save") { ahead.messages("news") == listOf("third") }
                    }
                }
            }
        } finally {
            redis().use { redis -> redis.keys("$prefix*").forEach { redis.del(it) } }
        }
    }

    @Test
    fun aSaveMadeWhileDisconnectedIsSentOnConnecting() {
        if (address == null) return
        try {
            Holder("survival-1").use { waiting ->
                // Saved with no connection, a while ago: nothing to send it to yet.
                waiting.edit("offline", Announcement(actions = [Action.Message(["written offline"])]), ago = 120_000)
                assertEquals(1, waiting.store.pending().size)

                Holder("survival-2").use { other ->
                    other.sync.start()
                    eventually("other connects") { other.file.exists() }
                    waiting.sync.start()
                    eventually("the save goes up") { other.messages("offline") == listOf("written offline") }
                    assertTrue(waiting.store.pending().isEmpty())
                    assertEquals(listOf("written offline"), waiting.messages("offline"), "a pull doesn't undo it")

                    // Redis counted back from its own time, so the save is about two minutes old there.
                    val stamp = redis().use { it.hget("$prefix:announcements", "offline") }.substringBefore('|').toLong()
                    val ago = System.currentTimeMillis() - stamp
                    assertTrue(ago in 115_000..125_000, "stamped about two minutes ago, was $ago ms")
                }
            }
        } finally {
            redis().use { redis -> redis.keys("$prefix*").forEach { redis.del(it) } }
        }
    }

    @Test
    fun anEmptiedRedisIsRefilled() {
        if (address == null) return
        try {
            Holder("survival-1").use { holder ->
                holder.sync.start()
                eventually("connects") { holder.file.exists() }
                holder.edit("keep", Announcement(actions = [Action.Message(["kept"])]))
                eventually("it is in Redis") { redis().use { it.hexists("$prefix:announcements", "keep") } }

                redis().use { it.del("$prefix:announcements") }
                holder.sync.sync()
                eventually("it is back") { redis().use { it.hexists("$prefix:announcements", "keep") } }
            }
        } finally {
            redis().use { redis -> redis.keys("$prefix*").forEach { redis.del(it) } }
        }
    }
}
