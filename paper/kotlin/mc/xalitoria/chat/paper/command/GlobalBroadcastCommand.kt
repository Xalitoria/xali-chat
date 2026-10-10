package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.modal.command.XCommand

/** `/xgbroadcast <message>` is [BroadcastCommand] for every server on the proxy. */
object GlobalBroadcastCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Network-wide announcement command"
    override val mainCommand = "xgbroadcast"
    override val aliases: List<String> = ["gbroadcast", "gbcast", "gbc"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = BroadcastCommand.node(mainCommand, global = true)
}
