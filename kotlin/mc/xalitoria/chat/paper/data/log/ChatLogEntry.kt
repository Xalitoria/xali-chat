package mc.xalitoria.chat.paper.data.log

import kotlinx.serialization.Serializable
import mc.xalitoria.util.serial.UniqueId

@Serializable
data class ChatLogEntry(
    val timestampMillis: Long,
    val uniqueId: UniqueId,
    val username: String,
    val message: String,
)
