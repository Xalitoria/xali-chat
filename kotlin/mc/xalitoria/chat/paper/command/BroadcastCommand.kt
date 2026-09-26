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
import mc.xalitoria.chat.paper.data.constants.Permissions.stripColors
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.util.toComponent
import mc.xalitoria.util.message.*

/**
 * `/broadcast <message>` sends a server-wide announcement to everyone online and the console. Players who muted
 * broadcasts skip it unless the sender has [Permissions.BYP_MUTE_BROADCASTS]. Formatting is limited by the
 * sender's `xalichat.format.broadcast.*` permissions.
 */
object BroadcastCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Server-wide announcement command"
    override val mainCommand = "broadcast"
    override val aliases: List<String> = ["bc"]
    private const val PERMISSION = "xalichat.admin.broadcast"
    private const val FORMAT_PERMISSION = "xalichat.format.broadcast"

    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx -> ctx.source.sender fail msg.usage("broadcast"); Command.SINGLE_SUCCESS }
        .then(Commands
            .argument("message", StringArgumentType.greedyString())
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.hasPermission(PERMISSION)) {
                    debug("/broadcast denied for {0}: missing {1}", sender.name, PERMISSION)
                    sender fail noPermission
                    return@executes Command.SINGLE_SUCCESS
                }

                val text = StringArgumentType.getString(ctx, "message").stripColors(sender, FORMAT_PERMISSION)
                val announcement = msg.format(text).toComponent()
                val bypassesMute = sender.hasPermission(Permissions.BYP_MUTE_BROADCASTS)

                val recipients = plugin.server.onlinePlayers
                    .filter { bypassesMute || !SettingCache.isEnabled(it.uniqueId, Setting.MUTE_BROADCASTS) }
                recipients.forEach { it.sendMessage(announcement) }
                debug(
                    "/broadcast by {0}: {1}/{2} players received it (bypassesMute={3}, formatted={4})",
                    sender.name, recipients.size, plugin.server.onlinePlayers.size, bypassesMute, text,
                )
                plugin.server.consoleSender.sendMessage(announcement)
                Command.SINGLE_SUCCESS
            }
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.broadcast
    private val noPermission get() = cmdMsg.noPermission
}
