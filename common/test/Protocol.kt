import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.protocol.Protocol
import mc.xalitoria.chat.protocol.Reassembler
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class Protocol {
    private val secret = "forwarding-secret"

    /** Sends [frames] through a reassembler and opens whatever comes out, the way a receiver does. */
    private fun receive(frames: List<ByteArray>, opener: String = secret): Message? {
        val reassembler = Reassembler()
        return frames.firstNotNullOfOrNull { reassembler.accept(it) }?.let { Protocol.open(opener, it) }
    }

    @Test
    fun messagesRoundTrip() {
        val uuid = UUID.randomUUID()
        val broadcast = assertIs<Message.Broadcast>(receive(Protocol.seal(secret, Message.Broadcast(true, """{"text":"hi é"}"""))))
        assertEquals(true, broadcast.bypassMute)
        assertEquals("""{"text":"hi é"}""", broadcast.json)

        val announce = assertIs<Message.Announce>(receive(Protocol.seal(secret, Message.Announce("rank", uuid, "Notch"))))
        assertEquals(Triple("rank", uuid, "Notch"), Triple(announce.key, announce.uuid, announce.name))
        assertNull(assertIs<Message.Announce>(receive(Protocol.seal(secret, Message.Announce("rank", uuid, null)))).name)

        val whisper = assertIs<Message.Whisper>(receive(Protocol.seal(secret, Message.Whisper(uuid, "Notch", uuid, true, true, "jeb_", "hi é"))))
        assertEquals(listOf(uuid, "Notch", uuid, true, true, "jeb_", "hi é"), listOf(whisper.id, whisper.from, whisper.fromUuid, whisper.bypassIgnore, whisper.seesVanished, whisper.to, whisper.text))
        assertNull(assertIs<Message.Whisper>(receive(Protocol.seal(secret, Message.Whisper(uuid, "CONSOLE", null, false, false, "jeb_", "")))).fromUuid)

        val ack = assertIs<Message.WhisperAck>(receive(Protocol.seal(secret, Message.WhisperAck(uuid, Message.WhisperAck.Status.IGNORED, "Jeb_"))))
        assertEquals(Triple(uuid, Message.WhisperAck.Status.IGNORED, "Jeb_"), Triple(ack.id, ack.status, ack.name))
        assertNull(assertIs<Message.WhisperAck>(receive(Protocol.seal(secret, Message.WhisperAck(uuid, Message.WhisperAck.Status.NOT_FOUND, null)))).name)
        assertEquals(Message.WhisperAck.Status.DECLINED, assertIs<Message.WhisperAck>(receive(Protocol.seal(secret, Message.WhisperAck(uuid, Message.WhisperAck.Status.DECLINED, "Jeb_")))).status)

        val players = assertIs<Message.Players>(receive(Protocol.seal(secret, Message.Players(["Notch", "jeb_", "Dinnerbone"], mapOf("jeb_" to listOf("Notch"), "Dinnerbone" to emptyList())))))
        assertEquals(listOf("Notch", "jeb_", "Dinnerbone"), players.names)
        assertEquals(mapOf("jeb_" to listOf("Notch"), "Dinnerbone" to emptyList()), players.hidden)
        assertEquals(emptyList(), assertIs<Message.Players>(receive(Protocol.seal(secret, Message.Players([], emptyMap())))).names)
    }

    @Test
    fun wrongOrMissingSecretIsRejected() {
        val frames = Protocol.seal(secret, Message.Announce("rank", UUID.randomUUID(), null))
        assertNull(receive(frames, "another-secret"))
        assertNull(receive(frames, ""))
        assertNull(Protocol.open(secret, Message.Announce("rank", UUID.randomUUID(), null).encode()), "unsigned")
    }

    @Test
    fun tamperedFrameIsRejected() {
        val frame = Protocol.seal(secret, Message.Announce("rank", UUID.randomUUID(), "Notch")).single()
        frame[frame.size - 1] = (frame.last() + 1).toByte()
        assertNull(Protocol.open(secret, frame))
    }

    @Test
    fun secretWhitespaceDoesNotMatter() {
        assertIs<Message.Announce>(receive(Protocol.seal("$secret\n", Message.Announce("rank", UUID.randomUUID(), null)), " $secret"))
    }

    @Test
    fun smallMessageIsOneFrame() {
        assertEquals(1, Protocol.seal(secret, Message.Broadcast(false, "{}")).size)
    }

    @Test
    fun bigMessageIsChunkedAndReassembledInAnyOrder() {
        val content = "x".repeat(81_380)
        val frames = Protocol.seal(secret, Message.Broadcast(false, content))
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size <= 32_766 }, "each frame fits a plugin message")

        val reassembler = Reassembler()
        val whole = listOf(frames[2], frames[0], frames[1]).mapNotNull { reassembler.accept(it) }.single()
        assertEquals(content, assertIs<Message.Broadcast>(Protocol.open(secret, whole)).json)
    }

    @Test
    fun unfinishedTransferExpires() {
        var now = 0L
        val reassembler = Reassembler(30.seconds) { now }
        val frames = Protocol.seal(secret, Message.Broadcast(false, "y".repeat(70_000)))
        assertNull(reassembler.accept(frames[0]))
        now += 31.seconds.inWholeNanoseconds
        assertNull(reassembler.accept(frames[1]))
        assertNull(reassembler.accept(frames[2]), "the first piece is gone, so the transfer can't finish")
    }

    @Test
    fun absurdChunkHeadersAreDropped() {
        val reassembler = Reassembler()
        fun chunk(index: Int, total: Int) = java.io.ByteArrayOutputStream().also {
            java.io.DataOutputStream(it).apply { writeUTF("CHUNK"); writeUTF("x"); writeInt(index); writeInt(total); write(1) }
        }.toByteArray()
        assertNull(reassembler.accept(chunk(0, 0)))
        assertNull(reassembler.accept(chunk(5, 2)))
        assertNull(reassembler.accept(chunk(0, 1_000_000)))
    }

    @Test
    fun otherFramesPassThroughTheReassembler() {
        val frame = java.io.ByteArrayOutputStream().also { java.io.DataOutputStream(it).writeUTF("LOOKUP_RES") }.toByteArray()
        assertContentEquals(frame, Reassembler().accept(frame))
        assertEquals("LOOKUP_RES", Protocol.subchannel(frame))
    }

    @Test
    fun unknownSubchannelIsNull() {
        assertNull(Message.decode(java.io.ByteArrayOutputStream().also { java.io.DataOutputStream(it).writeUTF("NOPE") }.toByteArray()))
    }
}
