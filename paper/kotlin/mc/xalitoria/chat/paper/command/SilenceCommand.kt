package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.xalitoria.util.bukkit.runOwned
import com.xalitoria.util.message.*
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.repository.SettingRepository
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.modal.command.XCommand
import org.bukkit.entity.Player

/** `/xsilence <players|broadcasts|messages>` toggles the sender's own setting to hide global chat or broadcasts, or to turn private messages away. */
object SilenceCommand : XCommand(XaliChat.INSTANCE) {
    override val description: String = "Chat and broadcast silence settings"
    override val mainCommand = "xsilence"
    override val aliases: List<String> = ["silence"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .then(toggle("players", Setting.MUTE_PLAYERS, Permissions.SET_MUTE_PLAYERS, plugin))
        .then(toggle("broadcasts", Setting.MUTE_BROADCASTS, Permissions.SET_MUTE_BROADCASTS, plugin))
        .then(toggle("messages", Setting.MUTE_MESSAGES, Permissions.SET_MUTE_MESSAGES, plugin))
        .executes { ctx -> ctx.source.sender fail msg.usage("xsilence"); Command.SINGLE_SUCCESS }

    private fun toggle(label: String, setting: Setting, permission: String, plugin: XaliChat) = Commands
        .literal(label)
        .executes { ctx ->
            toggle(ctx.source.sender, label, setting, permission)
            Command.SINGLE_SUCCESS
        }

    /** Flips [setting] for [sender], who needs [permission]; [label] only names it in the log. Also run by [MessageToggleCommand]. */
    fun toggle(sender: org.bukkit.command.CommandSender, label: String, setting: Setting, permission: String) {
            when {
                sender !is Player -> sender fail playerCommandOnly
                !sender.hasPermission(permission) -> {
                    debug("/xsilence {0} denied for {1}: missing {2}", label, sender.name, permission)
                    sender fail noPermission
                }
                else -> XaliChat.INSTANCE.scope.launch(Dispatchers.IO) {
                    try {
                        val muted = !SettingCache.isEnabled(sender.uniqueId, setting)
                        SettingRepository.set(sender.uniqueId, setting, if (muted) "true" else null)
                        SettingCache.set(sender.uniqueId, setting, if (muted) "true" else null)
                        debug("{0} set {1} to {2}", sender.name, setting, muted)
                        val state = when (setting) {
                            Setting.MUTE_PLAYERS -> msg.players
                            Setting.MUTE_BROADCASTS -> msg.broadcasts
                            Setting.MUTE_MESSAGES -> msg.messages
                        }
                        sender.runOwned(XaliChat.INSTANCE) { sender pass state.get(muted) }
                    } catch (e: Exception) {
                        XaliChat.INSTANCE.logger.fail("Failed to toggle $label mute for ${sender.name}", e)
                        sender.runOwned(XaliChat.INSTANCE) { sender fail unexpectedError }
                    }
                }
            }
    }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.mute
    private val noPermission get() = cmdMsg.noPermission
    private val playerCommandOnly get() = cmdMsg.playerCommandOnly
    private val unexpectedError get() = cmdMsg.unexpectedError
}
