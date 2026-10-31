package mc.xalitoria.chat.paper.modal.announce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.util.Colorizer
import mc.xalitoria.chat.protocol.Entry
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.message.fail
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import kotlin.io.path.exists
import java.util.UUID
import java.util.logging.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the announcements in `announcements.yml`: the ones with a delay or repeat on their own timers, the rest when a
 * command or the proxy asks.
 *
 * With `redis` on, the announcements are shared through Redis (see [AnnouncementSync]): this server's file holds the
 * ones whose `servers` name it. Saving the file sends the keys that changed (new, edited or deleted) to Redis, which
 * keeps the newest save of each and tells everyone else, and what comes back is applied as it arrives, so nothing
 * needs a reload. A key that stops naming this server, or was deleted elsewhere, is dropped from the file when
 * Redis next tells us, which is on a restart, `/xalichat reload` and every few minutes. See [AnnouncementStore] for
 * how saves are told apart.
 */
class Announcements(private val plugin: XaliChat) {
    private val path = plugin.dataPath.resolve(FILE)
    private val store = AnnouncementStore(path, plugin.dataPath.resolve("announcements.state.json"))

    /** What is loaded: every key in the file, whether or not it still names this server. */
    val announcements: Map<String, Announcement> get() = store.announcements

    private var timers: List<Job> = []

    @Volatile
    private var sync: AnnouncementSync? = null

    /**
     * Closes the connection to Redis, if there is one, and opens it again from the settings as they are now. It syncs
     * by itself once it is up, which is the pull of the latest announcements; until then the file is what runs.
     */
    private fun connect() {
        sync?.close()
        val redis = plugin.config.data.redis
        sync = if (redis.enable) AnnouncementSync(redis, store, { plugin.config.data.database.serverName }, ::receive, SyncLog()).also { it.start() }
        else {
            plugin.logger.info("Redis is off (redis.enable in config.yml), so announcements stay in this server's $FILE")
            null
        }
    }

    fun start() {
        connect()
        if (sync != null) {
            // A first start creates its file from what Redis holds. If that doesn't come, an empty one is better than none.
            if (!path.exists()) plugin.scope.launch(Dispatchers.IO) {
                delay(FIRST_START_WAIT)
                writeIfMissing("Redis did not answer in time")
            }
        } else {
            writeIfMissing("Redis is off")
        }
        // The sync reads Redis by itself once it connects, so only the file is loaded here.
        load(force = true)

        // Editors save in several steps (temp file, rename), hence the wait before reading.
        plugin.scope.launch(Dispatchers.IO) {
            FileSystems.getDefault().newWatchService().use { watcher ->
                path.parent.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
                while (isActive) {
                    val key = runInterruptible { watcher.take() }
                    val touched = key.pollEvents().any { it.context() == path.fileName }
                    key.reset()
                    if (!touched) continue
                    delay(300.milliseconds)
                    load(force = false)
                }
            }
        }
    }

    fun close() {
        sync?.close()
        timers.forEach { it.cancel() }
    }

    /** Creates the file with nothing in it, unless there is one by now. */
    private fun writeIfMissing(reason: String) {
        synchronized(this) {
            if (path.exists()) return
            store.write()
            plugin.logger.info("Created an empty $FILE ($reason); announcements will be added to it when they are shared.")
        }
    }

    /**
     * `/xalichat reload`: reconnects to Redis with the current settings, loads the file and restarts every timer.
     * The new connection sends what was saved and asks Redis what this server should hold as soon as it is up.
     */
    fun reload(): Boolean {
        connect()
        return load(force = true)
    }

    /**
     * Reads the file and sends what changed to Redis. A save that changed nothing (comments, our own writes)
     * leaves the timers running unless [force]. A file that fails to parse is logged and the loaded announcements stay.
     */
    @Synchronized
    private fun load(force: Boolean): Boolean {
        val changes = try {
            store.scan()
        } catch (e: Exception) {
            plugin.logger.fail("$FILE is not valid YAML, so nothing is synced and the announcements already loaded are kept; the last working version is in $FILE.bak.", e)
            return false
        }
        if (force || changes.isNotEmpty()) schedule()
        debug("Loaded {0} announcements, {1} on timers, {2} changed", announcements.size, timers.size, changes.size)
        sync?.flush()
        return true
    }

    private fun schedule() {
        val serverName = plugin.config.data.database.serverName
        timers.forEach { it.cancel() }
        timers = announcements
            .filter { it.value.enable && (it.value.delay > 0 || it.value.repeat > 0) && it.value.appliesTo(serverName) }
            .map { (key = key, announcement = value) ->
                plugin.scope.launch {
                    delay(announcement.delay.seconds)
                    do {
                        announce(key, null, null, respectServers = false)
                        delay(announcement.repeat.seconds)
                    } while (announcement.repeat > 0)
                }
            }
    }

    /**
     * Applies what Redis sent. The file is read first, so a save that hasn't been picked up yet isn't written over
     * (and goes to the proxy as usual); one that doesn't parse is left alone and the update is dropped.
     */
    fun receive(entries: List<Entry>) {
        plugin.scope.launch(Dispatchers.IO) {
            synchronized(this@Announcements) {
                try {
                    store.scan()
                } catch (e: Exception) {
                    plugin.logger.fail("Dropped an update from Redis: $FILE doesn't parse.", e)
                    return@launch
                }
                // Anything saved here goes first, so that it isn't compared with what Redis sent on another clock.
                sync?.flush()
                val applied = entries.count { store.apply(it) }
                debug("Applied {0} of {1} entries from Redis", applied, entries.size)
                // The answer to a first start creates the file, even when it names nothing for this server.
                val first = !path.exists()
                if (applied > 0 || first) {
                    // Not written if the file was saved since it was read; that save is picked up next.
                    if (!store.write()) debug("{0} was saved while Redis's update was being applied; leaving the file as saved", FILE)
                    schedule()
                    if (first) plugin.logger.info("Created $FILE from Redis ($applied announcements for this server).")
                }
            }
        }
    }

    /**
     * Plays [key]: its messages to everyone who hasn't muted broadcasts and the console, then its commands in order.
     * [uuid] and [name] are the player it is about, whom `{uuid}`, `{name}` and `%player_*%` resolve to; without one
     * each viewer gets their own. [respectServers] skips it when its `servers` list leaves this server out.
     */
    fun announce(key: String, uuid: UUID?, name: String?, respectServers: Boolean) {
        val announcement = announcements[key]
            ?.takeIf { it.enable && (!respectServers || it.appliesTo(plugin.config.data.database.serverName)) }
        if (announcement == null) {
            debug("Announcement {0} skipped: unknown, disabled or not for this server", key)
            return
        }
        val subject = uuid?.let { plugin.server.getOfflinePlayer(it) }

        // Runs on the main thread: PlaceholderAPI expansions read live server state.
        fun expand(text: String, viewer: Player?): String {
            val player: OfflinePlayer? = subject ?: viewer
            val playerName = name ?: player?.name
            var result = text
            (uuid ?: player?.uniqueId)?.let { result = result.replace("{uuid}", it.toString()) }
            if (playerName != null) result = result.replace("{name}", playerName).replace("%player_name%", playerName)
            return PlaceholderAPI.setPlaceholders(player, result)
        }

        debug("Playing announcement {0} for {1}", key, name ?: uuid ?: "everyone")
        plugin.scope.launch {
            plugin.runTask { send(false) { viewer -> announcement.messages.map { Colorizer.parse(expand(it, viewer)) } } }
            for (command in announcement.commands) {
                val words = command.trim().split(Regex("\\s+"))
                if (words[0].equals("delay", ignoreCase = true)) {
                    delay(DURATION.findAll(words.drop(1).joinToString(" ")).fold(Duration.ZERO) { total, match ->
                        val amount = match.groupValues[1].toLongOrNull() ?: return@fold total
                        total + when (match.groupValues[2].lowercase()) {
                            "t" -> (amount * 50).milliseconds
                            "ms" -> amount.milliseconds
                            "m" -> amount.minutes
                            "h" -> amount.hours
                            "d" -> amount.days
                            else -> amount.seconds
                        }
                    })
                } else {
                    plugin.runTask {
                        debug("Announcement {0} runs: {1}", key, command)
                        plugin.server.dispatchCommand(plugin.server.consoleSender, expand(command, null).removePrefix("/"))
                    }
                }
            }
        }
    }

    /**
     * Sends [lines] to every online player who hasn't muted broadcasts (all of them when [bypassMute]) and to the
     * console, which is asked for its lines with a `null` viewer. Run it on the main thread.
     */
    fun send(bypassMute: Boolean, lines: (Player?) -> List<Component>) {
        val viewers = plugin.server.onlinePlayers
            .filter { bypassMute || !SettingCache.isEnabled(it.uniqueId, Setting.MUTE_BROADCASTS) }
        viewers.forEach { viewer -> lines(viewer).forEach { viewer.sendMessage(it) } }
        lines(null).forEach { plugin.server.consoleSender.sendMessage(it) }
        debug("Sent to {0}/{1} players (bypassMute={2})", viewers.size, plugin.server.onlinePlayers.size, bypassMute)
    }

    /** Sends what the sync has to say to this plugin's logger. */
    private inner class SyncLog : AnnouncementSync.Log {
        override fun info(message: String) = plugin.logger.info(message)
        override fun warn(message: String, error: Throwable?) = plugin.logger.log(Level.WARNING, message, error)
        override fun debug(message: String) = debug("{0}", message)
    }

    companion object {
        const val FILE = "announcements.yml"
        private val FIRST_START_WAIT = 10.seconds
        private val DURATION = Regex("(\\d+)(ms|[tsmhd])?", RegexOption.IGNORE_CASE)
    }
}
