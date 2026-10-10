package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.SET_SLOT
import com.comphenix.protocol.PacketType.Play.Server.WINDOW_ITEMS
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import mc.xalitoria.chat.paper.XaliChat

class ItemContainerListener(plugin: XaliChat) : PacketAdapter(
    plugin,
    ListenerPriority.HIGHEST,
    SET_SLOT,
    WINDOW_ITEMS
) {
    override fun onPacketSending(event: PacketEvent) {
        val packet = event.packet
        when (event.packetType) {
            SET_SLOT -> {
                val mod = packet.itemModifier
                mod.readSafely(0)?.let { mod.write(0, it.protocolor()) }
            }
            WINDOW_ITEMS -> {
                val list = packet.itemListModifier
                list.readSafely(0)?.let { items -> list.write(0, items.map { it.protocolor() }) }
            }
        }
    }
}