package mc.xalitoria.chat.announce

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** One entry of `announcements.yml`; the file maps each announcement's key to this. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Announcement(
    @YamlComment("Set to false to switch it off without deleting it.")
    val enable: Boolean = true,
    @YamlComment(
        "Seconds before the first automatic run. With delay and repeat both 0 it only runs from",
        "/xannounce and /xgannounce.",
    )
    val delay: Long = 0,
    @YamlComment("Seconds between automatic runs; 0 runs it once.")
    val repeat: Long = 0,
    @YamlComment(
        "Servers that hold and run it: names as the proxy knows them, with * and ? wildcards (skyblock-*).",
        "Empty means every server. Only the servers listed keep it in their file.",
    )
    val servers: List<String> = [],
    @YamlComment(
        "Run in order. Each action is one of:",
        "  delay: 2s 10t           pause: seconds, or a number and unit (t ticks, ms, s, m, h, d)",
        "  command: \"say hi\"       console command",
        "  message: \"text\"         chat line, or a list of lines; &-codes, &#RRGGBB and MiniMessage work",
        "  condition: \"%a% == b\"   runs the actions under 'true': or else those under 'false':",
        "{name}, {uuid}, %player_name% and other PlaceholderAPI placeholders resolve for the player it was run for",
        "(each viewer for a message when there is none).",
    )
    val actions: List<Action> = [],
    /** The format before [actions]: still read, as the messages and then the commands; never written. */
    @EncodeDefault(NEVER) val messages: List<String> = [],
    @EncodeDefault(NEVER) val commands: List<String> = [],
) {
    /** Whether [server] is named by [servers]: any entry matches, `*` stands for any text and `?` for one character. */
    fun appliesTo(server: String): Boolean = serverMatches(servers, server)

    /** This with [messages] and [commands] (the old format) folded into the front of [actions], so only actions are left. */
    fun normalized(): Announcement {
        if (messages.isEmpty() && commands.isEmpty()) return this
        val old = ArrayList<Action>()
        if (messages.isNotEmpty()) old += Action.Message(messages)
        for (command in commands) {
            val words = command.trim().split(Regex("\\s+"))
            if (!words[0].equals("delay", ignoreCase = true)) {
                old += Action.Command(command)
                continue
            }
            val time = words.drop(1).joinToString(" ")
            if (parseDelay(time) != null) old += Action.Delay(time)
        }
        return copy(actions = old + actions, messages = [], commands = [])
    }
}

/** Whether [server] is named by [patterns]: any matches, `*` standing for any text and `?` for one character; none names every server. */
fun serverMatches(patterns: List<String>, server: String): Boolean = patterns.isEmpty() || patterns.any { pattern ->
    Regex(pattern.map { if (it == '*') ".*" else if (it == '?') "." else Regex.escape(it.toString()) }.joinToString(""), RegexOption.IGNORE_CASE)
        .matches(server)
}
