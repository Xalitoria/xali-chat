package mc.xalitoria.chat.paper.listener

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import io.papermc.paper.event.player.AsyncChatEvent
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.constants.Permissions.stripColors
import mc.xalitoria.chat.paper.data.db.IgnoreCache
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.data.log.ChatLogEntry
import mc.xalitoria.chat.paper.util.withPlaceholders
import mc.xalitoria.chat.paper.util.withRelationalPlaceholders
import mc.xalitoria.util.message.*
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatEvent(private val plugin: XaliChat) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onAsyncChat(event: AsyncChatEvent) {
        val player = event.player

        val rawText = PLAIN.serialize(event.message()).trim()
        plugin.logs.chat(ChatLogEntry(System.currentTimeMillis(), player.uniqueId, player.name, rawText))

        val filter = plugin.filterConfig.data
        val blockedBy = if (player.hasPermission(Permissions.BYP_FILTER)) null else filter.blockedBy(rawText)
        if (blockedBy != null) {
            debug("Chat from {0} blocked by filter entry {1}", player.name, blockedBy)
            event.isCancelled = true
            player fail plugin.messageConfig.data.commands.filter.blocked
            return
        }

        // Placeholders expand on the template before the message goes in, so typed text is never resolved.
        val template = plugin.config.data.chatFormat.withPlaceholders(player)
        val tokens = plugin.interactive.expand(player, rawText)
        fun render(viewer: CommandSender?) = template
            .withRelationalPlaceholders(viewer, player)
            .formatArgs(player.name, rawText.stripColors(player))
            .toComponent()
            .let { with(plugin.interactive) { it.expandTokens(tokens) } }

        // Only relational placeholders differ per viewer; otherwise one component serves everyone.
        val shared = if ("%rel_" in template) null else render(null)
        //chatLogger.info(PLAIN.serialize(shared ?: render(null)))
        event.renderer { _, _, _, viewer -> shared ?: render(viewer as? CommandSender) }

        // Senders with a bypass permission stay visible to viewers who ignore or mute chat.
        val bypassesIgnore = player.hasPermission(Permissions.BYP_MSG_IGNORE)
        val bypassesMute = player.hasPermission(Permissions.BYP_MUTE_PLAYERS)
        val before = event.viewers().size
        event.viewers().removeIf { viewer ->
            viewer is Player && (
                IgnoreCache.hidesFrom(viewer.uniqueId, player, bypassesIgnore) ||
                    (!bypassesMute && SettingCache.isEnabled(viewer.uniqueId, Setting.MUTE_PLAYERS))
                )
        }
        debug(
            "Chat from {0}: {1} tokens, per-viewer render={2}, {3}/{4} viewers hidden (bypassIgnore={5}, bypassMute={6})",
            player.name, tokens.size, shared == null, before - event.viewers().size, before, bypassesIgnore, bypassesMute,
        )
    }
}
