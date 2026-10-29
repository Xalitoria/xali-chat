package mc.xalitoria.chat.paper.util

import mc.xalitoria.chat.api.PrivateMessageGate
import mc.xalitoria.chat.paper.XaliChat
import org.bukkit.entity.Player
import java.util.UUID

/** The [PrivateMessageGate]s other plugins registered, asked before a private message is delivered. */
object PrivateMessageGates {
    /** Whether every gate lets [sender] (null for the console) message [receiver]; a gate that throws is skipped and logged. */
    fun allows(sender: UUID?, receiver: Player): Boolean =
        XaliChat.INSTANCE.server.servicesManager.getRegistrations(PrivateMessageGate::class.java).all { registration ->
            try {
                registration.provider.allows(sender, receiver)
            } catch (e: Exception) {
                XaliChat.INSTANCE.logger.warning("Message gate of ${registration.plugin.name} failed, so it was skipped: $e")
                true
            }
        }
}
