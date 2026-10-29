package mc.xalitoria.chat.api

import org.bukkit.entity.Player

/**
 * Who is on the proxy, not only on this server. XaliChat provides it through the services manager, as the proxy last
 * told it; without a proxy, or before the first list arrives, only this server's players are known.
 */
fun interface NetworkPlayers {
    /** Whether a player called [name] is online anywhere behind the proxy and visible to [viewer] (vanished players aren't). */
    fun isOnline(name: String, viewer: Player?): Boolean
}
