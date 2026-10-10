package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.db.IgnoreCache
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
import mc.xalitoria.chat.paper.data.constants.Permissions

object MessageCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Private message command"
    override val mainCommand = "xmessage"
    override val aliases: List<String> = ["message", "msg"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .then(Commands
            .argument("target", StringArgumentType.word())
            .suggestOnlinePlayers(plugin.server)
            .then(Commands
                .argument("message", StringArgumentType.greedyString())
                .executes { ctx ->
                    val sender = ctx.source.sender

                    if (!sender.hasPermission(Permissions.MESSAGE)) {
                        debug("/xmessage denied for {0}: missing xalichat.message", sender.name)
                        sender fail noPermission
                        return@executes Command.SINGLE_SUCCESS
                    }

                    val receiverName = StringArgumentType.getString(ctx, "target")
                    val message = StringArgumentType.getString(ctx, "message")

                    // `@` isn't allowed in a brigadier word, so the console is reached as plain `console` or `server`,
                    // after online players so a player with that name still wins.
                    val target: CommandSender? =
                        plugin.server.findOnlinePlayer(receiverName)
                            ?: plugin.server.matchPlayer(receiverName).firstOrNull()
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
                                return@executes Command.SINGLE_SUCCESS
                            }
                            sender send msg.console.sender(sender.name, "CONSOLE", message)
                            target send msg.console.target(sender.name, "CONSOLE", message)
                            SpyNotifier.notify(plugin, sender.name, "CONSOLE", message) { it.name == sender.name }
                        }
                        is Player -> {
                            if (sender is Player && IgnoreCache.hidesFrom(target.uniqueId, sender)) {
                                debug("/xmessage {0} -> {1} blocked: target ignores sender", sender.name, target.name)
                                sender fail cmdMsg.ignore.blockedByTarget
                                return@executes Command.SINGLE_SUCCESS
                            }
                            // Placeholders always describe the *other* party, as seen by the reader.
                            sender send msg.player.sender(sender.name, target.name, message) {
                                it.resolvePlaceholders(target, sender)
                            }
                            target send msg.player.target(sender.name, target.name, message) {
                                it.resolvePlaceholders(sender as? Player, target)
                            }
                            SpyNotifier.notify(plugin, sender.name, target.name, message, subject = sender as? Player) {
                                it.uniqueId == (sender as? Player)?.uniqueId || it.uniqueId == target.uniqueId
                            }
                        }
                        else -> {
                            // Not online here: "offline" if seen before and not on the proxy, else unknown.
                            // The proxy is asked by name, so a UUID needs a known name.
                            val known = plugin.server.findKnownPlayer(receiverName)
                            if (known == null && receiverName.toUuidOrNull() != null) {
                                sender fail msg.targetInvalid(receiverName)
                                return@executes Command.SINGLE_SUCCESS
                            }
                            val proxyName = known?.name ?: receiverName
                            debug("/xmessage {0}: {1} not online here, asking the proxy (known={2})", sender.name, proxyName, known != null)

                            plugin.scope.launch(Dispatchers.Default) {
                                val lookup = plugin.messenger.lookupPlayerOnProxy(proxyName)

                                plugin.runTask {
                                    val exactName = lookup?.takeIf { it.found }?.exactName
                                    debug("Proxy lookup for {0}: {1}", proxyName, exactName ?: "not found")
                                    if (exactName == null) {
                                        sender fail
                                            if (known != null) msg.targetOffline(known.nameOrId)
                                            else msg.targetInvalid(receiverName)
                                        return@runTask
                                    }
                                    if (!plugin.messenger.sendMsgOnProxy(sender.name, exactName, message)) {
                                        sender fail msg.targetInvalid(receiverName)
                                        return@runTask
                                    }
                                    sender send msg.player.sender(sender.name, exactName, message)
                                    // Proxied DM: only locally online spies are notified (ProxyMessenger has no
                                    // broadcast-to-spies plumbing) — documented limitation, not new breakage.
                                    SpyNotifier.notify(plugin, sender.name, exactName, message) {
                                        it.uniqueId == (sender as? Player)?.uniqueId
                                    }
                                }
                            }
                        }
                    }
                    Command.SINGLE_SUCCESS
                }
            )
        )

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.message
    private val noPermission get() = cmdMsg.noPermission
}
