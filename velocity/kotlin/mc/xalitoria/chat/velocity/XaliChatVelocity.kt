package mc.xalitoria.chat.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.protocol.Protocol
import mc.xalitoria.chat.protocol.Reassembler
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText

/**
 * Relays what the XaliChat servers send each other. A saved `announcements.yml` goes to every other server; a global
 * broadcast or announcement goes to every server, the sender included. The proxy keeps nothing: it only checks the
 * signature, which is made with the forwarding secret from velocity.toml, and passes the message on.
 */
class XaliChatVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private val channel = MinecraftChannelIdentifier.from(Protocol.CHANNEL)

    /** One per server: a chunked transfer's id only means something to the server that made it. */
    private val reassemblers = ConcurrentHashMap<String, Reassembler>()

    /**
     * The forwarding secret, found the way Velocity finds it: `VELOCITY_FORWARDING_SECRET`, else the file named by
     * `forwarding-secret-file` in velocity.toml (`forwarding.secret` when unset). The API doesn't expose it. Empty,
     * so every message is dropped, when it can't be read.
     */
    private var secret = ""

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        val root = dataDirectory.toAbsolutePath().parent.parent // plugins/<id> sits under the folder holding velocity.toml
        secret = try {
            System.getenv("VELOCITY_FORWARDING_SECRET") ?: root.resolve(
                Regex("""(?m)^\s*forwarding-secret-file\s*=\s*"([^"]*)"""").find(root.resolve("velocity.toml").readText())
                    ?.groupValues?.get(1) ?: "forwarding.secret"
            ).readText()
        } catch (e: Exception) {
            logger.error("Could not read the forwarding secret, so no message will be relayed", e)
            ""
        }
        proxy.channelRegistrar.register(channel)
        logger.info("XaliChat relay ready on {}", Protocol.CHANNEL)
    }

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return

        // A client can send on any channel, and these frames would be taken as the proxy's own by the server behind
        // it, so nothing a player sends here goes anywhere. The secret would stop a forgery anyway; this saves the trip.
        val source = event.source as? ServerConnection
        if (source == null) {
            event.result = PluginMessageEvent.ForwardResult.handled()
            return
        }
        // Other frames on the channel (such as the servers' player lookups) belong to whichever plugin answers them.
        if (Protocol.subchannel(event.data) !in listOf(Protocol.SIGNED, Protocol.CHUNK)) return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val origin = source.serverInfo.name
        val frame = reassemblers.getOrPut(origin) { Reassembler() }.accept(event.data) ?: return
        val message = Protocol.open(secret, frame)
        if (message == null) {
            logger.warn("Dropped a message from {} with a missing or wrong signature", origin)
            return
        }

        val frames = Protocol.seal(secret, message)
        val targets = proxy.allServers.filter { message !is Message.ConfigSync || it.serverInfo.name != origin }
        val reached = targets.count { target -> frames.map { target.sendPluginMessage(channel, it) }.all { it } }
        logger.info("Relayed {} from {} to {} of {} servers", message::class.simpleName, origin, reached, targets.size)
    }
}
