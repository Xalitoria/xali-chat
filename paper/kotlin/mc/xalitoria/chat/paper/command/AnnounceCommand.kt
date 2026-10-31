package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.command.argument.KnownPlayerArgument
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.util.nameOrId
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.paper.util.suggestMatching
import mc.xalitoria.util.message.*
import org.bukkit.OfflinePlayer

/**
 * `/xannounce <announcement> [player]` plays an announcement from `announcements.yml` on this server. With a player it
 * is about them (their name and UUID fill `{name}` and `{uuid}`); without, it is about nobody and each viewer of a
 * message gets their own. [GlobalAnnounceCommand] plays it on every server its `servers` list names instead.
 */
object AnnounceCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Plays an announcement from announcements.yml"
    override val mainCommand = "xannounce"
    override val aliases: List<String> = ["announce", "ann"]
    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = node(mainCommand, global = false)

    /** The command tree under [label]; [global] relays it through the proxy to every server, this one included. */
    fun node(label: String, global: Boolean): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(label)
        .executes { ctx -> ctx.source.sender fail msg.usage(label); Command.SINGLE_SUCCESS }
        .then(Commands
            .argument("announcement", StringArgumentType.word())
            .suggests { _, builder -> builder.suggestMatching(plugin.announcements.announcements.keys) }
            .executes { ctx -> play(ctx, label, global, null) }
            .then(Commands
                .argument("player", KnownPlayerArgument(plugin.server) { msg.targetInvalid(it) })
                .executes { ctx -> play(ctx, label, global, ctx.getArgument("player", OfflinePlayer::class.java)) }
            )
        )

    private fun play(ctx: CommandContext<CommandSourceStack>, label: String, global: Boolean, target: OfflinePlayer?): Int {
        val sender = ctx.source.sender
        val permission = if (global) Permissions.CMD_ADMIN_ANNOUNCE_GLOBAL else Permissions.CMD_ADMIN_ANNOUNCE
        if (!sender.hasPermission(permission)) {
            debug("/{0} denied for {1}: missing {2}", label, sender.name, permission)
            sender fail noPermission
            return Command.SINGLE_SUCCESS
        }

        val key = StringArgumentType.getString(ctx, "announcement")
        val announcement = plugin.announcements.announcements[key]
        if (announcement == null) {
            sender fail msg.unknown(key)
            return Command.SINGLE_SUCCESS
        }
        if (!announcement.enable) {
            sender fail msg.disabled(key)
            return Command.SINGLE_SUCCESS
        }

        debug("/{0} by {1}: {2} for {3}", label, sender.name, key, target?.nameOrId ?: "everyone")
        val relayed = global && plugin.messenger.send(Message.Announce(key, target?.uniqueId, target?.name))
        if (!relayed) {
            if (global) sender warn msg.localOnly
            plugin.announcements.announce(key, target?.uniqueId, target?.name, respectServers = false)
        }
        sender pass if (target == null) msg.playedForAll(key) else msg.played(key, target.nameOrId)
        return Command.SINGLE_SUCCESS
    }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.announce
    private val noPermission get() = cmdMsg.noPermission
}
