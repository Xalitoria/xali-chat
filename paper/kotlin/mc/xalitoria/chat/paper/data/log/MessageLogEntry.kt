package mc.xalitoria.chat.paper.data.log

import mc.xalitoria.util.serial.UniqueId

/** A private message from [sender] to [receiver]; an id is null for the console and for a receiver on another server. */
data class MessageLogEntry(
    val timestampMillis: Long,
    val senderId: UniqueId?,
    val sender: String,
    val receiverId: UniqueId?,
    val receiver: String,
    val message: String,
)
