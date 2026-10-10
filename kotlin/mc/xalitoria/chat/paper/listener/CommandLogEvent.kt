package mc.xalitoria.chat.paper.listener

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.CommandAliases
import mc.xalitoria.chat.paper.data.log.CommandLogEntry
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

/** Records players' commands, except `logging.commands.ignored-commands`. */
class CommandLogEvent(private val plugin: XaliChat) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val command = event.message.removePrefix("/")
        val words = command.split(' ')
        // `/xalichat msg ...` is logged under the subcommand, so it follows the same ignore rules as `/msg`.
        val label = words[0].substringAfter(':').let {
            if (it.lowercase() in CommandAliases.XALICHAT) words.getOrElse(1) { "" } else it
        }
        if (label.lowercase() in CommandAliases.MESSAGE || plugin.config.data.logging.commands.isIgnored(label)) {
            debug("Command /{0} by {1} not logged: ignored label", label, event.player.name)
            return
        }

        debug("Logging command /{0} by {1}", label, event.player.name)
        plugin.logs.command(CommandLogEntry(System.currentTimeMillis(), event.player.uniqueId, event.player.name, command))
    }
}
