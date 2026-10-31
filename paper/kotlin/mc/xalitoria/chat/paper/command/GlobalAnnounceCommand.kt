package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.modal.command.XCommand

/** `/xgannounce <announcement> [player]` is [AnnounceCommand] for every server on the proxy. */
object GlobalAnnounceCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Plays an announcement on every server"
    override val mainCommand = "xgannounce"
    override val aliases: List<String> = ["gannounce", "gann"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = AnnounceCommand.node(mainCommand, global = true)
}
