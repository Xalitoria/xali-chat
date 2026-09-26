package mc.xalitoria.chat.paper.concurrent

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class ProxyMessenger(private val plugin: JavaPlugin) : PluginMessageListener {
    private val pending = ConcurrentHashMap<UUID, CompletableDeferred<LookupResult>>()

    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray
    ) {
        if (channel != CHANNEL) return

        DataInputStream(ByteArrayInputStream(message)).use { input ->
            when (input.readUTF()) {
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
                else -> {}
            }
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

    companion object {
        const val CHANNEL = "xalichat:main"
    }
}