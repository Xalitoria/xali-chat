package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.modal.command.XCommand

/** `/xmsgtoggle` turns the sender's private messages off or on, the same as `/xsilence messages`. */
object MessageToggleCommand : XCommand(XaliChat.INSTANCE) {
    override val description: String = "Turn private messages off or on"
    override val mainCommand = "xmsgtoggle"
    override val aliases: List<String> = ["msgtoggle", "pmtoggle", "togglemsg", "togglepm"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx ->
            SilenceCommand.toggle(ctx.source.sender, "messages", Setting.MUTE_MESSAGES, Permissions.SET_MUTE_MESSAGES)
            Command.SINGLE_SUCCESS
        }
}
