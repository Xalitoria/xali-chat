package mc.xalitoria.chat.paper.util

import mc.xalitoria.chat.paper.external.VaultChat
import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/*
 * Placeholder expansion for message templates. Always run these on the raw template, *before*
 * user-supplied text is inserted with `formatArgs`; expanding after would let a player type
 * `%some_placeholder%` into chat or a DM and have the server resolve it for everyone reading.
 */

/**
 * Expands `{vault_prefix}`, `{vault_suffix}` and `{vault_group}` (Vault / VaultUnlocked), then
 * PlaceholderAPI player placeholders, all as [subject].
 */
fun String.withPlaceholders(subject: Player): String {
    val vault = if ("{vault_" in this) VaultChat.info(subject) else null
    return replace("{vault_prefix}", vault?.prefix.orEmpty())
        .replace("{vault_suffix}", vault?.suffix.orEmpty())
        .replace("{vault_group}", vault?.group.orEmpty())
        .let { PlaceholderAPI.setPlaceholders(subject, it) }
}

/** Expands PlaceholderAPI relational placeholders (`%rel_...%`) between [viewer] and [subject]; console has no relation. */
fun String.withRelationalPlaceholders(viewer: CommandSender?, subject: Player): String =
    if (viewer is Player) PlaceholderAPI.setRelationalPlaceholders(viewer, subject, this) else this

/**
 * Both of the above, for a message about [subject] shown to [viewer]. A `null` subject (console,
 * or a player on another server) has nothing to expand, so the text passes through untouched.
 */
fun String.resolvePlaceholders(subject: Player?, viewer: CommandSender?): String =
    if (subject == null) this else withPlaceholders(subject).withRelationalPlaceholders(viewer, subject)
