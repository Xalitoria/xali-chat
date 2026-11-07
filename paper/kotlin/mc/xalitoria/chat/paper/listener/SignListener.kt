package mc.xalitoria.chat.paper.listener

import com.xalitoria.util.message.PLAIN
import com.xalitoria.util.message.toComponent
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.constants.Permissions.stripColors
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent

class SignListener(plugin: XaliChat) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        val player = event.player
        event.lines().forEachIndexed { index, line ->
            val raw = PLAIN.serialize(line)
            if (raw.isEmpty()) return@forEachIndexed
            event.line(index, raw.stripColors(player, Permissions.FORMAT_SIGN).toComponent())
        }
    }
}
