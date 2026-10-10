package mc.xalitoria.chat.paper.modal.announce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.config.Announcement
import mc.xalitoria.chat.paper.data.db.SettingCache
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.util.Colorizer
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.config.YamlFile
import mc.xalitoria.util.message.fail
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.UUID
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the announcements in `announcements.yml`: the ones with a delay or repeat on their own timers, the rest when a
 * command or the proxy asks. The file is watched, so saving it reschedules everything and, once Velocity
 * forwarding is on, pushes the file to the proxy, which relays it to the other servers (see [receive]).
 */
class Announcements(private val plugin: XaliChat) {
    val file = YamlFile(
        plugin.dataPath.resolve(FILE),
        MapSerializer(String.serializer(), Announcement.serializer()),
        default = {
            mapOf(
                "example" to Announcement(
                    enable = false,
                    delay = 300,
                    repeat = 1800,
                    messages = ["&8&m------------------------------", "&dJoin our Discord: &fhttps://discord.gg/example", "&8&m------------------------------"],
                    commands = ["playsound minecraft:block.note_block.pling master @a", "delay 2s 10t"],
                )
            )
        },
    )

    private val path = plugin.dataPath.resolve(FILE)
    private var timers: List<Job> = []
    private var pushing: Job? = null

    /** The file as last loaded, sent or received; a save that leaves it unchanged is one of our own writes or a duplicate event. */
    @Volatile
    private var synced = ByteArray(0)

    fun start() {
        reload(push = false)

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
                    try {
                        if (!path.readBytes().contentEquals(synced)) reload()
                    } catch (e: Exception) {
                        debug("Could not read {0} after it changed: {1}", FILE, e)
                    }
                }
            }
        }
    }

    /**
     * Loads the file and restarts every timer. A file that fails to parse is logged and the announcements already
     * loaded stay. A changed file is also [push]ed to the proxy, waiting for a player to carry it if none is online.
     */
    @Synchronized
    fun reload(push: Boolean = true): Boolean {
        val raw = try {
            file.load()
            path.readBytes()
        } catch (e: Exception) {
            plugin.logger.fail("Failed to load $FILE; keeping the announcements already loaded.", e)
            return false
        }
        val changed = !raw.contentEquals(synced)
        synced = raw

        val serverName = plugin.config.data.database.serverName
        timers.forEach { it.cancel() }
        timers = file.data
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
        debug("Loaded {0} announcements, {1} on timers (changed={2})", file.data.size, timers.size, changed)

        if (changed && push && plugin.messenger.secret.isNotEmpty()) {
            pushing?.cancel()
            pushing = plugin.scope.launch {
                while (!plugin.messenger.send(Message.ConfigSync(FILE, raw))) delay(5.seconds)
                debug("Pushed {0} bytes of {1} to the proxy", raw.size, FILE)
            }
        }
        return true
    }

    /** Takes the file the proxy relayed: writes it over ours and loads it, without sending it back. */
    fun receive(content: ByteArray) {
        if (content.contentEquals(synced)) return
        plugin.scope.launch(Dispatchers.IO) {
            debug("Received {0} bytes of {1} from the proxy", content.size, FILE)
            synced = content
            path.writeBytes(content)
            reload(push = false)
        }
    }

    /**
     * Plays [key]: its messages to everyone who hasn't muted broadcasts and the console, then its commands in order.
     * [uuid] and [name] are the player it is about, whom `{uuid}`, `{name}` and `%player_*%` resolve to; without one
     * each viewer gets their own. [respectServers] skips it when its `servers` list leaves this server out.
     */
    fun announce(key: String, uuid: UUID?, name: String?, respectServers: Boolean) {
        val announcement = file.data[key]
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
