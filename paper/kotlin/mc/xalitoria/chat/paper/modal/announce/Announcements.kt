package mc.xalitoria.chat.paper.modal.announce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import mc.xalitoria.chat.announce.Announcement
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.util.Colorizer
import mc.xalitoria.chat.protocol.Entry
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.message.fail
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * The proxy holds every announcement; this server's file holds the ones whose `servers` name it. Saving the file sends
 * the keys that changed (new, edited or deleted) to the proxy, which keeps the newest save of each and passes it to
 * the servers it names. What the proxy sends back is applied as it arrives, so nothing needs a reload. A key that
 * stops naming this server, or was deleted elsewhere, stays in the file until the next `/xalichat reload` or restart,
 * which asks the proxy what this server should hold and drops the rest. See [AnnouncementStore] for how saves are told apart.
 */
class Announcements(private val plugin: XaliChat) {
    private val path = plugin.dataPath.resolve(FILE)
    private val store = AnnouncementStore(path, plugin.dataPath.resolve("announcements.state.json"))

    /** What is loaded: every key in the file, whether or not it still names this server. */
    val announcements: Map<String, Announcement> get() = store.announcements

    private var timers: List<Job> = []

    /** Changes the proxy hasn't been sent yet, the newest per key, and whether it still has to be asked what to hold. */
    private val outbox = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var pulling = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        reload()

        // A plugin message needs a player's connection to ride on, so sending waits for one.
        plugin.scope.launch {
            for (ignored in wake) {
                while (outbox.isNotEmpty() || pulling) {
                    val batch = outbox.values.toList()
                    if (batch.isNotEmpty() && !plugin.messenger.send(Message.Push(batch))) { delay(5.seconds); continue }
                    batch.forEach { outbox.remove(it.key, it) }
                    if (pulling) {
                        if (!plugin.messenger.send(Message.Pull(store.known()))) { delay(5.seconds); continue }
                        pulling = false
                    }
                }
            }
        }

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
                    load(force = false, pull = false)
                }
            }
        }
    }

    /** Loads the file, restarts every timer and asks the proxy what this server should hold: `/xalichat reload`. */
    fun reload(): Boolean = load(force = true, pull = true)

    /**
     * Reads the file and queues what changed for the proxy. A save that changed nothing (comments, our own writes)
     * leaves the timers running unless [force]. A file that fails to parse is logged and the loaded announcements stay.
     */
    @Synchronized
    private fun load(force: Boolean, pull: Boolean): Boolean {
        val changes = try {
            store.scan()
        } catch (e: Exception) {
            plugin.logger.fail("$FILE is not valid YAML, so nothing is synced and the announcements already loaded are kept; the last working version is in $FILE.bak.", e)
            return false
        }
        if (force || changes.isNotEmpty()) schedule()
        debug("Loaded {0} announcements, {1} on timers, {2} changed", announcements.size, timers.size, changes.size)
        queue(changes, pull)
        return true
    }

    private fun queue(changes: List<Entry>, pull: Boolean) {
        if (plugin.messenger.secret.isEmpty()) return
        changes.forEach { outbox[it.key] = it }
        if (pull) pulling = true
        if (outbox.isNotEmpty() || pulling) wake.trySend(Unit)
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
     * Applies what the proxy sent. The file is read first, so a save that hasn't been picked up yet isn't written over
     * (and goes to the proxy as usual); one that doesn't parse is left alone and the update is dropped.
     */
    fun receive(entries: List<Entry>) {
        plugin.scope.launch(Dispatchers.IO) {
            synchronized(this@Announcements) {
                val changes = try {
                    store.scan()
                } catch (e: Exception) {
                    plugin.logger.fail("Dropped an update from the proxy: $FILE doesn't parse.", e)
                    return@launch
                }
                val applied = entries.count { store.apply(it) }
                debug("Applied {0} of {1} entries from the proxy", applied, entries.size)
                if (applied > 0) {
                    store.write()
                    schedule()
                }
                queue(changes, pull = false)
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

    companion object {
        const val FILE = "announcements.yml"
        private val DURATION = Regex("(\\d+)(ms|[tsmhd])?", RegexOption.IGNORE_CASE)
    }
}
