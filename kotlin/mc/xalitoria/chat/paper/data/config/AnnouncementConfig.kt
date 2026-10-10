package mc.xalitoria.chat.paper.data.config

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
    @YamlComment("Servers (database.server-name in config.yml) that run it automatically or globally; empty means all.")
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
    fun appliesTo(server: String): Boolean = servers.isEmpty() || servers.any { it.equals(server, ignoreCase = true) }
}
