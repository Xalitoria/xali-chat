import kotlinx.serialization.json.Json
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.announce.AnnouncementSync
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
    fun brokenFileLeavesTheLastWorkingOneAsABackup() {
        val store = store()
        val backup = directory.resolve("announcements.yml.bak")
        save(titan, 1_000)
        store.scan()
        assertFalse(backup.toFile().exists(), "nothing to keep while the file is fine")

        save("rank-titan: [unclosed\n", 2_000)
        assertFailsWith<Exception> { store.scan() }
        assertEquals(titan, backup.toFile().readText())

        save(titan.replace("yay", "pling"), 3_000)
        store.scan()
        save("still: [broken\n", 4_000)
        assertFailsWith<Exception> { store.scan() }
        assertTrue("pling" in backup.toFile().readText(), "the newer working version is the one kept")
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
        assertTrue(store.apply(Entry("rank-titan", 2_000, entry.json.replace("yay", "new"))))
        assertEquals(listOf("playsound @a new"), store.announcements.getValue("rank-titan").commands)
        assertEquals(2_000L, store.entry("rank-titan")!!.modified)
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
        val json = Announcement(messages = ["hi"]).let { json.encodeToString(Announcement.serializer(), it) }
        assertTrue(store.apply(Entry("hello", 9_000, json)))
        store.write()
        assertTrue(store.scan().isEmpty(), "the file now says what the state says")
        assertEquals(9_000L, store.entry("hello")!!.modified)
    }

    /** A store of a server of its own: its file and state live in a folder beside the master's. */
    private fun holder() = createTempDirectory("holder").let { AnnouncementStore(it.resolve("announcements.yml"), it.resolve("state.json")) }

    private fun AnnouncementStore.take(entries: List<Entry>) = entries.forEach { apply(it) }

    @Test
    fun serverTakesItsKeysAndDropsTheOnesThatStopNamingIt() {
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
        val shared = master.allEntries()

        // The proxy (no server) takes everything; a server only the keys that name it.
        assertEquals(setOf("everywhere", "skyblock-only", "survival-only"), holder().relevant(null, shared).map { it.key }.toSet())
        val skyblock = holder()
        assertEquals(setOf("everywhere", "skyblock-only"), skyblock.relevant("skyblock-2", shared).map { it.key }.toSet())
        skyblock.take(skyblock.relevant("skyblock-2", shared))
        assertEquals(setOf("everywhere", "skyblock-only"), skyblock.announcements.keys)

        // Taking the same set again changes nothing.
        assertEquals(0, skyblock.relevant("skyblock-2", shared).count { skyblock.apply(it) })

        // A key that stops naming the server is a removal there; one the server never held is nothing.
        save(
            """
            "everywhere":
              messages: ["a"]
            "skyblock-only":
              servers: ["survival-1"]
              messages: ["b"]
            "survival-only":
              servers: ["survival-1"]
              messages: ["c"]
            """.trimIndent(),
            2_000,
        )
        master.scan()
        val moved = skyblock.relevant("skyblock-2", master.allEntries())
        assertEquals(listOf("skyblock-only"), moved.filter { it.json == null && it.modified == 2_000L }.map { it.key })
        skyblock.take(moved)
        assertEquals(setOf("everywhere"), skyblock.announcements.keys)
        assertEquals(setOf("everywhere"), holder().also { it.take(it.relevant("skyblock-2", master.allEntries())) }.announcements.keys)

        // Deleted at the master: the removal tells a server still holding it to drop it.
        save("", 3_000)
        master.scan()
        skyblock.take(skyblock.relevant("skyblock-2", master.allEntries()))
        assertTrue(skyblock.announcements.isEmpty())
    }

    @Test
    fun olderSharedCopyDoesNotReplaceANewerLocalOne() {
        val server = holder()
        server.take(listOf(Entry("a", 5_000, json.encodeToString(Announcement.serializer(), Announcement(messages = ["new"])))))
        val old = Entry("a", 1_000, json.encodeToString(Announcement.serializer(), Announcement(messages = ["old"])))
        assertEquals(0, server.relevant("x", listOf(old)).count { server.apply(it) })
        assertEquals(listOf("new"), server.announcements.getValue("a").messages)
    }

    @Test
    fun sharedValuesRoundTrip() {
        val kept = AnnouncementSync.decode("a", AnnouncementSync.encode(Entry("a", 42, """{"messages":["x|y"]}""")))!!
        assertEquals(42L, kept.modified)
        assertEquals("""{"messages":["x|y"]}""", kept.json)
        val removed = AnnouncementSync.decode("a", AnnouncementSync.encode(Entry("a", 43, null)))!!
        assertEquals(43L, removed.modified)
        assertNull(removed.json)
        assertNull(AnnouncementSync.decode("a", "garbage"))
    }

    @Test
    fun emptyStoreWritesAFileThatScansAsNothing() {
        val store = store()
        assertFalse(store.exists())
        assertFalse(store.hasHistory())
        store.write()
        assertTrue(store.exists())
        assertTrue(store().scan().isEmpty(), "the header-only file holds no announcements")
        assertTrue(store().announcements.isEmpty())
    }

    @Test
    fun anAnswerToAFirstSyncCreatesTheFile() {
        val store = store()
        assertTrue(store.apply(Entry("a", 5_000, json.encodeToString(Announcement.serializer(), Announcement(messages = ["hi"])))))
        store.write()
        val again = store()
        assertEquals(emptyList(), again.scan().map { it.key }, "what was written is what the state already holds")
        assertEquals(listOf("hi"), again.announcements.getValue("a").messages)
    }

    companion object {
        val json = Json { encodeDefaults = true }
    }
}
