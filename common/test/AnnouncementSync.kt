import kotlinx.serialization.json.Json
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.announce.RedisSettings
import mc.xalitoria.chat.protocol.Entry
import redis.clients.jedis.Jedis
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs against a real Redis named by `XALI_REDIS_TEST` (`host:port`); skipped without one. */
class AnnouncementSync {
    private val address = System.getenv("XALI_REDIS_TEST")?.split(":")
    private val prefix = "test-" + UUID.randomUUID()
    private val json = Json { encodeDefaults = true }
    private val quiet = object : AnnouncementSync.Log {
        override fun info(message: String) {}
        override fun warn(message: String, error: Throwable?) { error?.printStackTrace() }
        override fun debug(message: String) {}
    }

    /** One participant: a store in a folder of its own, and a sync that writes what it is told into the file. */
    private inner class Holder(val server: String?) : AutoCloseable {
        val directory = createTempDirectory("sync")
        val file = directory.resolve("announcements.yml")
        val store = AnnouncementStore(file, directory.resolve("state.json"))
        val sync = AnnouncementSync(
            RedisSettings(enable = true, host = address!![0], port = address[1].toInt(), prefix = prefix),
            store, { server },
            { entries -> synchronized(store) { store.scan(); entries.count { store.apply(it) }; store.write() } },
            quiet,
        )

        fun save(key: String, announcement: Announcement, time: Long) {
            val entry = Entry(key, time, json.encodeToString(Announcement.serializer(), announcement))
            synchronized(store) { store.apply(entry); store.write() }
            sync.push(listOf(entry))
        }

        override fun close() = sync.close()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        repeat(100) { if (check()) return; Thread.sleep(50) }
        assertTrue(check(), what)
    }

    @Test
    fun serversAndProxyShareTheNewestSave() {
        if (address == null) return
        try {
            Holder(null).use { proxy ->
                proxy.sync.start()
                eventually("proxy connects") { proxy.file.exists() }
                proxy.save("everywhere", Announcement(messages = ["hi"]), 1_000)
                proxy.save("skyblock-only", Announcement(servers = ["skyblock-*"], messages = ["sky"]), 1_000)

                // A server that has never run gets its file from what Redis holds, with no player or proxy message.
                Holder("survival-1").use { survival ->
                    assertTrue(!survival.file.exists())
                    survival.sync.start()
                    eventually("survival gets its file") { survival.file.exists() }
                    eventually("survival gets its key") { survival.store.announcements.keys == setOf("everywhere") }

                    // A save on the server reaches the proxy and a server it names, live.
                    Holder("skyblock-2").use { skyblock ->
                        skyblock.sync.start()
                        eventually("skyblock gets both") { skyblock.store.announcements.keys == setOf("everywhere", "skyblock-only") }
                        survival.save("everywhere", Announcement(messages = ["edited"]), 2_000)
                        eventually("proxy sees the edit") { proxy.store.announcements["everywhere"]?.messages == listOf("edited") }
                        eventually("skyblock sees the edit") { skyblock.store.announcements["everywhere"]?.messages == listOf("edited") }

                        // An older save is turned down.
                        skyblock.save("everywhere", Announcement(messages = ["stale"]), 500)
                        Thread.sleep(300)
                        assertEquals(listOf("edited"), proxy.store.announcements["everywhere"]?.messages)
                    }
                }
            }
        } finally {
            Jedis(address[0], address[1].toInt()).use { redis ->
                redis.keys("$prefix*").forEach { redis.del(it) }
            }
        }
    }
}
