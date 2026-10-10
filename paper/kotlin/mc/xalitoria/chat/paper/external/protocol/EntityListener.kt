package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.ENTITY_METADATA
import com.comphenix.protocol.PacketType.Play.Server.SPAWN_ENTITY
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.wrappers.WrappedDataValue
import mc.xalitoria.chat.paper.XaliChat

class EntityListener(plugin: XaliChat) : PacketAdapter(
    plugin,
    ListenerPriority.HIGHEST,
    ENTITY_METADATA,
    SPAWN_ENTITY
) {
    override fun onPacketSending(event: PacketEvent) {
        val packet = event.packet
        when (event.packetType) {
            ENTITY_METADATA -> {
                // 1. Modern Minecraft (1.19.3+) uses List<WrappedDataValue>
                val modList = packet.dataValueCollectionModifier
                if (modList.size() > 0) {
                    val list = modList.readSafely(0) ?: return
                    val newList = list.map { value ->
                        val newValue = value.value.protocolor()
                        if (newValue != value.value) WrappedDataValue(value.index, value.serializer, newValue) else value
                    }
                    modList.write(0, newList)
                    return
                }

                // 2. Fallback WrappedDataWatcher
                val listMod = packet.watchableCollectionModifier.takeIf { it.size() > 0 } ?: return
                val list = listMod.readSafely(0) ?: return
                for (item in list) {
                    item ?: continue
                    val new = item.value.protocolor()
                    if (new != item.value) item.value = new
                }
                listMod.write(0, list)
            }
            SPAWN_ENTITY -> {
                val mod = packet.dataWatcherModifier
                if (mod.size() > 0) {
                    mod.readSafely(0)?.let { mod.write(0, it.protocolor()) }
                }
            }
        }
    }
}