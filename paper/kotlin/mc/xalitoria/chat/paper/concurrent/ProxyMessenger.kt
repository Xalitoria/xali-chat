package mc.xalitoria.chat.paper.concurrent

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.protocol.Protocol
import mc.xalitoria.chat.protocol.Reassembler
import mc.xalitoria.util.message.GCOMP
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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

    private val reassembler = Reassembler()

    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray
    ) {
        if (channel != CHANNEL) return
        runCatching { handle(message) }.onFailure { debug("Ignored a malformed proxy message: {0}", it) }
    }

    /**
     * Anything a client can send to a plugin channel reaches here too, so the messages that change something (config
     * sync, global broadcasts and announcements) are only acted on inside a `SIGNED` frame whose HMAC checks out.
     */
    private fun handle(message: ByteArray) {
        val frame = reassembler.accept(message) ?: return
        when (val subchannel = Protocol.subchannel(frame)) {
            "LOOKUP_RES" -> DataInputStream(ByteArrayInputStream(frame)).use { input ->
                input.readUTF()
                val requestId = UUID.fromString(input.readUTF())
                val found = input.readBoolean()
                val exactName = input.readUTF().takeIf { it.isNotEmpty() }
                val serverName = input.readUTF().takeIf { it.isNotEmpty() }

                debug("Proxy lookup {0} answered: found={1}, name={2}, server={3}", requestId, found, exactName, serverName)
                pending.remove(requestId)?.complete(
                    LookupResult(found, exactName, serverName)
                )
            }
            Protocol.SIGNED -> when (val opened = Protocol.open(secret, frame)) {
                is Message.Update -> plugin.announcements.receive(opened.entries)
                is Message.Broadcast -> {
                    val component = GCOMP.deserialize(opened.json)
                    plugin.announcements.send(opened.bypassMute) { listOf(component) }
                }
                is Message.Announce -> plugin.announcements.announce(opened.key, opened.uuid, opened.name, respectServers = true)
                is Message.Push, is Message.Pull -> debug("Ignored {0}: only the proxy takes it", opened::class.simpleName)
                null -> debug("Ignored a proxy message with a missing or wrong signature")
            }
            else -> debug("Ignored proxy message {0}", subchannel)
        }
    }

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
     * Signs [message] with the Velocity [secret] and sends it to the proxy, as several frames when it is too big for
     * one plugin message. Returns false, sending nothing, when there is no secret or no player online to carry it.
     */
    fun send(message: Message): Boolean {
        if (secret.isEmpty()) {
            debug("Proxy message {0} not sent: no Velocity secret in paper-global.yml", message::class.simpleName)
            return false
        }
        val carrier = carrier() ?: run {
            debug("Proxy message {0} not sent: no player to carry it", message::class.simpleName)
            return false
        }
        val frames = Protocol.seal(secret, message)
        debug("Sending {0} to the proxy in {1} frames", message::class.simpleName, frames.size)
        frames.forEach { carrier.sendPluginMessage(plugin, CHANNEL, it) }
        return true
    }

    companion object {
        const val CHANNEL = Protocol.CHANNEL
    }
}
