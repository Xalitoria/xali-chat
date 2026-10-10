package mc.xalitoria.chat.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * What the servers and the proxy tell each other. [encode] writes the subchannel as a `writeUTF` string followed by
 * the fields below; strings are `writeUTF` too, and the field that ends a message takes all the bytes that remain.
 */
sealed interface Message {
    fun encode(): ByteArray

    /** `CONFIG_SYNC`: the bytes of [file] (`announcements.yml`), sent when it is saved and relayed to the other servers. */
    class ConfigSync(val file: String, val content: ByteArray) : Message {
        override fun encode() = write("CONFIG_SYNC") { writeUTF(file); write(content) }
    }

    /** `BROADCAST`: a finished message as Adventure JSON, shown to everyone online; [bypassMute] reaches muted players too. */
    class Broadcast(val bypassMute: Boolean, val json: String) : Message {
        override fun encode() = write("BROADCAST") { writeBoolean(bypassMute); write(json.toByteArray()) }
    }

    /** `ANNOUNCE`: play the announcement [key] for the player [uuid] ([name] when known). */
    class Announce(val key: String, val uuid: UUID, val name: String?) : Message {
        override fun encode() = write("ANNOUNCE") { writeUTF(key); writeUTF(uuid.toString()); writeUTF(name.orEmpty()) }
    }

    companion object {
        /** Reads a message written by [encode]; null for a subchannel this version doesn't know. */
        fun decode(bytes: ByteArray): Message? = DataInputStream(ByteArrayInputStream(bytes)).run {
            when (readUTF()) {
                "CONFIG_SYNC" -> ConfigSync(readUTF(), readAllBytes())
                "BROADCAST" -> Broadcast(readBoolean(), String(readAllBytes()))
                "ANNOUNCE" -> Announce(readUTF(), UUID.fromString(readUTF()), readUTF().takeIf { it.isNotEmpty() })
                else -> null
            }
        }

        private fun write(subchannel: String, body: DataOutputStream.() -> Unit): ByteArray =
            ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(subchannel); body() } }.toByteArray()
    }
}
