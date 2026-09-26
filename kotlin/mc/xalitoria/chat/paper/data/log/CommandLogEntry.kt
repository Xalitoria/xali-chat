package mc.xalitoria.chat.paper.data.log

import kotlinx.serialization.Serializable
import mc.xalitoria.util.serial.UniqueId

@Serializable
data class CommandLogEntry(
    val timestampMillis: Long,
    val uniqueId: UniqueId,
    val username: String,
    /** The command as typed, without the leading slash. */
    val command: String,
)
