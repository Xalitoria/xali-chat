package mc.xalitoria.chat.paper.data.log

import com.xalitoria.util.serial.UniqueId
import kotlinx.serialization.Serializable

@Serializable
data class CommandLogEntry(
    val timestampMillis: Long,
    val uniqueId: UniqueId,
    val username: String,
    /** The command as typed, without the leading slash. */
    val command: String,
)
