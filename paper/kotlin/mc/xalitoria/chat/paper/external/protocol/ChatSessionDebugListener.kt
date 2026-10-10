package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Client.CHAT_SESSION_UPDATE
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug

/** Logs each client chat-session update (debug only), to tell a client or proxy key problem from a plugin one. Never changes packets. */
class ChatSessionDebugListener(private val instance: XaliChat) : PacketAdapter(
    instance,
    ListenerPriority.MONITOR,
    CHAT_SESSION_UPDATE,
) {
    override fun onPacketReceiving(event: PacketEvent) {
        debug(
            "CHAT_SESSION_UPDATE from {0}: cancelled={1}, online-mode={2}, enforce-secure-profile={3}",
            event.player.name, event.isCancelled, instance.server.onlineMode,
            instance.server.isEnforcingSecureProfiles,
        )
    }
}
