package mc.xalitoria.chat.paper.data.log

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

data class ChatLogSearch(
    val player: String?,
    val sinceMillis: Long?,
    /** Raw `since:` flag text (e.g. `"2h"`), kept so pagination links can reproduce this exact search. */
    val sinceInput: String?,
    /** Raw `server:` flag text (a name, or `*` for all); `null` means this server. */
    val serverInput: String?,
    val textFilter: String?,
    val results: List<ChatLogEntry>,
)

/** Each sender's last search, so `/chatlog page:2` can reuse it. Expires after five minutes. */
object ChatLogSessions {
    private val TTL_MILLIS = 5.minutes.inWholeMilliseconds

    private data class Entry(val search: ChatLogSearch, val storedAt: Long)

    private val sessions = ConcurrentHashMap<String, Entry>()

    fun get(senderName: String): ChatLogSearch? {
        val entry = sessions[senderName] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MILLIS) {
            sessions.remove(senderName)
            return null
        }
        return entry.search
    }

    fun put(senderName: String, search: ChatLogSearch) {
        sessions[senderName] = Entry(search, System.currentTimeMillis())
    }
}
