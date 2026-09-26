package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.TILE_ENTITY_DATA
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.wrappers.WrappedChatComponent
import com.comphenix.protocol.wrappers.nbt.NbtCompound
import com.comphenix.protocol.wrappers.nbt.NbtFactory
import mc.xalitoria.chat.paper.XaliChat

class TileEntityListener(plugin: XaliChat) : PacketAdapter(
    plugin,
    ListenerPriority.HIGHEST,
    TILE_ENTITY_DATA
) {
    override fun onPacketSending(event: PacketEvent) {
        val nbt: NbtCompound = event.packet.nbtModifier
            ?.takeIf { it.size() > 0 }
            ?.readSafely(0)
            ?.let { NbtFactory.fromBase(it) as? NbtCompound }
            ?: return

        // 1. Modern Signs (1.20+ front_text / back_text compounds)
        for (sideKey in arrayOf("front_text", "back_text")) {
            if (!nbt.containsKey(sideKey)) continue
            val side = nbt.getCompound(sideKey) ?: continue
            if (!side.containsKey("messages")) continue

            val list = side.getList<Any>("messages")
            // Newer versions store lines as compound components; only plain JSON string lines can be rewritten here.
            if (list.any { it !is String }) continue
            val rewrittenList = list.map { json ->
                json as String
                if (json.isBlank()) json else WrappedChatComponent.fromJson(json).protocolor().json
            }
            side.put(NbtFactory.ofList("messages", rewrittenList))
        }

        // 2. Legacy Signs (Text1, Text2, Text3, Text4)
        for (i in 1..4) {
            val key = "Text$i"
            if (!nbt.containsKey(key)) continue
            val rawJson = nbt.getString(key)
            if (rawJson.isNullOrBlank()) continue
            nbt.put(key, WrappedChatComponent.fromJson(rawJson).protocolor().json)
        }
    }
}