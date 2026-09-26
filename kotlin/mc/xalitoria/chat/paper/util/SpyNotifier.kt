package mc.xalitoria.chat.paper.util

import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.util.bukkit.SenderPlayer
import mc.xalitoria.util.message.info
import org.bukkit.entity.Player

/** Tells socialspy watchers about a delivered DM. Proxied DMs only reach spies on this server. */
object SpyNotifier {
    fun notify(
        plugin: XaliChat,
        senderName: String,
        targetName: String,
        message: String,
        subject: SenderPlayer? = null,
        exclude: (Player) -> Boolean = { false },
    ) {
        val msg = plugin.messageConfig.data.commands.socialSpy
        SpyState.socialSpies.forEach { id ->
            val viewer = plugin.server.getPlayer(id) ?: return@forEach
            if (!viewer.hasPermission(Permissions.SPY_SOCIAL) || exclude(viewer)) return@forEach
            viewer info msg.format(senderName, targetName, message) { it.resolvePlaceholders(subject, viewer) }
        }
    }
}
