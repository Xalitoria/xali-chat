package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.*
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import mc.xalitoria.chat.paper.XaliChat

class ChatComponentListener(instance: XaliChat) : PacketAdapter(
    instance,
    ListenerPriority.HIGHEST,
    SYSTEM_CHAT,
    DISGUISED_CHAT,
    SET_TITLE_TEXT,
    SET_SUBTITLE_TEXT,
    SET_ACTION_BAR_TEXT,
    OPEN_WINDOW
) {
    override fun onPacketSending(event: PacketEvent) {
        event.packet.chatComponents?.protocolor()
    }
}