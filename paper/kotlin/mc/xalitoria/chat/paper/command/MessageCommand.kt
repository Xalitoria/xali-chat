package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.db.IgnoreCache
import mc.xalitoria.chat.paper.data.log.MessageLogEntry
import mc.xalitoria.chat.paper.external.VanishAPI
import mc.xalitoria.chat.paper.util.*
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mc.xalitoria.util.bukkit.hasAnyPermission
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.message.*
import mc.xalitoria.util.serial.toUuidOrNull
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import java.util.UUID
import mc.xalitoria.chat.paper.data.constants.Permissions

object MessageCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Private message command"
    override val mainCommand = "xmessage"
    override val aliases: List<String> = ["message", "msg", "pm", "tell", "t", "whisper", "w"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .then(Commands
            .argument("target", StringArgumentType.word())
            .suggestOnlinePlayers(plugin.server)
            .then(Commands
                .argument("message", StringArgumentType.greedyString())
                .executes { ctx ->
                    send(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "target"),
                        StringArgumentType.getString(ctx, "message")
                    )
                    Command.SINGLE_SUCCESS
                }
            )
        )

    /**
     * Sends [message] from [sender] to the player [receiverName] (or the console), on this server or, through the
     * proxy, on another. Used by `/msg` and `/reply`.
     */
    fun send(sender: CommandSender, receiverName: String, message: String) {
        if (!sender.hasPermission(Permissions.MESSAGE)) {
            debug("/xmessage denied for {0}: missing xalichat.message", sender.name)
            sender fail noPermission
            return
        }

        // `@` isn't allowed in a brigadier word, so the console is reached as plain `console` or `server`,
        // after online players so a player with that name still wins.
        // A vanished player the sender can't see is as good as offline: they aren't found, here or through the proxy.
        val exact = plugin.server.findOnlinePlayer(receiverName)
        if (exact != null && !VanishAPI.canSee(sender, exact)) {
            debug("/xmessage {0} -> {1}: target is vanished", sender.name, exact.name)
            sender fail msg.targetInvalid(receiverName)
            return
        }
        val target: CommandSender? =
            exact
                ?: plugin.server.matchPlayer(receiverName).firstOrNull { VanishAPI.canSee(sender, it) }
                ?: plugin.server.consoleSender.takeIf { receiverName.lowercase() in ["console", "server"] }

        debug(
            "/xmessage {0} -> {1}: resolved to {2}",
            sender.name, receiverName, target?.let { "${it::class.simpleName} ${it.name}" } ?: "nobody local",
        )
        when (target) {
            sender -> sender fail msg.self
            is ConsoleCommandSender -> {
                if (!sender.hasAnyPermission(Permissions.MESSAGE, ["console", "server"])) {
                    sender fail noPermission
                    return
                }
                sender send msg.console.sender(sender.name, "CONSOLE", message)
                target send msg.console.target(sender.name, "CONSOLE", message)
                ReplyTracker.remember(sender, "CONSOLE")
                record(sender, null, "CONSOLE", message)
                ReplyTracker.remember(target, sender.name)
                SpyNotifier.notify(plugin, sender.name, "CONSOLE", message) { it.name == sender.name }
            }
            is Player -> {
                if (sender is Player && IgnoreCache.hidesFrom(target.uniqueId, sender)) {
                    debug("/xmessage {0} -> {1} blocked: target ignores sender", sender.name, target.name)
                    sender fail cmdMsg.ignore.blockedByTarget
                    return
                }
                if (!sender.hasPermission(Permissions.BYP_MSG_IGNORE) && !PrivateMessageGates.allows((sender as? Player)?.uniqueId, target)) {
                    debug("/xmessage {0} -> {1} declined by a message gate", sender.name, target.name)
                    sender fail msg.declined(target.name)
                    return
                }
                // Placeholders always describe the *other* party, as seen by the reader.
                sender send msg.player.sender(sender.name, target.name, message) {
                    it.resolvePlaceholders(target, sender)
                }
                target send msg.player.target(sender.name, target.name, message) {
                    it.resolvePlaceholders(sender as? Player, target)
                }
                ReplyTracker.remember(sender, target.name)
                ReplyTracker.remember(target, sender.name)
                record(sender, target.uniqueId, target.name, message)
                SpyNotifier.notify(plugin, sender.name, target.name, message, subject = sender as? Player) {
                    it.uniqueId == (sender as? Player)?.uniqueId || it.uniqueId == target.uniqueId
                }
            }
            else -> {
                // Not online here: ask the proxy, which passes it to the server they are on. Without an
                // answer it is "offline" if seen before, else unknown.
                val known = plugin.server.findKnownPlayer(receiverName)
                if (known == null && receiverName.toUuidOrNull() != null) {
                    sender fail msg.targetInvalid(receiverName)
                    return
                }
                val proxyName = known?.name ?: receiverName
                if (!plugin.config.data.crossServerMessages) {
                    debug("/xmessage {0}: {1} not online here and cross-server messages are off", sender.name, proxyName)
                    sender fail
                        if (known != null) msg.targetOffline(known.nameOrId)
                        else msg.targetInvalid(receiverName)
                    return
                }
                debug(
                    "/xmessage {0}: {1} not online here, sending through the proxy (known={2})",
                    sender.name,
                    proxyName,
                    known != null
                )

                plugin.scope.launch(Dispatchers.Default) {
                    val ack = plugin.messenger.whisper(sender, proxyName, message)

                    plugin.runTask {
                        debug("Proxy whisper to {0}: {1}", proxyName, ack?.status)
                        when (ack?.status) {
                            Message.WhisperAck.Status.DELIVERED -> {
                                val name = ack.name ?: proxyName
                                sender send msg.player.sender(sender.name, name, message)
                                ReplyTracker.remember(sender, name)
                                record(sender, null, name, message)
                                // Only spies on this server are told here; the receiver's server tells its own.
                                SpyNotifier.notify(plugin, sender.name, name, message, subject = sender as? Player) {
                                    it.uniqueId == (sender as? Player)?.uniqueId
                                }
                            }
                            Message.WhisperAck.Status.IGNORED -> sender fail cmdMsg.ignore.blockedByTarget
                            Message.WhisperAck.Status.DECLINED -> sender fail msg.declined(ack.name ?: proxyName)
                            else -> sender fail
                                if (known != null) msg.targetOffline(known.nameOrId)
                                else msg.targetInvalid(receiverName)
                        }
                    }
                }
            }
        }
    }

    /** Logs a delivered message here, on the sender's server; the receiver's server leaves it out. */
    private fun record(sender: CommandSender, receiverId: UUID?, receiver: String, message: String) {
        plugin.logs.message(MessageLogEntry(System.currentTimeMillis(), (sender as? Player)?.uniqueId, sender.name, receiverId, receiver, message))
    }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.message
    private val noPermission get() = cmdMsg.noPermission
}
