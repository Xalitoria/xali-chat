package mc.xalitoria.chat.paper.util

import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import mc.xalitoria.chat.paper.external.VanishAPI
import mc.xalitoria.util.serial.toUuidOrNull
import mc.xalitoria.util.kotlin.stringFilter
import org.bukkit.command.CommandSender
import java.util.concurrent.CompletableFuture
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.entity.Player

/** Online player by UUID or exact username, or `null`. */
fun Server.findOnlinePlayer(input: String): Player? =
    input.toUuidOrNull()?.let { getPlayer(it) } ?: getPlayerExact(input)

/** Resolves a UUID or exact name to a player seen on this server (online or played before), never via Mojang. */
fun Server.findKnownPlayer(input: String): OfflinePlayer? {
    val uuid = input.toUuidOrNull()
    val player: OfflinePlayer? =
        if (uuid != null) getOfflinePlayer(uuid) else getPlayerExact(input) ?: getOfflinePlayerIfCached(input)
    return player?.takeIf { it.isOnline || it.hasPlayedBefore() }
}

/** Username, or the UUID for a player whose name isn't cached. */
val OfflinePlayer.nameOrId: String
    get() = name ?: uniqueId.toString()

/** Names of online players [viewer] is allowed to know about (vanished players are left out). */
fun Server.visiblePlayerNames(viewer: CommandSender): List<String> =
    onlinePlayers.filter { VanishAPI.canSee(viewer, it) }.map { it.name }

/**
 * Names to tab-complete for [viewer]: this server's visible players and everyone else on the proxy that they can see.
 * A local player the viewer can't see stays out even if the proxy doesn't know they are vanished.
 */
fun Server.completablePlayerNames(viewer: CommandSender): List<String> {
    val unseen = onlinePlayers.filterNot { VanishAPI.canSee(viewer, it) }.map { it.name }.toSet()
    return (visiblePlayerNames(viewer) + ProxyPlayers.visibleTo(viewer).filter { it !in unseen }).distinct().filter { it != viewer.name }
}

/** Adds every one of [names] matching what's typed so far (see `stringFilter`). */
fun SuggestionsBuilder.suggestMatching(names: Collection<String>): CompletableFuture<Suggestions> {
    names.stringFilter(remaining).forEach { suggest(it) }
    return buildFuture()
}

/** Tab-completes the names of players the sender can see, on this server and the proxy, plus any [extra] words. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.suggestOnlinePlayers(
    server: Server,
    extra: List<String> = [],
): RequiredArgumentBuilder<CommandSourceStack, T> =
    suggests { ctx, builder -> builder.suggestMatching(server.completablePlayerNames(ctx.source.sender) + extra) }
