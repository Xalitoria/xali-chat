package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.util.message.*
import org.bukkit.entity.Player

/** `/xview <id>` opens an inventory snapshot from an interactive chat token; it's the click target, not typed by hand. */
object ViewCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Interactive chat snapshot viewer"
    override val mainCommand = "xview"
    override val aliases: List<String> = ["view"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .then(Commands
            .argument("id", StringArgumentType.word())
            .executes { ctx ->
                val sender = ctx.source.sender
                when {
                    sender !is Player -> sender fail cmdMsg.playerCommandOnly
                    else -> {
                        val id = StringArgumentType.getString(ctx, "id")
                        val opened = plugin.interactive.view(sender, id)
                        debug("{0} opened snapshot {1}: {2}", sender.name, id, if (opened) "ok" else "expired or unknown")
                        if (!opened) sender fail cmdMsg.interactive.expired
                    }
                }
                Command.SINGLE_SUCCESS
            }
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
}
