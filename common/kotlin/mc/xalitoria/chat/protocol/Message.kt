package mc.xalitoria.chat.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** One announcement as it is shared: [key], when it was last saved, and its content as JSON, or null for a removal. */
class Entry(val key: String, val modified: Long, val json: String?)

/**
 * What the servers and the proxy tell each other. [encode] writes the subchannel as a `writeUTF` string followed by
 * the fields below; strings are `writeUTF` too, and the field that ends a message takes all the bytes that remain.
 */
sealed interface Message {
    fun encode(): ByteArray

    /** `BROADCAST`: a finished message as Adventure JSON, shown to everyone online; [bypassMute] reaches muted players too. */
    class Broadcast(val bypassMute: Boolean, val json: String) : Message {
        override fun encode() = write("BROADCAST") { writeBoolean(bypassMute); write(json.toByteArray()) }
    }

    /** `ANNOUNCE`: play the announcement [key], for the player [uuid] ([name] when known) or for nobody in particular when null. */
    class Announce(val key: String, val uuid: UUID?, val name: String?) : Message {
        override fun encode() = write("ANNOUNCE") { writeUTF(key); writeUTF(uuid?.toString().orEmpty()); writeUTF(name.orEmpty()) }
    }

    /**
     * `WHISPER`: a private message from [from] to [to], for the proxy to find on whichever server [to] is on. [id]
     * ties the [WhisperAck] back to the command that sent it; [bypassIgnore] is the sender's permission to reach
     * players who ignore them, since only the receiving server knows who ignores whom, and [seesVanished] that they
     * may see vanished players, who are otherwise not found.
     */
    class Whisper(
        val id: UUID,
        val from: String,
        val fromUuid: UUID?,
        val bypassIgnore: Boolean,
        val seesVanished: Boolean,
        val to: String,
        val text: String,
    ) : Message {
        override fun encode() = write("WHISPER") {
            writeUTF(id.toString()); writeUTF(from); writeUTF(fromUuid?.toString().orEmpty())
            writeBoolean(bypassIgnore); writeBoolean(seesVanished); writeUTF(to); write(text.toByteArray())
        }
    }

    /** `WHISPER_ACK`: how the [Whisper] [id] ended, with the receiver's exact [name] when it was found. */
    class WhisperAck(val id: UUID, val status: Status, val name: String?) : Message {
        /** [DECLINED]: a gate of another plugin (the friend list, say) turned the message away. Last, as the wire carries the ordinal. */
        enum class Status { DELIVERED, NOT_FOUND, IGNORED, DECLINED }

        override fun encode() = write("WHISPER_ACK") { writeUTF(id.toString()); writeByte(status.ordinal); writeUTF(name.orEmpty()) }
    }

    /**
     * `PLAYERS`: everyone on the proxy, for a server's tab completion. [hidden] maps a vanished player to the
     * players on the receiving server who can't see them, so it stays small however many players there are.
     */
    class Players(val names: List<String>, val hidden: Map<String, List<String>>) : Message {
        override fun encode() = write("PLAYERS") {
            writeInt(names.size)
            names.forEach { writeUTF(it) }
            writeInt(hidden.size)
            hidden.forEach { (target = key, viewers = value) ->
                writeUTF(target)
                writeInt(viewers.size)
                viewers.forEach { writeUTF(it) }
            }
        }
    }

    companion object {
        /** Reads a message written by [encode]; null for a subchannel this version doesn't know. */
        fun decode(bytes: ByteArray): Message? = DataInputStream(ByteArrayInputStream(bytes)).run {
            when (readUTF()) {
                "BROADCAST" -> Broadcast(readBoolean(), String(readAllBytes()))
                "ANNOUNCE" -> Announce(readUTF(), readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString), readUTF().takeIf { it.isNotEmpty() })
                "WHISPER" -> Whisper(
                    UUID.fromString(readUTF()), readUTF(), readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString),
                    readBoolean(), readBoolean(), readUTF(), String(readAllBytes()),
                )
                "WHISPER_ACK" -> WhisperAck(
                    UUID.fromString(readUTF()), WhisperAck.Status.entries[readByte().toInt()], readUTF().takeIf { it.isNotEmpty() },
                )
                "PLAYERS" -> Players(
                    List(readInt()) { readUTF() },
                    buildMap { repeat(readInt()) { put(readUTF(), List(readInt()) { readUTF() }) } },
                )
                else -> null
            }
        }

        private fun write(subchannel: String, body: DataOutputStream.() -> Unit): ByteArray =
            ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(subchannel); body() } }.toByteArray()
    }
}
