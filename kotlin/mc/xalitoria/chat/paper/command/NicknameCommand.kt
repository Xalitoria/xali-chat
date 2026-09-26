package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.constants.Permissions.stripColors
import mc.xalitoria.chat.paper.data.db.repository.NicknameRepository
import mc.xalitoria.chat.paper.util.findKnownPlayer
import mc.xalitoria.chat.paper.util.nameOrId
import mc.xalitoria.chat.paper.util.suggestMatching
import mc.xalitoria.chat.paper.util.toComponent
import mc.xalitoria.chat.paper.util.visiblePlayerNames
import mc.xalitoria.util.message.*
import mc.xalitoria.util.bukkit.runTask
import org.bukkit.entity.Player

/**
 * `/nickname <nickname|reset>` sets your own; `/nickname <player|uuid> <nickname|reset>` sets another
 * player's, offline included. Formatting the sender lacks permission for is stripped; no spaces.
 */
object NicknameCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Nickname command"
    override val mainCommand = "nickname"
    override val aliases: List<String> = ["nick"]
    private const val RESET = "reset"
    private const val MAX_STORED_LENGTH = 64

    // One lock for every nickname write: the uniqueness check and the save must not interleave.
    private val lock = Mutex()

    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx -> ctx.source.sender fail msg.usage("nickname"); Command.SINGLE_SUCCESS }
        .then(Commands
            .argument("args", StringArgumentType.greedyString())
            .suggests { ctx, builder ->
                // Only the first word can be a target; after a space it's the nickname itself.
                if (' ' in builder.remaining) builder.buildFuture()
                else builder.suggestMatching(plugin.server.visiblePlayerNames(ctx.source.sender) + RESET)
            }
            .executes { ctx ->
                val sender = ctx.source.sender
                val words = StringArgumentType.getString(ctx, "args").trim().split(Regex("\\s+"), limit = 2)
                val input = words.last()
                val targetInput = words.first().takeIf { words.size > 1 }

                val target = if (targetInput == null) {
                    if (sender !is Player) {
                        sender fail playerCommandOnly
                        return@executes Command.SINGLE_SUCCESS
                    }
                    sender
                } else {
                    plugin.server.findKnownPlayer(targetInput)
                }
                if (target == null) {
                    sender fail msg.targetInvalid(targetInput.orEmpty())
                    return@executes Command.SINGLE_SUCCESS
                }

                val isSelf = sender is Player && sender.uniqueId == target.uniqueId
                if (!sender.hasPermission(if (isSelf) Permissions.NICKNAME else Permissions.NICKNAME_OTHERS)) {
                    debug("/nickname denied for {0} (self={1})", sender.name, isSelf)
                    sender fail noPermission
                    return@executes Command.SINGLE_SUCCESS
                }

                if (input.equals(RESET, ignoreCase = true)) {
                    plugin.scope.launch(Dispatchers.IO) {
                        try {
                            lock.withLock { NicknameRepository.clear(target.uniqueId) }
                            debug("{0} reset the nickname of {1}", sender.name, target.nameOrId)
                            plugin.runTask {
                                plugin.server.getPlayer(target.uniqueId)?.applyNickname(null)
                                if (isSelf) sender pass msg.reset else sender pass msg.resetOther(target.nameOrId)
                            }
                        } catch (e: Exception) {
                            plugin.logger.fail("Failed to reset nickname for ${target.nameOrId}", e)
                            plugin.runTask { sender fail unexpectedError }
                        }
                    }
                    return@executes Command.SINGLE_SUCCESS
                }

                val nickname = input.stripColors(sender, Permissions.NICKNAME)
                val plain = MINI.stripTags(nickname)
                val maxLength = plugin.config.data.nickname.maxLength.coerceIn(1, MAX_STORED_LENGTH)
                when {
                    plain.isBlank() || nickname.any(Char::isWhitespace) -> {
                        debug("Nickname '{0}' from {1} rejected: blank or contains whitespace", input, sender.name)
                        sender fail msg.invalid
                        return@executes Command.SINGLE_SUCCESS
                    }
                    plain.length > maxLength -> {
                        debug("Nickname '{0}' from {1} rejected: {2} > {3} characters", plain, sender.name, plain.length, maxLength)
                        sender fail msg.tooLong(maxLength)
                        return@executes Command.SINGLE_SUCCESS
                    }
                }

                val unique = plugin.config.data.nickname.unique
                val usedAsName = unique && plugin.server.findKnownPlayer(plain)
                    ?.let { it.uniqueId != target.uniqueId } == true
                if (usedAsName) {
                    debug("Nickname '{0}' from {1} rejected: it is another player's name", plain, sender.name)
                    sender fail msg.taken
                    return@executes Command.SINGLE_SUCCESS
                }

                plugin.scope.launch(Dispatchers.IO) {
                    try {
                        val saved = lock.withLock {
                            val taken = unique && NicknameRepository.isTaken(plain.lowercase(), target.uniqueId)
                            if (!taken) NicknameRepository.set(target.uniqueId, nickname, plain.lowercase())
                            !taken
                        }
                        plugin.runTask {
                            debug("{0} set the nickname of {1} to '{2}': {3}", sender.name, target.nameOrId, nickname, if (saved) "saved" else "taken")
                            if (!saved) return@runTask sender fail msg.taken
                            plugin.server.getPlayer(target.uniqueId)?.applyNickname(nickname)
                            if (isSelf) sender pass msg.set(nickname)
                            else sender pass msg.setOther(target.nameOrId, nickname)
                        }
                    } catch (e: Exception) {
                        plugin.logger.fail("Failed to set nickname for ${target.nameOrId}", e)
                        plugin.runTask { sender fail unexpectedError }
                    }
                }
                Command.SINGLE_SUCCESS
            }
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.nickname
    private val noPermission get() = cmdMsg.noPermission
    private val playerCommandOnly get() = cmdMsg.playerCommandOnly
    private val unexpectedError get() = cmdMsg.unexpectedError

    /** [nickname] was already stripped for whoever set it, so it is shown as stored. */
    fun Player.applyNickname(nickname: String?) = displayName(nickname?.toComponent())
}
