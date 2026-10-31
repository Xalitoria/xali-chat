import kotlinx.serialization.SerializationException
import mc.xalitoria.chat.announce.Action
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.announce.parseDelay
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Actions {
    private val directory = createTempDirectory("actions")
    private val file = directory.resolve("announcements.yml")
    private val state = directory.resolve("announcements.state.json")

    private fun store() = AnnouncementStore(file, state)

    private fun save(text: String, time: Long = 1_000) {
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(time))
    }

    private val example = """
        "rank-example":
          enable: true
          servers:
          - "skyblock"
          actions:
          - delay: 1
          - command: "another command"
          - condition: "%discordsrv_linked%"
            'true':
            - message: "%player_name% purchased a rank!"
            - condition: '%luckperms_group% != "example"'
              'true':
              - message: "You already have this rank"
              - command: "execute at @a run playsound minecraft:entity.player.levelup master @a"
              # false can be empty
            'false':
            - message:
              - "&cYou need to link your discord first"
              - "<click:open_url:'https://discord.gg'>Join Here</click>"
    """.trimIndent()

    private val parsed = Announcement(
        servers = ["skyblock"],
        actions = [
            Action.Delay("1"),
            Action.Command("another command"),
            Action.Condition(
                "%discordsrv_linked%",
                yes = [
                    Action.Message(["%player_name% purchased a rank!"]),
                    Action.Condition(
                        "%luckperms_group% != \"example\"",
                        yes = [
                            Action.Message(["You already have this rank"]),
                            Action.Command("execute at @a run playsound minecraft:entity.player.levelup master @a"),
                        ],
                    ),
                ],
                no = [Action.Message(["&cYou need to link your discord first", "<click:open_url:'https://discord.gg'>Join Here</click>"])],
            ),
        ],
    )

    @Test
    fun theFileReadsAsATreeOfActions() {
        save(example)
        assertEquals(parsed, store().also { it.scan() }.announcements.getValue("rank-example"))
    }

    @Test
    fun theTreeSurvivesBeingWrittenAndSent() {
        save(example)
        val sender = store()
        val entry = sender.scan().single()

        val other = createTempDirectory("other")
        val receiver = AnnouncementStore(other.resolve("announcements.yml"), other.resolve("state.json"))
        assertTrue(receiver.apply(entry))
        assertEquals(parsed, receiver.announcements.getValue("rank-example"), "over the wire")

        receiver.write()
        val text = other.resolve("announcements.yml").readText()
        assertFalse("null" in text, "unused keys are not written: $text")
        assertFalse("messages" in text || "commands" in text, "the old keys are not written: $text")
        val again = AnnouncementStore(other.resolve("announcements.yml"), other.resolve("again.json"))
        again.scan()
        assertEquals(parsed, again.announcements.getValue("rank-example"), "through the file")
    }

    @Test
    fun theOldFormatIsReadAsActionsInTheOldOrder() {
        save(
            """
            "discord":
              messages:
              - "one"
              - ""
              commands:
              - "playsound @a pling"
              - "delay 2s 10t"
              - "/another"
              - "delay nonsense"
            """.trimIndent(),
        )
        val loaded = store().also { it.scan() }.announcements.getValue("discord")
        assertEquals(
            [Action.Message(["one", ""]), Action.Command("playsound @a pling"), Action.Delay("2s 10t"), Action.Command("/another")],
            loaded.actions,
        )
        assertEquals(emptyList(), loaded.messages)
        assertEquals(emptyList(), loaded.commands)
    }

    @Test
    fun theOldWireFormatIsFoldedToo() {
        val receiver = store()
        assertTrue(receiver.apply(Entry("a", 5_000, """{"messages":["hi"],"commands":["say x"]}""")))
        assertEquals([Action.Message(["hi"]), Action.Command("say x")], receiver.announcements.getValue("a").actions)
    }

    @Test
    fun messagesAreOneLineOrAList() {
        save("\"a\":\n  actions:\n  - message: one\n  - message:\n    - x\n    -\n    - y\n  - message: []\n")
        assertEquals(
            [Action.Message(["one"]), Action.Message(["x", "", "y"]), Action.Message([])],
            store().also { it.scan() }.announcements.getValue("a").actions,
            "an empty item is a blank line",
        )
    }

    @Test
    fun anActionNeedsExactlyOneKind() {
        fun failure(action: String) = assertFailsWith<Exception> {
            save("\"a\":\n  actions:\n  - $action\n")
            store().scan()
        }.toString()

        assertTrue("needs one of" in failure("mesage: typo"), "a typo is not silently dropped")
        assertTrue("delay and command" in failure("{delay: 1, command: x}"))
        assertTrue("only go with a condition" in failure("{message: x, 'true': [{command: y}]}"))
        assertTrue("not a delay" in failure("delay: soon"))
    }

    @Test
    fun aBadFileKeepsTheLoadedAnnouncements() {
        save(example)
        val store = store()
        store.scan()
        save("\"a\":\n  actions:\n  - delay: soon\n", 2_000)
        assertFailsWith<SerializationException> { store.scan() }
        assertEquals(setOf("rank-example"), store.announcements.keys)
    }

    @Test
    fun delaysAddUpTheirTokens() {
        assertEquals(1.seconds, parseDelay("1"))
        assertEquals(2500.milliseconds, parseDelay("2s 10t"))
        assertEquals(500.milliseconds, parseDelay("0.5"))
        assertEquals(90.seconds, parseDelay("1m 30s"))
        assertEquals(250.milliseconds, parseDelay("250ms"))
        assertNull(parseDelay(""))
        assertNull(parseDelay("2 seconds"))
        assertNull(parseDelay("s"))
    }

    private fun test(condition: String, vararg values: Pair<String, String>): Boolean =
        Action.Condition(condition).matches { text -> values.fold(text) { result, pair -> result.replace(pair.first, pair.second) } }

    @Test
    fun aBareValueIsTrueOrFalse() {
        assertTrue(test("%linked%", "%linked%" to "true"))
        assertTrue(test("%linked%", "%linked%" to "TRUE"))
        assertTrue(test("%linked%", "%linked%" to "yes"))
        assertFalse(test("%linked%", "%linked%" to "false"))
        assertFalse(test("%linked%", "%linked%" to "%linked%"), "an unresolved placeholder is false")
        assertFalse(test(""))
    }

    @Test
    fun valuesAreComparedByTheOperatorWrittenInTheCondition() {
        assertTrue(test("%g% != \"example\"", "%g%" to "default"))
        assertFalse(test("%g% != \"example\"", "%g%" to "example"))
        assertTrue(test("%g% == example", "%g%" to "example"))
        assertTrue(test("'%g%' == 'a b'", "%g%" to "a b"))
        assertFalse(test("%g% == example", "%g%" to "Example"), "text is compared exactly")

        assertTrue(test("%n% >= 10", "%n%" to "10"))
        assertTrue(test("%n% > 9.5", "%n%" to "10"))
        assertFalse(test("%n% < 10", "%n%" to "10"))
        assertTrue(test("%n% == 1.0", "%n%" to "1"), "numbers compare as numbers")
        assertFalse(test("%n% > a", "%n%" to "b"), "ordering needs numbers")

        // An operator in a placeholder's value or in quotes is not the operator.
        assertTrue(test("%g% == 'a==b'", "%g%" to "a==b"))
        assertTrue(test("%g% == x", "%g%" to "x"))
        assertTrue(test("%g% != x", "%g%" to "x != x"), "the value can't add an operator")
        assertTrue(test("%g% == 'a<b'", "%g%" to "a<b"))
    }
}
