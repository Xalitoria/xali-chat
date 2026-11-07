package mc.xalitoria.chat.paper.modal.announce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import com.xalitoria.chat.announce.Action
import com.xalitoria.chat.announce.Announcement
import com.xalitoria.chat.announce.AnnouncementStore
import com.xalitoria.chat.announce.AnnouncementSync
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.util.Colorizer
import com.xalitoria.chat.protocol.Entry
import mc.xalitoria.chat.paper.util.onGlobalThread
import mc.xalitoria.chat.paper.util.runGlobal
import mc.xalitoria.chat.paper.util.runOwned
import com.xalitoria.util.message.fail
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the announcements in `announcements.yml`: the ones with a delay or repeat on their own timers, the rest when a
 * command or the proxy asks.
 *
 * With `redis` on, the announcements are shared through Redis (see [AnnouncementSync]): this server's file holds the
 * ones whose `servers` name it. Saving the file sends the keys that changed (new, edited or deleted) to Redis, which
 * keeps the newest save of each and tells everyone else, and what comes back is written into the file as it arrives.
 * A key that stops naming this server, or was deleted elsewhere, is dropped from the file when Redis next tells us.
 * See [AnnouncementStore] for how saves are told apart.
 *
 * What runs is not touched by any of that: [announcements] and the timers are what the file held when the plugin
 * started or last reloaded (`/xalichat reload`, or the proxy's `/xalichatv greload`), including what Redis answered
 * to that start or reload. A save, and an update from another server, wait in the file for the next reload.
 */
class Announcements(private val plugin: XaliChat) {
    private val path = plugin.dataPath.resolve(FILE)
    private val store = AnnouncementStore(path, plugin.dataPath.resolve("announcements.state.json"))

    /** What runs: the announcements as of the last start or reload, which the file and Redis only change at the next one. */
    @Volatile
    private var running: Map<String, Announcement> = emptyMap()

    /** What can be played and has timers: the file as of the last start or reload, whether or not it still names this server. */
    val announcements: Map<String, Announcement> get() = running

    private var timers: List<Job> = []

    /** True from connecting, in a start or a reload, until Redis has answered: what it says then is what runs. */
    @Volatile
    private var pulling = false

    @Volatile
    private var sync: AnnouncementSync? = null

    /**
     * Closes the connection to Redis, if there is one, and opens it again from the settings as they are now. It syncs
     * by itself once it is up, which is the pull of the latest announcements; until then the file is what runs.
     */
    private fun connect() {
        sync?.close()
        val redis = plugin.config.data.redis
        pulling = true
        sync = if (redis.enable) AnnouncementSync(redis, store, { plugin.config.data.database.serverName }, ::receive, SyncLog(), synced = { pulling = false }).also { it.start() }
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
        load()

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
                    saved()
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
     * The new connection sends what was saved and asks Redis what this server should hold as soon as it is up, and
     * the answer is what runs.
     */
    fun reload(): Boolean {
        connect()
        return load()
    }

    /**
     * Reads the file, sends what changed to Redis and runs what it holds: the start and the reload. A file that fails
     * to parse is logged and the announcements already running stay.
     */
    @Synchronized
    private fun load(): Boolean {
        val changes = scanFile() ?: return false
        running = store.announcements
        schedule()
        debug("Loaded {0} announcements, {1} on timers, {2} changed", running.size, timers.size, changes.size)
        sync?.flush()
        return true
    }

    /**
     * A save of the file: sends what changed to Redis, and nothing else. What runs stays as it is until a reload; a
     * file that fails to parse is logged and left.
     */
    @Synchronized
    private fun saved() {
        val changes = scanFile() ?: return
        debug("Saved {0}: {1} changed", FILE, changes.size)
        // Only keys whose content changed are in it: comments, layout and our own writes are not.
        if (changes.isNotEmpty()) plugin.logger.info("Saved $FILE; it runs after /xalichat reload here, or /xalichatv greload on every server.")
        sync?.flush()
    }

    /** What changed in the file since it was last read; null, with the reason logged, when it doesn't parse. */
    private fun scanFile(): List<Entry>? = try {
        store.scan()
    } catch (e: Exception) {
        plugin.logger.fail("$FILE is not valid YAML, so nothing is synced and the announcements already loaded are kept; the last working version is in $FILE.bak.", e)
        null
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
     * Takes what Redis sent into the file. The file is read first, so a save that hasn't been picked up yet isn't
     * written over (and goes to the proxy as usual); one that doesn't parse is left alone and the update is dropped.
     * It runs only when it is the answer to a start or a reload; otherwise it waits in the file for the next reload.
     */
    fun receive(entries: List<Entry>) {
        val run = pulling
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
                val taken = entries.filter { store.apply(it) }
                val applied = taken.size
                debug("Applied {0} of {1} entries from Redis", applied, entries.size)
                // The answer to a first start creates the file, even when it names nothing for this server.
                val first = !path.exists()
                if (applied > 0 || first) {
                    // Not written if the file was saved since it was read; that save is picked up next.
                    if (!store.write()) debug("{0} was saved while Redis's update was being applied; leaving the file as saved", FILE)
                    if (run || first) {
                        running = store.announcements
                        schedule()
                    }
                    if (first) plugin.logger.info("Created $FILE from Redis ($applied announcements for this server).")
                    // Always, whatever debug says: this changes the file.
                    else if (run) plugin.logger.info("Synced $FILE from Redis: ${AnnouncementSync.summarize(taken)}; running ${timers.size} timed announcement${if (timers.size == 1) "" else "s"}.")
                    else plugin.logger.info("Synced $FILE from Redis: ${AnnouncementSync.summarize(taken)}; it runs after /xalichat reload here, or /xalichatv greload on every server.")
                }
            }
        }
    }

    /**
     * Plays [key]: its actions in order. [uuid] and [name] are the player it is about, whom `{uuid}`, `{name}` and
     * `%player_*%` resolve to, in conditions and commands too; without one each viewer of a message gets their own,
     * and a condition or command sees no player. [respectServers] skips it when its `servers` list leaves this server out.
     */
    fun announce(key: String, uuid: UUID?, name: String?, respectServers: Boolean) {
        val announcement = announcements[key]
            ?.takeIf { it.enable && (!respectServers || it.appliesTo(plugin.config.data.database.serverName)) }
        if (announcement == null) {
            debug("Announcement {0} skipped: unknown, disabled or not for this server", key)
            return
        }
        val subject = uuid?.let { plugin.server.getOfflinePlayer(it) }

        // Runs on the global region (the main thread on Paper), or on a viewer's own thread for their lines: PlaceholderAPI expansions read live server state.
        fun expand(text: String, viewer: Player?): String {
            val player: OfflinePlayer? = subject ?: viewer
            val playerName = name ?: player?.name
            var result = text
            (uuid ?: player?.uniqueId)?.let { result = result.replace("{uuid}", it.toString()) }
            if (playerName != null) result = result.replace("{name}", playerName).replace("%player_name%", playerName)
            return PlaceholderAPI.setPlaceholders(player, result)
        }

        // Runs a list in order. Messages and commands are handed to the server's threads and not waited for; a condition is
        // waited for, since what it decides is needed here.
        suspend fun play(actions: List<Action>) {
            for (action in actions) when (action) {
                is Action.Delay -> delay(action.duration)
                is Action.Message -> plugin.runGlobal { send(false) { viewer -> action.lines.map { Colorizer.parse(expand(it, viewer)) } } }
                is Action.Command -> plugin.runGlobal {
                    debug("Announcement {0} runs: {1}", key, action.command)
                    plugin.server.dispatchCommand(plugin.server.consoleSender, expand(action.command, null).removePrefix("/"))
                }
                is Action.Condition -> {
                    val passed = plugin.onGlobalThread { action.matches { expand(it, null) } }
                    debug("Announcement {0} condition {1} is {2}", key, action.test, passed)
                    play(if (passed) action.yes else action.no)
                }
            }
        }

        debug("Playing announcement {0} for {1}", key, name ?: uuid ?: "everyone")
        plugin.scope.launch { play(announcement.actions) }
    }

    /**
     * Sends [lines] to every online player who hasn't muted broadcasts (all of them when [bypassMute]) and, unless
     * [console] is off, to the console, which is asked for its lines with a `null` viewer. Returns how many players
     * it reached. Each player's lines are made and sent on the thread that owns them (their region on Folia), so it may
     * be called from any thread, but the console's lines are made on the caller's: run it on the global region when
     * they read server state.
     */
    fun send(bypassMute: Boolean, console: Boolean = true, lines: (Player?) -> List<Component>): Int {
        val viewers = plugin.server.onlinePlayers
            .filter { bypassMute || !SettingCache.isEnabled(it.uniqueId, Setting.MUTE_BROADCASTS) }
        viewers.forEach { viewer -> viewer.runOwned(plugin) { lines(viewer).forEach { viewer.sendMessage(it) } } }
        if (console) lines(null).forEach { plugin.server.consoleSender.sendMessage(it) }
        debug("Sent to {0}/{1} players (bypassMute={2}, console={3})", viewers.size, plugin.server.onlinePlayers.size, bypassMute, console)
        return viewers.size
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
    }
}
