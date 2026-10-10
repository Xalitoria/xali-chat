package mc.xalitoria.chat.paper.concurrent

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.announce.Announcements
import mc.xalitoria.util.message.GCOMP
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProxyMessenger(private val plugin: XaliChat) : PluginMessageListener {
    private val pending = ConcurrentHashMap<UUID, CompletableDeferred<LookupResult>>()

    /**
     * Velocity's forwarding secret from Paper's own `config/paper-global.yml` (`proxies.velocity`), which the proxy
     * reads from its `forwarding-secret-file` in velocity.toml: the one secret the two already share. Empty, so the
     * signed messages stay off, unless Velocity forwarding is enabled. Paper only reads it at startup, so this does too.
     */
    val secret: String = try {
        Yaml(configuration = YamlConfiguration(strictMode = false))
            .decodeFromString(PaperGlobal.serializer(), plugin.server.pluginsFolder.toPath().resolveSibling("config/paper-global.yml").readText())
            .proxies.velocity.takeIf { it.enabled }?.secret.orEmpty()
    } catch (e: Exception) {
        debug("Could not read the Velocity secret from paper-global.yml: {0}", e)
        ""
    }

    @Serializable
    private class PaperGlobal(val proxies: Proxies = Proxies()) {
        @Serializable
        class Proxies(val velocity: Velocity = Velocity())

        @Serializable
        class Velocity(val enabled: Boolean = false, val secret: String = "")
    }

    /** Pieces of the large messages still arriving, by transfer id; a transfer that never completes is dropped. */
    private val transfers = ConcurrentHashMap<String, Array<ByteArray?>>()

    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray
    ) {
        if (channel != CHANNEL) return
        runCatching { handle(message, signed = false) }.onFailure { debug("Ignored a malformed proxy message: {0}", it) }
    }

    /**
     * Anything a client can send to a plugin channel reaches here too, so the messages that change something (config
     * sync, global broadcasts and announcements) are only acted on inside a `SIGNED` frame whose HMAC checks out.
     */
    private fun handle(message: ByteArray, signed: Boolean) {
        DataInputStream(ByteArrayInputStream(message)).use { input ->
            when (val subchannel = input.readUTF()) {
                "LOOKUP_RES" -> {
                    val requestId = UUID.fromString(input.readUTF())
                    val found = input.readBoolean()
                    val exactName = input.readUTF().takeIf { it.isNotEmpty() }
                    val serverName = input.readUTF().takeIf { it.isNotEmpty() }

                    debug("Proxy lookup {0} answered: found={1}, name={2}, server={3}", requestId, found, exactName, serverName)
                    pending.remove(requestId)?.complete(
                        LookupResult(found, exactName, serverName)
                    )
                }
                "CHUNK" -> {
                    val id = input.readUTF()
                    val index = input.readInt()
                    val total = input.readInt()
                    if (total !in 1..MAX_CHUNKS || index !in 0 until total) return
                    val pieces = transfers.computeIfAbsent(id) {
                        plugin.scope.launch { delay(30.seconds); transfers.remove(id) }
                        arrayOfNulls(total)
                    }
                    if (pieces.size != total) return
                    pieces[index] = input.readAllBytes()
                    if (pieces.any { it == null }) return
                    transfers.remove(id)
                    debug("Reassembled transfer {0} from {1} chunks", id, total)
                    handle(pieces.filterNotNull().reduce { whole, piece -> whole + piece }, signed = false)
                }
                "SIGNED" -> {
                    val mac = input.readNBytes(MAC_BYTES)
                    val inner = input.readAllBytes()
                    if (secret.isEmpty() || !MessageDigest.isEqual(mac, mac(inner))) {
                        debug("Ignored a proxy message with a missing or wrong signature")
                        return
                    }
                    handle(inner, signed = true)
                }
                "CONFIG_SYNC" -> if (signed && input.readUTF() == Announcements.FILE) plugin.announcements.receive(input.readAllBytes())
                "BROADCAST" -> if (signed) {
                    val bypassMute = input.readBoolean()
                    val component = GCOMP.deserialize(String(input.readAllBytes()))
                    plugin.announcements.send(bypassMute) { listOf(component) }
                }
                "ANNOUNCE" -> if (signed) {
                    val key = input.readUTF()
                    val uuid = UUID.fromString(input.readUTF())
                    val name = input.readUTF().takeIf { it.isNotEmpty() }
                    plugin.announcements.announce(key, uuid, name, respectServers = true)
                }
                else -> debug("Ignored proxy message {0} (signed={1})", subchannel, signed)
            }
        }
    }

    private fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        .doFinal(data)

    private fun carrier(): Player? =
        Bukkit.getOnlinePlayers().firstOrNull()

    suspend fun lookupPlayerOnProxy(name: String, timeoutMs: Long = 5000): LookupResult? {
        val carrier = carrier() ?: run {
            debug("Proxy lookup for {0} skipped: no player to carry the message", name)
            return null
        }
        val requestId = UUID.randomUUID()

        val deferred = CompletableDeferred<LookupResult>()
        pending[requestId] = deferred

        val payload = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.writeUTF("LOOKUP_REQ")
                out.writeUTF(requestId.toString())
                out.writeUTF(name)
            }
            baos.toByteArray()
        }

        carrier.sendPluginMessage(plugin, CHANNEL, payload)

        val res = withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
        if (res == null) {
            debug("Proxy lookup {0} for {1} timed out after {2}ms", requestId, name, timeoutMs)
            pending.remove(requestId)
        }
        return res
    }

    fun sendMsgOnProxy(senderName: String, receiverName: String, msg: String): Boolean {
        val carrier = carrier() ?: run {
            debug("Proxy message from {0} to {1} not sent: no player to carry it", senderName, receiverName)
            return false
        }
        debug("Sending proxy message from {0} to {1}", senderName, receiverName)

        val payload = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.writeUTF("MSG_SEND")
                out.writeUTF(senderName)
                out.writeUTF(receiverName)
                out.writeUTF(msg)
            }
            baos.toByteArray()
        }

        carrier.sendPluginMessage(plugin, CHANNEL, payload)
        return true
    }

    /**
     * Sends [subchannel] followed by whatever [body] writes to the proxy, signed with the Velocity [secret]. A message too big
     * for one plugin message goes out as `CHUNK` frames that the receiver puts back together. Returns false, sending
     * nothing, when there is no secret or no player online to carry it.
     */
    fun sendSigned(subchannel: String, body: DataOutputStream.() -> Unit): Boolean {
        if (secret.isEmpty()) {
            debug("Proxy message {0} not sent: no Velocity secret in paper-global.yml", subchannel)
            return false
        }
        val carrier = carrier() ?: run {
            debug("Proxy message {0} not sent: no player to carry it", subchannel)
            return false
        }

        val inner = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(subchannel); body() } }.toByteArray()
        val signed = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF("SIGNED"); write(mac(inner)); write(inner) } }.toByteArray()
        if (signed.size <= CHUNK_BYTES) {
            carrier.sendPluginMessage(plugin, CHANNEL, signed)
            return true
        }

        val id = UUID.randomUUID().toString()
        val total = (signed.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        debug("Sending {0} as {1} chunks ({2} bytes), transfer {3}", subchannel, total, signed.size, id)
        for (index in 0 until total) {
            val frame = ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeUTF("CHUNK")
                    writeUTF(id)
                    writeInt(index)
                    writeInt(total)
                    write(signed, index * CHUNK_BYTES, minOf(CHUNK_BYTES, signed.size - index * CHUNK_BYTES))
                }
            }.toByteArray()
            carrier.sendPluginMessage(plugin, CHANNEL, frame)
        }
        return true
    }

    companion object {
        const val CHANNEL = "xalichat:main"

        /** Bukkit refuses plugin messages over 32766 bytes; this leaves room for a chunk's header. */
        private const val CHUNK_BYTES = 30_000
        private const val MAX_CHUNKS = 64
        private const val MAC_BYTES = 32
    }
}
