package mc.xalitoria.chat.paper.modal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import org.bukkit.command.PluginIdentifiableCommand

/**
 * A command whose [mainCommand] starts with `x`. Every alias is registered with and without the `x`, and the command
 * is also a subcommand of `/xalichat` under each of those names without it (`/xalichat bc` for `/xbroadcast`).
 */
abstract class XCommand(val plugin: XaliChat) {
    abstract val description: String
    abstract val mainCommand: String
    open val aliases: List<String> = []
    abstract fun builder(): LiteralArgumentBuilder<CommandSourceStack>

    /** The names this runs under inside `/xalichat`: the main command and the aliases, all without the `x`. */
    val subcommands: List<String> get() = (aliases + mainCommand).map { it.removePrefix("x") }.distinct()

    fun register(registrar: Commands) {
        val labels = aliases.flatMap { [it.removePrefix("x"), "x" + it.removePrefix("x")] }
        val aliasLabels = (labels - mainCommand).distinct()
        claim(listOf(mainCommand) + aliasLabels)
        val registered = registrar.register(builder().build(), description, aliasLabels)
        // Only a label that could not be taken over is left out of what comes back.
        val lost = (listOf(mainCommand) + aliasLabels).filter { it !in registered }
        if (lost.isNotEmpty()) plugin.logger.warning(
            "/${lost.joinToString(", /")} could not be registered for /$mainCommand: another command already uses ${if (lost.size == 1) "it" else "them"}. " +
                "It is still reachable as /xalichat:$mainCommand and under /xalichat."
        )
    }

    /**
     * Frees [labels] that another plugin already holds. Paper never replaces a plugin's command with another plugin's,
     * and plugins such as EssentialsX enable (and register) before this event runs, so load order cannot decide it.
     */
    private fun claim(labels: List<String>) {
        val known = plugin.server.commandMap.knownCommands
        val taken = labels.mapNotNull { label ->
            val holder = known.remove(label) ?: return@mapNotNull null
            label.takeIf { (holder as? PluginIdentifiableCommand)?.plugin.let { it != null && it != plugin } }
        }
        if (taken.isNotEmpty()) plugin.logger.info("/${taken.joinToString(", /")} now run XaliChat's /$mainCommand instead of another plugin's command.")
    }

    /** This command's tree under another literal; the subtree is shared, so it behaves exactly like the original. */
    fun subcommand(name: String): LiteralArgumentBuilder<CommandSourceStack> {
        val node = builder().build()
        return Commands.literal(name).requires(node.requirement).executes(node.command)
            .also { sub -> node.children.forEach { sub.then(it) } }
    }
}
