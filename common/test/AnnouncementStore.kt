import kotlinx.serialization.json.Json
import mc.xalitoria.chat.announce.Action
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.protocol.Entry
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
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

    private fun Announcement.lines() = actions.filterIsInstance<Action.Message>().flatMap { it.lines }
    private fun Announcement.commands() = actions.filterIsInstance<Action.Command>().map { it.command }

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
          actions:
          - message: "%player_name% purchased Titan rank!"
          - command: "playsound @a yay"
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
        store.settle(store.pending().single(), 1_000)

        assertFalse(store.apply(Entry("rank-titan", 1_000, entry.json)), "same time")
        assertFalse(store.apply(Entry("rank-titan", 500, entry.json!!.replace("yay", "old"))), "older")
        assertTrue(store.apply(Entry("rank-titan", 2_000, entry.json.replace("yay", "new"))))
        assertEquals(listOf("playsound @a new"), store.announcements.getValue("rank-titan").commands())
        assertEquals(2_000L, store.entry("rank-titan")!!.modified)
        assertFalse(store.apply(Entry("rank-titan", 3_000, "not json")), "an entry that doesn't parse is dropped")

        // A removal also wins a tie, and an older copy can't bring the key back.
        assertTrue(store.apply(Entry("rank-titan", 2_000, null)))
        assertTrue(store.announcements.isEmpty())
        assertFalse(store.apply(Entry("rank-titan", 1_999, entry.json)))
        assertTrue(store.apply(Entry("rank-titan", 2_001, entry.json)), "a newer save does")
    }

    @Test
    fun aSaveNotYetSentIsNotComparedWithAnotherClock() {
        val store = store()
        save(titan, 1_000)
        val entry = store.scan().single()
        val pending = store.pending().single()
        assertEquals(1_000L, pending.saved, "until it is sent it has the time of the file")
        assertEquals(entry.json, pending.json)

        // Whatever time the shared set claims for its copy, it can't be put over a save that hasn't been sent.
        assertFalse(store.apply(Entry("rank-titan", 9_999_999, entry.json!!.replace("yay", "new"))))
        assertEquals(listOf("playsound @a yay"), store.announcements.getValue("rank-titan").commands())

        // Once it is sent the time is the shared set's, and a copy newer than that is taken.
        store.settle(pending, 5_000)
        assertTrue(store.pending().isEmpty())
        assertEquals(5_000L, store.entry("rank-titan")!!.modified)
        assertFalse(store.apply(Entry("rank-titan", 4_000, entry.json)))
        assertTrue(store.apply(Entry("rank-titan", 6_000, entry.json.replace("yay", "new"))))
    }

    @Test
    fun aSaveTurnedDownGivesWayToTheSharedCopy() {
        val store = store()
        save(titan, 1_000)
        store.scan()
        store.settle(store.pending().single(), 0)
        assertTrue(store.apply(Entry("rank-titan", 7_000, """{"messages":["theirs"]}""")), "any copy is newer than a turned-down save")
        assertEquals(listOf("theirs"), store.announcements.getValue("rank-titan").lines())
    }

    @Test
    fun settlingIgnoresAKeySavedAgainInTheMeantime() {
        val store = store()
        save(titan, 1_000)
        store.scan()
        val sent = store.pending().single()
        save(titan.replace("yay", "boo"), 2_000)
        store.scan()

        store.settle(sent, 8_000)
        val now = store.pending().single()
        assertEquals(2_000L, now.saved, "the newer save is still waiting")
        assertEquals(1, store.pending().size)
    }

    @Test
    fun pendingSavesSurviveARestartAndSettledOnesDoNot() {
        save(titan, 1_000)
        store().scan()
        val restarted = store()
        assertEquals(1_000L, restarted.pending().single().saved, "a save sent after the restart is still found")
        restarted.settle(restarted.pending().single(), 3_000)
        assertTrue(store().pending().isEmpty())
        assertEquals(listOf("rank-titan"), store().settledEntries().map { it.key })
    }

    @Test
    fun aSaveThatSlipsInBeforeAWriteIsKept() {
        val store = store()
        save(titan, 1_000)
        store.scan()
        store.settle(store.pending().single(), 1_000)
        assertTrue(store.apply(Entry("other", 2_000, """{"messages":["remote"]}""")))

        // The file is saved after it was read and before the update is written: that save is newer and stays.
        save(titan.replace("yay", "boo"), 3_000)
        assertFalse(store.write(), "the file isn't the one that was read")
        assertTrue(directory.resolve("announcements.yml").readText().contains("boo"))
        // It is a save of a file that lacks "other" (an editor's older copy), so that is read as removing it: what was
        // saved last is what was meant.
        assertEquals(setOf("rank-titan", "other"), store.scan().map { it.key }.toSet(), "and it is picked up as a save")

        assertTrue(store.write(), "once read, the file can be written again")
        assertFalse(directory.resolve("announcements.yml").readText().contains("\"other\":"))
    }

    @Test
    fun wireJsonLeavesOutDefaults() {
        val sender = store()
        save("\"hi\":\n  actions:\n  - message: hello\n", 1_000)
        val entry = sender.scan().single()
        assertEquals("""{"actions":[{"message":"hello"}]}""", entry.json)

        // The receiver fills the defaults back in and sees nothing to report once it has written the file.
        val receiverDir = createTempDirectory("receiver")
        val receiver = AnnouncementStore(receiverDir.resolve("announcements.yml"), receiverDir.resolve("state.json"))
        assertTrue(receiver.apply(entry))
        assertEquals(Announcement(actions = [Action.Message(["hello"])]), receiver.announcements.getValue("hi"))
        receiver.write()
        assertTrue(receiver.scan().isEmpty())
    }

    @Test
    fun writtenEntriesAreNotReportedAgain() {
        val store = store()
        val json = Announcement(actions = [Action.Message(["hi"])]).let { json.encodeToString(Announcement.serializer(), it) }
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
              actions: [{message: "a"}]
            "skyblock-only":
              servers: ["skyblock-*"]
              actions: [{message: "b"}]
            "survival-only":
              servers: ["survival-1"]
              actions: [{message: "c"}]
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
              actions: [{message: "a"}]
            "skyblock-only":
              servers: ["survival-1"]
              actions: [{message: "b"}]
            "survival-only":
              servers: ["survival-1"]
              actions: [{message: "c"}]
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
        server.take(listOf(Entry("a", 5_000, json.encodeToString(Announcement.serializer(), Announcement(actions = [Action.Message(["new"])])))))
        val old = Entry("a", 1_000, json.encodeToString(Announcement.serializer(), Announcement(actions = [Action.Message(["old"])])))
        assertEquals(0, server.relevant("x", listOf(old)).count { server.apply(it) })
        assertEquals(listOf("new"), server.announcements.getValue("a").lines())
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
        assertTrue(store.apply(Entry("a", 5_000, json.encodeToString(Announcement.serializer(), Announcement(actions = [Action.Message(["hi"])])))))
        store.write()
        val again = store()
        assertEquals(emptyList(), again.scan().map { it.key }, "what was written is what the state already holds")
        assertEquals(listOf("hi"), again.announcements.getValue("a").lines())
    }

    companion object {
        val json = Json { encodeDefaults = true }
    }
}
