package mc.xalitoria.chat.paper.listener

import mc.xalitoria.chat.paper.util.ReplyTracker
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.db.IgnoreCache
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.repository.IgnoreRepository
import mc.xalitoria.chat.paper.data.db.repository.SettingRepository
import mc.xalitoria.chat.paper.data.db.repository.NicknameRepository
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.util.SpyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mc.xalitoria.chat.paper.command.NicknameCommand.applyNickname
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Loads the ignored players, settings, and nickname on join and drops the caches on quit. A per-player generation
 * discards loads that finish after a quit or newer join; it's never reset, so a stale load can't match.
 */
class PlayerCacheListener(private val plugin: XaliChat) : Listener {
    private val generations = ConcurrentHashMap<UUID, AtomicLong>()

    private class JoinData(val ignored: Set<UUID>, val settings: Map<Setting, String>, val nickname: String?)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val generation = generations.computeIfAbsent(uuid) { AtomicLong() }.incrementAndGet()

        plugin.scope.launch(Dispatchers.IO) {
            // One transaction: the repositories join it instead of each taking a connection.
            val data = dbTransaction {
                JoinData(IgnoreRepository.ignoredByViewer(uuid), SettingRepository.load(uuid),
                    if (plugin.config.data.nickname.enable) NicknameRepository.get(uuid) else null)
            }

            if (generations[uuid]?.get() != generation) {
                debug("Discarded stale join data for {0}", player.name)
                return@launch
            }
            debug(
                "Loaded {0}: {1} ignored, {2} settings, nickname={3}",
                player.name, data.ignored.size, data.settings.size, data.nickname ?: "none",
            )
            IgnoreCache.load(uuid, data.ignored)
            SettingCache.load(uuid, data.settings)
            data.nickname?.let { plugin.runTask { if (player.isOnline) player.applyNickname(it) } }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        generations.computeIfAbsent(uuid) { AtomicLong() }.incrementAndGet()
        debug("Evicting cached data for {0}", event.player.name)
        IgnoreCache.evict(uuid)
        SettingCache.evict(uuid)
        SpyState.commandSpies.remove(uuid)
        SpyState.socialSpies.remove(uuid)
        ReplyTracker.forget(event.player)
    }
}
