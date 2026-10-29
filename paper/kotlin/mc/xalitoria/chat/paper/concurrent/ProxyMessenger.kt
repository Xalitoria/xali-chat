package mc.xalitoria.chat.paper.concurrent

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.db.IgnoreCache
import mc.xalitoria.chat.paper.external.VanishAPI
import mc.xalitoria.chat.paper.util.PrivateMessageGates
import mc.xalitoria.chat.paper.util.ProxyPlayers
import mc.xalitoria.chat.paper.util.ReplyTracker
import mc.xalitoria.chat.paper.util.SpyNotifier
import mc.xalitoria.chat.paper.util.resolvePlaceholders
import mc.xalitoria.util.message.send
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.protocol.Protocol
import mc.xalitoria.chat.protocol.Reassembler
import mc.xalitoria.util.message.GCOMP
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class ProxyMessenger(private val plugin: XaliChat) : PluginMessageListener {
    private val pending = ConcurrentHashMap<UUID, CompletableDeferred<Message.WhisperAck>>()

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
            Protocol.SIGNED -> when (val opened = Protocol.open(secret, frame)) {
                is Message.Broadcast -> {
                    val component = GCOMP.deserialize(opened.json)
                    plugin.announcements.send(opened.bypassMute) { listOf(component) }
                }
                is Message.Announce -> plugin.announcements.announce(opened.key, opened.uuid, opened.name, respectServers = true)
                is Message.Players -> {
                    ProxyPlayers.update(opened)
                    debug("Proxy player list: {0} names, {1} hidden from someone here", opened.names.size, opened.hidden.size)
                }
                is Message.Whisper -> receiveWhisper(opened)
                is Message.WhisperAck -> pending.remove(opened.id)?.complete(opened)
                null -> debug("Ignored a proxy message with a missing or wrong signature")
            }
            else -> debug("Ignored proxy message {0}", subchannel)
        }
    }

    private fun carrier(): Player? =
        Bukkit.getOnlinePlayers().firstOrNull()

    /**
     * Sends a private message to a player on another server, through the proxy, and waits for how it went: delivered
     * (with the receiver's exact name), no such player online, or ignored by the receiver. Null when the proxy never
     * answered, or there was no secret or player to send it with.
     */
    suspend fun whisper(sender: CommandSender, to: String, text: String, timeoutMs: Long = 5000): Message.WhisperAck? {
        if (!plugin.config.data.crossServerMessages) return null
        val player = sender as? Player
        val whisper = Message.Whisper(
            UUID.randomUUID(), sender.name, player?.uniqueId,
            sender.hasPermission(Permissions.BYP_MSG_IGNORE), VanishAPI.seesVanished(sender), to, text,
        )
        val deferred = CompletableDeferred<Message.WhisperAck>()
        pending[whisper.id] = deferred
        if (!send(whisper)) {
            pending.remove(whisper.id)
            return null
        }
        val ack = withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
        if (ack == null) {
            debug("Whisper {0} to {1} timed out after {2}ms", whisper.id, to, timeoutMs)
            pending.remove(whisper.id)
        }
        return ack
    }

    /** A private message the proxy found one of this server's players for; says how it went, to be passed back. */
    private fun receiveWhisper(whisper: Message.Whisper) {
        val target = plugin.server.getPlayerExact(whisper.to)
        val ack = when {
            !plugin.config.data.crossServerMessages ||
                target == null || (!whisper.seesVanished && VanishAPI.isVanished(target)) -> Message.WhisperAck(whisper.id, Message.WhisperAck.Status.NOT_FOUND, null)
            !whisper.bypassIgnore && whisper.fromUuid?.let { IgnoreCache.isIgnoring(target.uniqueId, it) } == true ->
                Message.WhisperAck(whisper.id, Message.WhisperAck.Status.IGNORED, target.name)
            !whisper.bypassIgnore && !PrivateMessageGates.allows(whisper.fromUuid, target) ->
                Message.WhisperAck(whisper.id, Message.WhisperAck.Status.DECLINED, target.name)
            else -> {
                target send plugin.messageConfig.data.commands.message.player.target(whisper.from, target.name, whisper.text) {
                    it.resolvePlaceholders(null, target)
                }
                ReplyTracker.remember(target, whisper.from)
                SpyNotifier.notify(plugin, whisper.from, target.name, whisper.text) { it.uniqueId == target.uniqueId }
                Message.WhisperAck(whisper.id, Message.WhisperAck.Status.DELIVERED, target.name)
            }
        }
        debug("Whisper {0} from {1} to {2}: {3}", whisper.id, whisper.from, whisper.to, ack.status)
        send(ack)
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
