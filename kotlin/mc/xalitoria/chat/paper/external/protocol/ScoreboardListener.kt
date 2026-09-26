package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.*
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import mc.xalitoria.chat.paper.XaliChat

class ScoreboardListener(plugin: XaliChat) : PacketAdapter(
    plugin,
    ListenerPriority.HIGHEST,
    SCOREBOARD_DISPLAY_OBJECTIVE,
    SCOREBOARD_OBJECTIVE,
    SCOREBOARD_TEAM
) {
    override fun onPacketSending(event: PacketEvent) {
        event.packet.structures
            ?.takeIf { it.size() > 0 }
            ?.readSafely(0)
            ?.chatComponents
            ?.let { for (i in 0 until it.size()) it.protocolor(i) }
    }
}