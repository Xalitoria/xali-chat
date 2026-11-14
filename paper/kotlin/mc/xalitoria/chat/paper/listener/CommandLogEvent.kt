package mc.xalitoria.chat.paper.listener

import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.constants.CommandAliases
import mc.xalitoria.chat.paper.data.log.CommandLogEntry
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.UUID

/**
 * Records players' commands, except `logging.commands.ignored-commands`, under the name of the plugin that owns them
 * (`/gmc` is logged as `essentials:gmc`), and the proxy's commands that [mc.xalitoria.chat.paper.concurrent.ProxyMessenger] hands over.
 */
class CommandLogEvent(private val plugin: XaliChat) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        record(event.player.uniqueId, event.player.name, event.message.removePrefix("/"), qualify = true)
    }

    /** Logs [command] (without the slash) run by a player; [qualify] puts the owner's namespace in front of the label. */
    fun record(uniqueId: UUID, name: String, command: String, qualify: Boolean) {
        val words = command.split(' ')
        // `/xalichat msg ...` is logged under the subcommand, so it follows the same ignore rules as `/msg`.
        val label = words[0].substringAfter(':').let {
            if (it.lowercase() in CommandAliases.XALICHAT) words.getOrElse(1) { "" } else it
        }
        if (label.lowercase() in CommandAliases.PRIVATE || plugin.config.data.logging.commands.isIgnored(label)) {
            debug("Command /{0} by {1} not logged: ignored label", label, name)
            return
        }

        val logged = if (qualify) namespaced(words[0]) + command.substring(words[0].length) else command
        debug("Logging command /{0} by {1}", logged, name)
        plugin.logs.command(CommandLogEntry(System.currentTimeMillis(), uniqueId, name, logged))
    }

    /** [label] as `namespace:label`: already so when typed, else the prefix the command map registered it under, else as typed. */
    private fun namespaced(label: String): String {
        if (':' in label) return label
        return try {
            val map = plugin.server.commandMap
            val command = map.getCommand(label) ?: return label
            val prefix = map.knownCommands.entries.firstOrNull { (key, value) ->
                value === command && key.length > label.length && key.endsWith(":$label", ignoreCase = true)
            }?.key
            prefix ?: label
        } catch (e: Exception) {
            debug("Could not find the namespace of /{0}: {1}", label, e)
            label
        }
    }
}
