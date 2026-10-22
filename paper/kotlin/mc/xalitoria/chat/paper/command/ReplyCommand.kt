package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.util.ReplyTracker
import mc.xalitoria.util.message.fail
import mc.xalitoria.util.message.send

/** Answers whoever the sender last exchanged a private message with, on this server or another. */
object ReplyCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Reply to your last private message"
    override val mainCommand = "xreply"
    override val aliases: List<String> = ["reply", "r"]

    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx ->
            val sender = ctx.source.sender
            sender send if (sender.hasPermission(Permissions.MESSAGE)) msg.replyUsage(mainCommand) else cmdMsg.noPermission
            Command.SINGLE_SUCCESS
        }
        .then(Commands
            .argument("message", StringArgumentType.greedyString())
            .executes { ctx ->
                val sender = ctx.source.sender
                val partner = ReplyTracker.last(sender)
                debug("/xreply by {0}: last partner is {1}", sender.name, partner)
                when {
                    !sender.hasPermission(Permissions.MESSAGE) -> sender fail cmdMsg.noPermission
                    partner == null -> sender fail msg.noReply
                    else -> MessageCommand.send(sender, partner, StringArgumentType.getString(ctx, "message"))
                }
                Command.SINGLE_SUCCESS
            }
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.message
}
