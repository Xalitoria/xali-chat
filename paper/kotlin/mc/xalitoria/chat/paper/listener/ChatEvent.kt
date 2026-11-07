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
import mc.xalitoria.chat.paper.util.CharacterCheck
import mc.xalitoria.chat.paper.util.checkCharacters
import mc.xalitoria.chat.paper.util.withoutCharacters
import mc.xalitoria.chat.paper.util.withPlaceholders
import mc.xalitoria.chat.paper.util.withRelationalPlaceholders
import com.xalitoria.util.message.*
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatEvent(private val plugin: XaliChat) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onAsyncChat(event: AsyncChatEvent) {
        val player = event.player

        val current = PLAIN.serialize(event.message()).trim()
        plugin.logs.chat(ChatLogEntry(System.currentTimeMillis(), player.uniqueId, player.name, current))

        val rawText = limitCharacters(event, player, current) ?: return

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

    /**
     * Applies `characters` to what the player typed (`originalMessage`, so what other plugins turned it into doesn't
     * count). Returns the text to send, which is [current] unless it was stripped, or null when the message is refused.
     */
    private fun limitCharacters(event: AsyncChatEvent, player: Player, current: String): String? {
        val messages = plugin.messageConfig.data.commands.characters
        when (val check = plugin.checkCharacters(player, PLAIN.serialize(event.originalMessage()).trim(), privateMessage = false)) {
            CharacterCheck.Allowed, is CharacterCheck.Alerted -> return current
            is CharacterCheck.Strip -> {
                // On the component, so that what other plugins put in the message keeps its colors and events.
                val stripped = event.message().withoutCharacters(check.remove)
                val plain = PLAIN.serialize(stripped).trim()
                if (plain.isEmpty()) return refuse(event, player, check.characters)
                debug("Chat from {0} had characters removed: {1}", player.name, check.characters)
                player warn messages.stripped(check.characters)
                event.message(stripped)
                return plain
            }
            is CharacterCheck.Block -> return refuse(event, player, check.characters)
        }
    }

    private fun refuse(event: AsyncChatEvent, player: Player, characters: String): String? {
        debug("Chat from {0} blocked for characters: {1}", player.name, characters)
        event.isCancelled = true
        player fail plugin.messageConfig.data.commands.characters.blocked(characters)
        return null
    }
}
