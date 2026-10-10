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

    companion object {
        /** Reads a message written by [encode]; null for a subchannel this version doesn't know. */
        fun decode(bytes: ByteArray): Message? = DataInputStream(ByteArrayInputStream(bytes)).run {
            when (readUTF()) {
                "PUSH" -> Push(readEntries())
                "UPDATE" -> Update(readEntries())
                "PULL" -> Pull(buildMap { repeat(readInt()) { put(readUTF(), readLong()) } })
                "BROADCAST" -> Broadcast(readBoolean(), String(readAllBytes()))
                "ANNOUNCE" -> Announce(readUTF(), UUID.fromString(readUTF()), readUTF().takeIf { it.isNotEmpty() })
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
