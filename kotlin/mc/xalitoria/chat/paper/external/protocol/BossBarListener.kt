package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.BOSS
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import mc.xalitoria.chat.paper.XaliChat

class BossBarListener(plugin: XaliChat) : PacketAdapter(
    plugin,
    ListenerPriority.HIGHEST,
    BOSS
) {
    override fun onPacketSending(event: PacketEvent) {
        val packet = event.packet
        val comp = packet.chatComponents ?: return
        if (comp.size() > 0) comp.protocolor()
        else packet.structures
            ?.takeIf { it.size() > 0 }
            ?.readSafely(0)
            ?.let { it.chatComponents?.protocolor() }
    }
}