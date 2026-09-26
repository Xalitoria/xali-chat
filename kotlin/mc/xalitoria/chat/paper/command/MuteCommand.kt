package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.repository.SettingRepository
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.message.*
import org.bukkit.entity.Player

/** `/xmute <players|broadcasts>` toggles the sender's own setting to hide global chat or broadcasts. */
object MuteCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Chat and broadcast mute settings"
    override val mainCommand = "xmute"
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .then(toggle("players", Setting.MUTE_PLAYERS, Permissions.SET_MUTE_PLAYERS, plugin))
        .then(toggle("broadcasts", Setting.MUTE_BROADCASTS, Permissions.SET_MUTE_BROADCASTS, plugin))
        .executes { ctx -> ctx.source.sender fail msg.usage("xmute"); Command.SINGLE_SUCCESS }

    private fun toggle(label: String, setting: Setting, permission: String, plugin: XaliChat) = Commands
        .literal(label)
        .executes { ctx ->
            val sender = ctx.source.sender
            when {
                sender !is Player -> sender fail playerCommandOnly
                !sender.hasPermission(permission) -> {
                    debug("/xmute {0} denied for {1}: missing {2}", label, sender.name, permission)
                    sender fail noPermission
                }
                else -> plugin.scope.launch(Dispatchers.IO) {
                    try {
                        val muted = !SettingCache.isEnabled(sender.uniqueId, setting)
                        SettingRepository.set(sender.uniqueId, setting, if (muted) "true" else null)
                        SettingCache.set(sender.uniqueId, setting, if (muted) "true" else null)
                        debug("{0} set {1} to {2}", sender.name, setting, muted)
                        val state = if (setting == Setting.MUTE_PLAYERS) msg.players else msg.broadcasts
                        plugin.runTask { sender pass state.get(muted) }
                    } catch (e: Exception) {
                        plugin.logger.fail("Failed to toggle $label mute for ${sender.name}", e)
                        plugin.runTask { sender fail unexpectedError }
                    }
                }
            }
            Command.SINGLE_SUCCESS
        }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.mute
    private val noPermission get() = cmdMsg.noPermission
    private val playerCommandOnly get() = cmdMsg.playerCommandOnly
    private val unexpectedError get() = cmdMsg.unexpectedError
}
