package mc.xalitoria.chat.protocol

import java.io.*
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a [Message] for the plugin channel and opens one that arrived.
 *
 * Anything a client can send to a plugin channel reaches the plugins too, and these messages change config and run
 * console commands, so each travels in a `SIGNED` frame: `UTF "SIGNED"`, the 32-byte HMAC-SHA256 of the message, then
 * the message. The key is Velocity's forwarding secret, which the proxy and every server already share.
 *
 * A signed frame too big for one plugin message (Bukkit refuses over 32 766 bytes) goes out as `CHUNK` frames:
 * `UTF "CHUNK"`, `UTF id`, `int index`, `int total`, bytes. The slices in index order are the whole signed frame.
 */
object Protocol {
    const val CHANNEL = "xalichat:main"
    const val SIGNED = "SIGNED"
    const val CHUNK = "CHUNK"

    /** Room for a chunk's header under Bukkit's limit. */
    const val CHUNK_BYTES = 30_000
    const val MAX_CHUNKS = 64
    private const val MAC_BYTES = 32

    /** [message] signed with [secret], as the frames to send in order: one, or several when it needs chunking. */
    fun seal(secret: String, message: Message): List<ByteArray> {
        val inner = message.encode()
        val signed = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeUTF(SIGNED); write(mac(secret, inner)); write(inner) }
        }.toByteArray()
        if (signed.size <= CHUNK_BYTES) return listOf(signed)

        val id = UUID.randomUUID().toString()
        val total = (signed.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return List(total) { index ->
            ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeUTF(CHUNK)
                    writeUTF(id)
                    writeInt(index)
                    writeInt(total)
                    write(signed, index * CHUNK_BYTES, minOf(CHUNK_BYTES, signed.size - index * CHUNK_BYTES))
                }
            }.toByteArray()
        }
    }

    /** The message in a whole `SIGNED` [frame]; null when it is unsigned, forged, signed with another secret or unknown. */
    fun open(secret: String?, frame: ByteArray): Message? {
        val secret = secret.orEmpty()
        if (secret.isBlank()) return null
        val input = DataInputStream(ByteArrayInputStream(frame))
        if (input.readUTF() != SIGNED) return null
        val mac = input.readNBytes(MAC_BYTES)
        val inner = input.readAllBytes()
        return if (MessageDigest.isEqual(mac, mac(secret, inner))) Message.decode(inner) else null
    }

    /** The subchannel a frame starts with, such as [SIGNED], [CHUNK] or one of the proxy's own. */
    fun subchannel(frame: ByteArray): String = DataInputStream(ByteArrayInputStream(frame)).readUTF()

    // Whitespace around the secret is trimmed on both sides, since a secret file usually ends in a newline.
    private fun mac(secret: String, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(secret.trim().toByteArray(), "HmacSHA256")) }
        .doFinal(data)
}
