package mc.xalitoria.chat.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** One announcement as it travels: [key], when it was last saved, and its content as JSON, or null for a removal. */
class Entry(val key: String, val modified: Long, val json: String?)

/**
 * What the servers and the proxy tell each other. [encode] writes the subchannel as a `writeUTF` string followed by
 * the fields below; strings are `writeUTF` too, and the field that ends a message takes all the bytes that remain.
 * An entry list is an `int` count, then per entry `UTF key`, `long modified` and an `int` length (-1 for a removal)
 * followed by that many bytes of JSON.
 */
sealed interface Message {
    fun encode(): ByteArray

    /** `PUSH`: a server's saved changes, for the proxy to keep and pass on to the servers that need them. */
    class Push(val entries: List<Entry>) : Message {
        override fun encode() = write("PUSH") { writeEntries(entries) }
    }

    /** `UPDATE`: entries for a server to apply, from the proxy. Each is taken only if it is newer than what the server has. */
    class Update(val entries: List<Entry>) : Message {
        override fun encode() = write("UPDATE") { writeEntries(entries) }
    }

    /** `PULL`: a server's keys and when each was saved, asking the proxy for what it should hold instead. */
    class Pull(val known: Map<String, Long>) : Message {
        override fun encode() = write("PULL") {
            writeInt(known.size)
            known.forEach { (key = key, modified = value) -> writeUTF(key); writeLong(modified) }
        }
    }

    /** `BROADCAST`: a finished message as Adventure JSON, shown to everyone online; [bypassMute] reaches muted players too. */
    class Broadcast(val bypassMute: Boolean, val json: String) : Message {
        override fun encode() = write("BROADCAST") { writeBoolean(bypassMute); write(json.toByteArray()) }
    }

    /** `ANNOUNCE`: play the announcement [key] for the player [uuid] ([name] when known). */
    class Announce(val key: String, val uuid: UUID, val name: String?) : Message {
        override fun encode() = write("ANNOUNCE") { writeUTF(key); writeUTF(uuid.toString()); writeUTF(name.orEmpty()) }
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
        enum class Status { DELIVERED, NOT_FOUND, IGNORED }

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
                "PUSH" -> Push(readEntries())
                "UPDATE" -> Update(readEntries())
                "PULL" -> Pull(buildMap { repeat(readInt()) { put(readUTF(), readLong()) } })
                "BROADCAST" -> Broadcast(readBoolean(), String(readAllBytes()))
                "ANNOUNCE" -> Announce(readUTF(), UUID.fromString(readUTF()), readUTF().takeIf { it.isNotEmpty() })
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

        private fun DataOutputStream.writeEntries(entries: List<Entry>) {
            writeInt(entries.size)
            entries.forEach { entry ->
                writeUTF(entry.key)
                writeLong(entry.modified)
                val body = entry.json?.toByteArray()
                writeInt(body?.size ?: -1)
                if (body != null) write(body)
            }
        }

        private fun DataInputStream.readEntries(): List<Entry> = List(readInt()) {
            val key = readUTF()
            val modified = readLong()
            val length = readInt()
            Entry(key, modified, if (length < 0) null else String(readNBytes(length)))
        }

        private fun write(subchannel: String, body: DataOutputStream.() -> Unit): ByteArray =
            ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(subchannel); body() } }.toByteArray()
    }
}
