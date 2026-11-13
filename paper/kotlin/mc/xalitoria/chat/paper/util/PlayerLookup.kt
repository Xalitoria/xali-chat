package mc.xalitoria.chat.paper.util

import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.xalitoria.util.bukkit.suggestMatching
import io.papermc.paper.command.brigadier.CommandSourceStack
import mc.xalitoria.chat.paper.external.VanishAPI
import org.bukkit.Server
import org.bukkit.command.CommandSender

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

/** Tab-completes the names of players the sender can see, on this server and the proxy, plus any [extra] words. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.suggestOnlinePlayers(
    server: Server,
    extra: List<String> = [],
): RequiredArgumentBuilder<CommandSourceStack, T> =
    suggests { ctx, builder -> builder.suggestMatching(server.completablePlayerNames(ctx.source.sender) + extra) }
