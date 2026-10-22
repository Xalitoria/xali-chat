package mc.xalitoria.chat.paper.util

import mc.xalitoria.util.bukkit.SERVER_UUID
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who each player last exchanged a private message with, by name, for `/reply`. A name rather than a player, since
 * the other end may be on another server. The console is [SERVER_UUID] and "CONSOLE".
 */
object ReplyTracker {
    private val last = ConcurrentHashMap<UUID, String>()

    private val CommandSender.key: UUID get() = (this as? Player)?.uniqueId ?: SERVER_UUID

    fun remember(sender: CommandSender, partner: String) {
        last[sender.key] = partner
    }

    fun remember(player: Player, partner: String) {
        last[player.uniqueId] = partner
    }

    fun last(sender: CommandSender): String? = last[sender.key]

    fun forget(player: Player) {
        last.remove(player.uniqueId)
    }
}
