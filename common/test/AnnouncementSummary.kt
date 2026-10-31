import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.protocol.Entry
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the log says when Redis changes the announcements. */
class AnnouncementSummary {
    private fun saved(key: String) = Entry(key, 1, "{}")
    private fun removed(key: String) = Entry(key, 1, null)

    @Test
    fun namesWhatWasUpdatedAndRemoved() {
        assertEquals("updated a, b; removed c", AnnouncementSync.summarize([saved("a"), removed("c"), saved("b")]))
        assertEquals("removed c", AnnouncementSync.summarize([removed("c")]))
    }

    @Test
    fun shortensALongList() {
        val keys = "abcdefg".map { saved(it.toString()) }
        assertEquals("updated a, b, c, d, e, … (7 in all)", AnnouncementSync.summarize(keys))
    }
}
