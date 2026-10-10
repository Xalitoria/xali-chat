package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.PacketType.Play.Server.PLAYER_INFO
import com.comphenix.protocol.PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.wrappers.PlayerInfoData
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug

class TabListListener(instance: XaliChat) : PacketAdapter(
    instance,
    ListenerPriority.HIGHEST,
    PLAYER_LIST_HEADER_FOOTER,
    PLAYER_INFO // Retained for backwards compatibility with legacy versions
) {
    override fun onPacketSending(event: PacketEvent) {
        val packet = event.packet

        when (event.packetType) {
            PLAYER_LIST_HEADER_FOOTER -> {
                val comp = packet.chatComponents
                if (comp.size() >= 2) {
                    comp.protocolor(0)
                    comp.protocolor(1)
                }
            }

            PLAYER_INFO -> {
                // Read the list of PlayerInfoData entries (index 0)
                val listMod = packet.playerInfoDataLists.takeIf { it.size() > 0 } ?: return

                val entries = listMod.readSafely(0) ?: return
                // Actions such as INITIALIZE_CHAT carry the chat session; rewriting must never drop it.
                val actions = packet.playerInfoActions.readSafely(0)
                debug(
                    "PLAYER_INFO to {0}: actions={1}, entries={2}, nulls={3}, withChatSession={4}",
                    event.player.name, actions, entries.size, entries.count { it == null },
                    entries.count { it?.remoteChatSessionData != null },
                )
                // ProtocolLib yields null entries it cannot convert; rewriting the list would corrupt them.
                if (entries.any { it == null }) return

                var updated = false
                val newList = entries.map { data ->
                    val original = data.displayName ?: return@map data
                    val colorized = original.protocolor()
                    if (colorized == original) return@map data
                    updated = true
                    // Create a new PlayerInfoData copy with the colorized display name component
                    PlayerInfoData(
                        data.profileId,
                        data.latency,
                        data.isListed,
                        data.gameMode,
                        data.profile,
                        colorized,
                        data.remoteChatSessionData
                    )
                }

                if (updated) {
                    debug("PLAYER_INFO to {0}: rewrote display names, actions={1}", event.player.name, actions)
                    listMod.write(0, newList)
                }
            }
        }
    }
}