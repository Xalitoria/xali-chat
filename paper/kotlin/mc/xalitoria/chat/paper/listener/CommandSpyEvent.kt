package mc.xalitoria.chat.paper.listener

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.CommandAliases
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.util.SpyState
import mc.xalitoria.chat.paper.util.resolvePlaceholders
import mc.xalitoria.util.message.info
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

/** Shows players' commands to commandspy watchers. Message commands are skipped; socialspy covers them. */
class CommandSpyEvent(private val plugin: XaliChat) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        if (SpyState.commandSpies.isEmpty()) return

        val player = event.player
        val raw = event.message.removePrefix("/")
        // `/xalichat msg ...` is a message command too, so look past the root label.
        val words = raw.lowercase().split(' ')
        val label = words[0].substringAfter(':')
        if ((if (label in CommandAliases.XALICHAT) words.getOrElse(1) { "" }.substringAfter(':') else label) in CommandAliases.PRIVATE) return

        debug("Command spy: /{0} by {1} shown to {2} watchers", raw.substringBefore(' '), player.name, SpyState.commandSpies.size)
        val msg = plugin.messageConfig.data.commands.commandSpy
        SpyState.commandSpies.forEach { id ->
            val viewer = plugin.server.getPlayer(id) ?: return@forEach
            if (viewer.uniqueId == player.uniqueId || !viewer.hasPermission(Permissions.CMD_ADMIN_SPY_COMMAND)) return@forEach
            viewer info msg.format(player.name, raw) { it.resolvePlaceholders(player, viewer) }
        }
    }
}
