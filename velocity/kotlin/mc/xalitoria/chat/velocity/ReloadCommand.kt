package mc.xalitoria.chat.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * `/xalichatv reload` reloads the proxy's XaliChat; `/xalichatv greload` also asks every server to reload (the proxy
 * can't run a server's `/xalichat reload` any other way). The console may always; a player needs `xalichatv.reload` and
 * `xalichatv.greload`.
 */
class ReloadCommand(private val relay: XaliChat) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        when (invocation.arguments().firstOrNull()?.lowercase()) {
            "reload" -> if (allowed(source, RELOAD)) {
                val ok = relay.reload()
                source.sendMessage(
                    if (ok) say("Reloaded the proxy.", NamedTextColor.GREEN)
                    else say("Reloaded the proxy, but config.yml could not be read; its settings stay as they were (see the log).", NamedTextColor.YELLOW),
                )
            }
            "greload" -> if (allowed(source, GRELOAD)) {
                if (!relay.canSign) source.sendMessage(say("No forwarding secret could be read, so no server can be asked; reloading the proxy only.", NamedTextColor.YELLOW))
                val reached = relay.reloadAll()
                source.sendMessage(say("Reloaded the proxy and asked ${reached.asked.size} of ${reached.asked.size + reached.missed.size} servers to reload.", NamedTextColor.GREEN))
                if (reached.missed.isNotEmpty()) {
                    source.sendMessage(say("Nobody is on ${reached.missed.joinToString(", ")}, so it couldn't be asked: run /xalichat reload there.", NamedTextColor.YELLOW))
                }
            }
            else -> source.sendMessage(say("Usage: /$LABEL <reload|greload>  (reload: this proxy; greload: this proxy and every server)", NamedTextColor.GRAY))
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val typed = invocation.arguments().firstOrNull().orEmpty()
        if (invocation.arguments().size > 1) return emptyList()
        return listOf("reload" to RELOAD, "greload" to GRELOAD).filter { it.second.let { node -> invocation.source().hasPermission(node) } && it.first.startsWith(typed, ignoreCase = true) }.map { it.first }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission(RELOAD) || invocation.source().hasPermission(GRELOAD)

    private fun allowed(source: CommandSource, node: String): Boolean {
        if (source.hasPermission(node)) return true
        source.sendMessage(say("You don't have permission for that ($node).", NamedTextColor.RED))
        return false
    }

    private fun say(text: String, color: NamedTextColor): Component = Component.text("XaliChat > ", NamedTextColor.GRAY).append(Component.text(text, color))

    companion object {
        const val LABEL = "xalichatv"
        const val RELOAD = "xalichatv.reload"
        const val GRELOAD = "xalichatv.greload"
    }
}
