package mc.xalitoria.chat.paper.command

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.util.message.*
import mc.xalitoria.chat.paper.data.constants.Permissions

/** The root command. Every other command is registered on its own and also runs here under its names without the `x`. */
object XaliChatCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Main command for XaliChat"
    override val mainCommand = "xalichat"
    override val aliases = ["chat"]

    val commands: List<XCommand> = [
        MessageCommand,
        ReplyCommand,
        SpamCommand,
        IgnoreCommand,
        SilenceCommand,
        CommandSpyCommand,
        SocialSpyCommand,
        ChatLogCommand,
        BroadcastCommand,
        GlobalBroadcastCommand,
        AnnounceCommand,
        GlobalAnnounceCommand,
        ViewCommand,
        NicknameCommand,
    ]

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
                if (!sender.hasPermission(Permissions.RELOAD)) {
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
        .also { root -> commands.forEach { command -> command.subcommands.forEach { root.then(command.subcommand(it)) } } }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
}
