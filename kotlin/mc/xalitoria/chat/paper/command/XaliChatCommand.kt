package mc.xalitoria.chat.paper.command

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.util.message.*

object XaliChatCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Main command for XaliChat"
    override val mainCommand = "xalichat"
    override val aliases = ["xchat", "chat"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx ->
            ctx.source.sender send cmdMsg.help(mainCommand)
            Command.SINGLE_SUCCESS
        }
        .then(Commands
            .literal("reload")
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.hasPermission("xalichat.reload")) {
                    debug("/xalichat reload denied for {0}: missing xalichat.reload", sender.name)
                    sender fail cmdMsg.noPermission
                } else {
                    debug("{0} reloaded the configuration", sender.name)
                    plugin.reload()
                    sender pass cmdMsg.reload.pass
                }
                Command.SINGLE_SUCCESS
            }
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
}
