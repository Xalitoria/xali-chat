package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.external.VanishAPI
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.util.message.fail
import mc.xalitoria.util.message.send
import mc.xalitoria.util.bukkit.SERVER_UUID
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import mc.xalitoria.chat.paper.data.constants.Permissions

object SpamCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Repeating chat command"
    override val mainCommand = "xspam"
    override val aliases: List<String> = ["spam", "alispam"]
    private val spammer: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val activeTasks = ConcurrentHashMap<UUID, BukkitTask>()

    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx ->
            val sender = ctx.source.sender
            if (!sender.hasPermission(Permissions.SPAM)) {
                debug("/xspam denied for {0}: missing xalichat.spam", sender.name)
                sender fail noPermission
                return@executes Command.SINGLE_SUCCESS
            }
            sender send msg.usage("xspam")
            Command.SINGLE_SUCCESS
        }
        .then(Commands
            .literal("stop")
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.hasPermission(Permissions.SPAM)) {
                    sender fail noPermission
                    return@executes Command.SINGLE_SUCCESS
                }
                val uuid = if (sender is Player) sender.uniqueId else SERVER_UUID
                spammer.remove(uuid)
                val cancelled = activeTasks.remove(uuid)?.also { it.cancel() } != null
                debug("{0} stopped spam (task was running: {1})", sender.name, cancelled)
                sender send msg.stop
                Command.SINGLE_SUCCESS
            }
        )
        .then(Commands
            .literal("say")
            .then(Commands
                .argument("repeat", IntegerArgumentType.integer(1))
                .then(Commands
                    .argument("message", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        if (!sender.hasPermission(Permissions.SPAM)) {
                            sender fail noPermission
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val uuid = if (sender is Player) sender.uniqueId else SERVER_UUID
                        val message = StringArgumentType.getString(ctx, "message")
                        val repeat = IntegerArgumentType.getInteger(ctx, "repeat").coerceAtLeast(1)
                        debug("{0} started spam: {1} x \"{2}\"", sender.name, repeat, message)
                        playMassSay(plugin, uuid, message, repeat)
                        Command.SINGLE_SUCCESS
                    })
                )
            )

    /** Replaces any spam task already running for [uuid] so `say` calls never stack timers. */
    fun playMassSay(plugin: XaliChat, uuid: UUID, message: String, repeat: Int): BukkitTask? {
        if (message.isEmpty() || repeat <= 0) {
            debug("Spam for {0} ignored: empty message or repeat <= 0", uuid)
            return null
        }
        var remaining = repeat
        val players = plugin.server.onlinePlayers.toMutableList()
        spammer.add(uuid)
        activeTasks.remove(uuid)?.cancel()

        val task = object : BukkitRunnable() {
            override fun run() {
                if (uuid !in spammer) {
                    cancel()
                    return
                }
                if (--remaining <= 0) {
                    activeTasks.remove(uuid)
                    spammer.remove(uuid)
                    debug("Spam for {0} finished", uuid)
                    cancel()
                    return
                }
                players.removeFirstOrNull()?.let {
                    if (it.isOnline && !VanishAPI.isVanished(it)) it.chat(message)
                    return
                }
                players.clear()
                players.addAll(plugin.server.onlinePlayers)
            }
        }.runTaskTimer(plugin, 0, 1)

        activeTasks[uuid] = task
        return task
    }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.spam
    private val noPermission get() = cmdMsg.noPermission
}
