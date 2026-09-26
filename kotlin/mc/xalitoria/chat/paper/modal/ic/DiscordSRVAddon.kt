package mc.xalitoria.chat.paper.modal.ic

import com.discordsrv.api.DiscordSRV
import com.discordsrv.api.component.MinecraftComponent
import com.discordsrv.api.eventbus.Subscribe
import com.discordsrv.api.events.message.postprocess.discord.DiscordChatMessagePostProcessEvent
import com.discordsrv.api.events.message.preprocess.game.GameChatMessagePreProcessEvent
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.util.CHAT_MINI
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.data.constants.Permissions.stripColors as stripFormat
import mc.xalitoria.color.stripColors
import mc.xalitoria.util.message.*
import net.kyori.adventure.text.Component
import org.bukkit.permissions.Permissible
import org.bukkit.permissions.PermissibleBase

/**
 * DiscordSRV 3 bridge, loaded only when DiscordSRV is present. Minecraft chat reaches Discord as plain text
 * (formatting tags stripped, interactive tokens described); Discord messages reach Minecraft with a safe
 * subset of MiniMessage parsed, and skip players who muted chat.
 */
class DiscordSRVAddon(private val interactive: InteractiveChat) {
    fun register() {
        if (DiscordSRV.isAvailable()) DiscordSRV.get().eventBus().subscribe(this)
    }

    fun unregister() {
        DiscordSRV.optional().ifPresent { it.eventBus().unsubscribe(this) }
    }

    @Subscribe
    fun onGameChat(event: GameChatMessagePreProcessEvent) {
        val message = event.message?.asAdventure() ?: return
        val tokens = XaliChat.INSTANCE.server.getPlayer(event.player.uniqueId())
            ?.let { interactive.describe(it, PLAIN.serialize(message)) }.orEmpty()
        val result = message.mapText { text ->
            tokens.entries.fold(MINI.stripTags(text).stripColors()) { plain, entry ->
                plain.replace(entry.key.text, entry.value)
            }
        }
        debug(
            "DiscordSRV game chat from {0}: in={1} tokens={2} out={3}",
            event.player.username(), PLAIN.serialize(message), tokens, PLAIN.serialize(result),
        )
        event.message = MinecraftComponent.fromAdventure(result)
    }

    @Subscribe
    fun onDiscordChat(event: DiscordChatMessagePostProcessEvent) {
        val author = formatTarget(event.preEvent.message.author.id)
        event.message.asAdventure()?.let { message ->
            event.message = MinecraftComponent.fromAdventure(
                // The "&r" makes stripColors always run; it returns early on text without codes, which would skip
                // permission checks for typed MiniMessage tags.
                message.mapText { CHAT_MINI.deserialize("&r$it".stripFormat(author, FORMAT_PERMISSION)) }
            )
        }
        event.recipients?.removeIf { SettingCache.isEnabled(it.uniqueId(), Setting.MUTE_PLAYERS) }
    }

    /** Who a Discord message's formatting is judged as: its linked Minecraft player if online, otherwise nobody. */
    private fun formatTarget(discordUserId: Long): Permissible =
        DiscordSRV.get().profileManager().getCachedProfile(discordUserId)?.playerUUID()
            ?.let(XaliChat.INSTANCE.server::getPlayer) ?: NOBODY

    private companion object {
        const val FORMAT_PERMISSION = "xalichat.format.discord"

        /** A permissible with no permissions, for Discord users with no online linked player. */
        val NOBODY = PermissibleBase(null)

        val EVERYTHING = Regex("(?s).+").toPattern()

        /** Replaces the content of every text node with [transform]'s result. */
        fun Component.mapText(transform: (String) -> Any): Component = replaceText { config ->
            config.match(EVERYTHING).replacement { result, _ ->
                when (val mapped = transform(result.group())) {
                    is Component -> mapped
                    else -> Component.text(mapped.toString())
                }
            }
        }
    }
}
