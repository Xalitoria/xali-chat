import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.announce.RedisSettings
import mc.xalitoria.chat.logs.LogChanges
import mc.xalitoria.chat.logs.LogPings
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs against a real Redis named by `XALI_REDIS_TEST` (`host:port`, password in `XALI_REDIS_PASSWORD`); skipped without one. */
class LogPings {
    private val address = System.getenv("XALI_REDIS_TEST")?.split(":")
    private val password = System.getenv("XALI_REDIS_PASSWORD").orEmpty()
    private val prefix = "test-" + UUID.randomUUID()
    private val quiet = object : AnnouncementSync.Log {
        override fun info(message: String) {}
        override fun warn(message: String, error: Throwable?) { error?.printStackTrace() }
        override fun debug(message: String) {}
    }

    private fun settings(enable: Boolean = true, pings: Boolean = true) =
        RedisSettings(enable = enable, host = address!![0], port = address[1].toInt(), password = password, prefix = prefix, logPings = pings)

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun aBusyServerSendsOnePingAWindowAndNeverHearsItself() {
        if (address == null) return
        val a = LogChanges()
        val b = LogChanges()
        LogPings(settings(), "a", a, quiet).use { first ->
            LogPings(settings(), "b", b, quiet).use { second ->
                first.start()
                second.start()
                waitUntil("both to connect") { a.trusted && b.trusted }
                // Connecting counts as news, since pings from before it were missed.
                val startA = a.version
                val startB = b.version
                repeat(5) { first.written(); Thread.sleep(50) }
                // Counted here at once, told to others at the end of the window.
                assertEquals(startA + 5, a.version)
                assertEquals(startB, b.version)
                waitUntil("the ping") { b.version > startB }
                Thread.sleep(500)
                assertEquals(startB + 1, b.version)
                assertEquals(startA + 5, a.version, "a server's own ping is not news to it")
                first.written()
                waitUntil("the next ping") { b.version > startB + 1 }
                assertEquals(startB + 2, b.version)
            }
        }
        assertFalse(a.trusted)
    }

    @Test
    fun withoutRedisAWriteIsOnlyCountedHere() {
        val changes = LogChanges()
        LogPings(RedisSettings(enable = false), "a", changes, quiet).use { pings ->
            pings.start()
            pings.written()
            assertEquals(1, changes.version)
            assertFalse(changes.trusted, "nothing is heard from others, so the version proves nothing")
            assertFalse(pings.active)
        }
        // Switched off on its own, with Redis on.
        LogPings(RedisSettings(enable = true, logPings = false), "a", changes, quiet).use { assertFalse(it.active) }
        assertTrue(LogPings(RedisSettings(enable = true), "a", changes, quiet).use { it.active })
    }
}
