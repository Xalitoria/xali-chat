package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.util.Colorizer
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.util.message.*

/**
 * `/xbroadcast <message>` sends a server-wide announcement to everyone online and the console. Players who muted
 * broadcasts skip it unless the sender has [Permissions.BYP_MUTE_BROADCASTS]. Formatting is limited by the
 * sender's `xalichat.format.broadcast.*` permissions. [GlobalBroadcastCommand] sends it to every server instead.
 */
object BroadcastCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Server-wide announcement command"
    override val mainCommand = "xbroadcast"
    override val aliases: List<String> = ["broadcast", "bcast", "bc"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = node(mainCommand, global = false)

    /** The command tree under [label]; [global] relays it through the proxy to every server, this one included. */
    fun node(label: String, global: Boolean): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(label)
        .executes { ctx -> ctx.source.sender fail msg.usage(label); Command.SINGLE_SUCCESS }
        .then(Commands
            .argument("message", StringArgumentType.greedyString())
            .executes { ctx ->
                val sender = ctx.source.sender
                val permission = if (global) Permissions.CMD_ADMIN_BROADCAST_GLOBAL else Permissions.CMD_ADMIN_BROADCAST
                if (!sender.hasPermission(permission)) {
                    debug("/{0} denied for {1}: missing {2}", label, sender.name, permission)
                    sender fail noPermission
                    return@executes Command.SINGLE_SUCCESS
                }

                val text = StringArgumentType.getString(ctx, "message")
                val announcement = Colorizer.parse(msg.format, text, sender, Permissions.FORMAT_BROADCAST)
                val bypassesMute = sender.hasPermission(Permissions.BYP_MUTE_BROADCASTS)
                debug("/{0} by {1}: bypassesMute={2}, message={3}", label, sender.name, bypassesMute, text)

                val relayed = global && plugin.messenger.send(Message.Broadcast(bypassesMute, GCOMP.serialize(announcement)))
                if (!relayed) {
                    if (global) sender warn msg.localOnly
                    plugin.announcements.send(bypassesMute) { listOf(announcement) }
                }
                Command.SINGLE_SUCCESS
            }
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.broadcast
    private val noPermission get() = cmdMsg.noPermission
}
