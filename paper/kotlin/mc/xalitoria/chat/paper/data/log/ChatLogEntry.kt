package mc.xalitoria.chat.paper.data.log

import com.xalitoria.util.serial.UniqueId
import kotlinx.serialization.Serializable

@Serializable
data class ChatLogEntry(
    val timestampMillis: Long,
    val uniqueId: UniqueId,
    val username: String,
    val message: String,
)
