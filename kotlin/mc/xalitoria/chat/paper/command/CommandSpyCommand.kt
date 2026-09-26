package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.util.SpyState
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.util.message.*
import org.bukkit.entity.Player

object CommandSpyCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Command spy toggle"
    override val mainCommand = "commandspy"
    override val aliases: List<String> = []
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.hasPermission(Permissions.SPY_COMMAND)) {
                    debug("/commandspy denied for {0}: missing {1}", sender.name, Permissions.SPY_COMMAND)
                    sender fail noPermission
                    return@executes Command.SINGLE_SUCCESS
                }
                if (sender !is Player) {
                    sender fail playerCommandOnly
                    return@executes Command.SINGLE_SUCCESS
                }
                val enabled = SpyState.toggleCommandSpy(sender.uniqueId)
                debug("{0} toggled commandspy: {1}", sender.name, enabled)
                sender pass msg.toggle.get(enabled)
                Command.SINGLE_SUCCESS
            }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.commandSpy
    private val noPermission get() = cmdMsg.noPermission
    private val playerCommandOnly get() = cmdMsg.playerCommandOnly
}
