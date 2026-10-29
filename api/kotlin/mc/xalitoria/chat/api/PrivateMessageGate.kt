package mc.xalitoria.chat.api

import org.bukkit.entity.Player
import java.util.UUID

/**
 * A say in who may send a private message to whom, for plugins such as a friend list. Register one in the services
 * manager, and XaliChat asks it before it delivers a message to a player on its server, whether the sender is on that
 * server or reached it through the proxy:
 *
 * ```kotlin
 * server.servicesManager.register(PrivateMessageGate::class.java, MyGate, plugin, ServicePriority.Normal)
 * ```
 *
 * A message goes through only if every registered gate allows it. Senders with `xalichat.bypass.message.ignore` skip the gates,
 * as they skip a player's ignore list. What a gate registered goes when its plugin is disabled.
 */
fun interface PrivateMessageGate {
    /**
     * Whether [sender] may message [receiver], who is on this server. [sender] is null for the console and for anything
     * that isn't a player. It is asked on the server's main thread for every message, so it must answer from memory.
     */
    fun allows(sender: UUID?, receiver: Player): Boolean
}
