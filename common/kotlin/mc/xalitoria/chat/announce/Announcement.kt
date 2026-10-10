package mc.xalitoria.chat.announce

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable

/** One entry of `announcements.yml`; the file maps each announcement's key to this. */
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
        "Chat lines. &-codes, &#RRGGBB and MiniMessage work; {name}, {uuid}, %player_name% and other",
        "PlaceholderAPI placeholders resolve for the player it was run for (each viewer when there is none).",
    )
    val messages: List<String> = [],
    @YamlComment(
        "Console commands run in order after the messages, with the same placeholders.",
        "'delay <time>...' pauses the list: 2s 10t is 2.5 seconds (t ticks, ms, s, m, h, d; a bare number is seconds).",
    )
    val commands: List<String> = [],
) {
    /** Whether [server] is named by [servers]: any entry matches, `*` stands for any text and `?` for one character. */
    fun appliesTo(server: String): Boolean = servers.isEmpty() || servers.any { pattern ->
        Regex(pattern.map { if (it == '*') ".*" else if (it == '?') "." else Regex.escape(it.toString()) }.joinToString(""), RegexOption.IGNORE_CASE)
            .matches(server)
    }
}
