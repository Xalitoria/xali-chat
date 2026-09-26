package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.concurrent.KeyedMutex
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.db.IgnoreCache
import mc.xalitoria.chat.paper.data.db.repository.IgnoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mc.xalitoria.util.message.*
import mc.xalitoria.chat.paper.command.argument.KnownPlayerArgument
import mc.xalitoria.chat.paper.util.nameOrId
import mc.xalitoria.util.bukkit.runTask
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Self-toggle: the executing player ignores/un-ignores the target. Not an admin action — that's
 * `MuteCommand`'s domain.
 */
object IgnoreCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Ignore players command"
    override val mainCommand = "xignore"
    override val aliases: List<String> = ["ignore"]
    // Serializes toggles per owner, so two overlapping `/xignore` invocations for the same owner
    // (against different targets) can't race each other's check-then-act DB write + cache update.
    private val locks = KeyedMutex<UUID>()

    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .then(Commands
            .argument("target", KnownPlayerArgument(plugin.server) { msg.targetInvalid(it) })
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.hasPermission(Permissions.IGNORE)) {
                    debug("/xignore denied for {0}: missing {1}", sender.name, Permissions.IGNORE)
                    sender fail noPermission
                    return@executes Command.SINGLE_SUCCESS
                }
                if (sender !is Player) {
                    sender fail playerCommandOnly
                    return@executes Command.SINGLE_SUCCESS
                }
                val target = ctx.getArgument("target", OfflinePlayer::class.java)
                if (target.uniqueId == sender.uniqueId) {
                    sender fail msg.self
                    return@executes Command.SINGLE_SUCCESS
                }
                // Staff with the bypass permission can't be ignored; an existing entry can still be removed.
                if (target.player?.hasPermission(Permissions.BYP_MSG_IGNORE) == true &&
                    !IgnoreCache.isIgnoring(sender.uniqueId, target.uniqueId)
                ) {
                    debug("{0} tried to ignore {1}, who has {2}", sender.name, target.nameOrId, Permissions.BYP_MSG_IGNORE)
                    sender fail msg.exempt(target.nameOrId)
                    return@executes Command.SINGLE_SUCCESS
                }

                plugin.scope.launch(Dispatchers.IO) {
                    try {
                        val nowIgnoring = locks.withLock(sender.uniqueId) {
                            val toggled = IgnoreRepository.toggle(sender.uniqueId, target.uniqueId)
                            IgnoreCache.set(sender.uniqueId, target.uniqueId, toggled)
                            toggled
                        }
                        debug("{0} {1} {2}", sender.name, if (nowIgnoring) "now ignores" else "no longer ignores", target.nameOrId)
                        plugin.runTask {
                            if (nowIgnoring) sender pass msg.added(target.nameOrId)
                            else sender pass msg.removed(target.nameOrId)
                        }
                    } catch (e: Exception) {
                        plugin.logger.fail("Failed to toggle ignore for ${sender.name} -> ${target.nameOrId}", e)
                        plugin.runTask { sender fail unexpectedError }
                    }
                }
                Command.SINGLE_SUCCESS
            }
        ).executes { ctx -> ctx.source.sender fail msg.usage("xignore"); Command.SINGLE_SUCCESS }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.ignore
    private val noPermission get() = cmdMsg.noPermission
    private val playerCommandOnly get() = cmdMsg.playerCommandOnly
    private val unexpectedError get() = cmdMsg.unexpectedError
}
