package mc.xalitoria.chat.paper.util

import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.protocol.Message
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Everyone on the proxy, as the proxy last told this server, for tab completion. Empty until the first list arrives
 * (a player joining the proxy sends it), in which case only this server's players are completed.
 */
object ProxyPlayers {
    @Volatile
    private var names: List<String> = []

    /** A vanished player's name to the names of the players on this server who can't see them. */
    @Volatile
    private var hidden: Map<String, Set<String>> = emptyMap()

    fun update(players: Message.Players) {
        names = players.names
        hidden = players.hidden.mapValues { it.value.toSet() }
    }

    /** The names [viewer] may know about, themselves left out; the console sees everyone. */
    fun visibleTo(viewer: CommandSender): List<String> =
        if (!XaliChat.INSTANCE.config.data.crossServerMessages) emptyList() else names.filter { name ->
        name != viewer.name && (viewer !is Player || viewer.name !in hidden[name].orEmpty())
    }
}
