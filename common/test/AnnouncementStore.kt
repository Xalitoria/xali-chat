import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.protocol.Entry
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementStore {
    private val directory = createTempDirectory("announcements")
    private val file = directory.resolve("announcements.yml")
    private val state = directory.resolve("announcements.state.json")

    private fun store() = AnnouncementStore(file, state)

    /** Saves [text] as the file with [time] as its save time. */
    private fun save(text: String, time: Long) {
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(time))
    }

    private val titan = """
        "rank-titan":
          servers:
          - "skyblock-*"
          - survival-1
          messages:
          - "%player_name% purchased Titan rank!"
          commands:
          - "playsound @a yay"
    """.trimIndent()

    @Test
    fun globsMatchServerNames() {
        val a = Announcement(servers = ["skyblock-*", "survival-1"])
        assertTrue(a.appliesTo("skyblock-2"))
        assertTrue(a.appliesTo("SKYBLOCK-lobby"))
        assertTrue(a.appliesTo("survival-1"))
        assertFalse(a.appliesTo("survival-2"))
        assertFalse(a.appliesTo("skyblock"))
        assertTrue(Announcement(servers = ["survival-?"]).appliesTo("survival-2"))
        assertFalse(Announcement(servers = ["survival-?"]).appliesTo("survival-12"))
        assertTrue(Announcement(servers = ["a.b"]).appliesTo("a.b"), "dots are literal")
        assertFalse(Announcement(servers = ["a.b"]).appliesTo("axb"))
        assertTrue(Announcement().appliesTo("anything"), "no servers means all of them")
    }

    @Test
    fun scanReportsNewEditedAndRemovedKeys() {
        val store = store()
        save(titan, 1_000)
        val first = store.scan()
        assertEquals(listOf("rank-titan" to 1_000L), first.map { it.key to it.modified })
        assertTrue(store.scan().isEmpty(), "nothing changed")

        // Comments and layout are not changes.
        save("# a comment\n$titan\n", 2_000)
        assertTrue(store.scan().isEmpty())

        save(titan.replace("Titan", "Titan+"), 3_000)
        assertEquals(listOf("rank-titan" to 3_000L), store.scan().map { it.key to it.modified })

        save("", 4_000)
        val removed = store.scan().single()
        assertEquals(Triple("rank-titan", 4_000L, null), Triple(removed.key, removed.modified, removed.json))
        assertTrue(store.announcements.isEmpty())
        assertTrue(store.scan().isEmpty(), "a removal is reported once")
    }

    @Test
    fun brokenFileChangesNothing() {
        val store = store()
        save(titan, 1_000)
        store.scan()
        save("rank-titan: [unclosed\n", 2_000)
        assertFailsWith<Exception> { store.scan() }
        assertEquals(setOf("rank-titan"), store.announcements.keys)
    }

    @Test
    fun stateSurvivesARestart() {
        save(titan, 1_000)
        store().scan()
        assertTrue(store().scan().isEmpty(), "the key was already known, so a restart reports nothing")
        save(titan.replace("yay", "boo"), 5_000)
        assertEquals(5_000L, store().scan().single().modified, "an edit made while it was down is found")
    }

    @Test
    fun newestSaveWins() {
        val store = store()
        save(titan, 1_000)
        val entry = store.scan().single()

        assertFalse(store.apply(Entry("rank-titan", 1_000, entry.json)), "same time")
        assertFalse(store.apply(Entry("rank-titan", 500, entry.json!!.replace("yay", "old"))), "older")
        assertTrue(store.apply(Entry("rank-titan", 2_000, entry.json!!.replace("yay", "new"))))
        assertEquals(listOf("playsound @a new"), store.announcements.getValue("rank-titan").commands)
        assertEquals(2_000L, store.known().getValue("rank-titan"))
        assertFalse(store.apply(Entry("rank-titan", 3_000, "not json")), "an entry that doesn't parse is dropped")

        // A removal also wins a tie, and an older copy can't bring the key back.
        assertTrue(store.apply(Entry("rank-titan", 2_000, null)))
        assertTrue(store.announcements.isEmpty())
        assertFalse(store.apply(Entry("rank-titan", 1_999, entry.json)))
        assertTrue(store.apply(Entry("rank-titan", 2_001, entry.json)), "a newer save does")
    }

    @Test
    fun wireJsonLeavesOutDefaults() {
        val sender = store()
        save("\"hi\":\n  messages:\n  - hello\n", 1_000)
        val entry = sender.scan().single()
        assertEquals("""{"messages":["hello"]}""", entry.json)

        // The receiver fills the defaults back in and sees nothing to report once it has written the file.
        val receiverDir = createTempDirectory("receiver")
        val receiver = AnnouncementStore(receiverDir.resolve("announcements.yml"), receiverDir.resolve("state.json"))
        assertTrue(receiver.apply(entry))
        assertEquals(Announcement(messages = ["hello"]), receiver.announcements.getValue("hi"))
        receiver.write()
        assertTrue(receiver.scan().isEmpty())
    }

    @Test
    fun writtenEntriesAreNotReportedAgain() {
        val store = store()
        val json = Announcement(messages = ["hi"]).let { kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Announcement.serializer(), it) }
        assertTrue(store.apply(Entry("hello", 9_000, json)))
        store.write()
        assertTrue(store.scan().isEmpty(), "the file now says what the state says")
        assertEquals(9_000L, store.known().getValue("hello"))
    }

    @Test
    fun serverGetsItsKeysNewerCopiesAndRemovals() {
        val master = store()
        save(
            """
            "everywhere":
              messages: ["a"]
            "skyblock-only":
              servers: ["skyblock-*"]
              messages: ["b"]
            "survival-only":
              servers: ["survival-1"]
              messages: ["c"]
            """.trimIndent(),
            1_000,
        )
        master.scan()

        // A server that knows nothing gets the keys that name it, and only those.
        assertEquals(setOf("everywhere", "skyblock-only"), master.forServer("skyblock-2", emptyMap()).map { it.key }.toSet())
        assertEquals(setOf("everywhere", "survival-only"), master.forServer("survival-1", emptyMap()).map { it.key }.toSet())

        // Up to date: nothing. Older copy: the newer one. A key that no longer names it, or is gone: a removal.
        assertTrue(master.forServer("skyblock-2", mapOf("everywhere" to 1_000L, "skyblock-only" to 1_000L)).isEmpty())
        assertEquals(listOf("skyblock-only"), master.forServer("skyblock-2", mapOf("everywhere" to 1_000L, "skyblock-only" to 10L)).map { it.key })
        val removals = master.forServer("skyblock-2", mapOf("everywhere" to 1_000L, "skyblock-only" to 1_000L, "survival-only" to 1_000L))
        assertEquals(listOf("survival-only"), removals.map { it.key })
        assertNull(removals.single().json)

        // Deleted at the master: the tombstone tells servers still holding it to drop it.
        save("", 2_000)
        master.scan()
        val gone = master.forServer("skyblock-2", mapOf("everywhere" to 1_000L))
        assertEquals(listOf("everywhere"), gone.map { it.key })
        assertEquals(2_000L, gone.single().modified)
        assertNull(gone.single().json)
    }
}
