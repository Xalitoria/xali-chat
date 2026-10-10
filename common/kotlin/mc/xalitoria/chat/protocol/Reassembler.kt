package mc.xalitoria.chat.protocol

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Puts `CHUNK` frames back together. Use one per sender, since a transfer id only means something to whoever made
 * it. A transfer that doesn't finish within [ttl] is dropped, and so are frames that claim an absurd size.
 */
class Reassembler(private val ttl: Duration = 30.seconds, private val nanos: () -> Long = System::nanoTime) {
    private class Transfer(val pieces: Array<ByteArray?>, val started: Long)

    private val transfers = HashMap<String, Transfer>()

    /** The whole frame once [frame] completes it, [frame] itself when it isn't a chunk, and null while pieces are missing. */
    @Synchronized
    fun accept(frame: ByteArray): ByteArray? {
        val input = DataInputStream(ByteArrayInputStream(frame))
        if (input.readUTF() != Protocol.CHUNK) return frame

        val now = nanos()
        transfers.values.removeIf { now - it.started > ttl.inWholeNanoseconds }
        val id = input.readUTF()
        val index = input.readInt()
        val total = input.readInt()
        if (total !in 1..Protocol.MAX_CHUNKS || index !in 0 until total) return null

        val transfer = transfers.getOrPut(id) { Transfer(arrayOfNulls(total), now) }
        if (transfer.pieces.size != total) return null
        transfer.pieces[index] = input.readAllBytes()
        if (transfer.pieces.any { it == null }) return null

        transfers.remove(id)
        return transfer.pieces.filterNotNull().reduce { whole, piece -> whole + piece }
    }
}
