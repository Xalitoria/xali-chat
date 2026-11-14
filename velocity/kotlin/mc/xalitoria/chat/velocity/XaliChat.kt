package mc.xalitoria.chat.velocity

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.scheduler.ScheduledTask
import com.xalitoria.chat.announce.AnnouncementStore
import com.xalitoria.chat.announce.AnnouncementSync
import com.xalitoria.chat.announce.serverMatches
import com.xalitoria.chat.logs.LogChanges
import com.xalitoria.chat.logs.LogPings
import com.xalitoria.chat.protocol.Entry
import com.xalitoria.chat.protocol.Message
import com.xalitoria.chat.protocol.Message.*
import com.xalitoria.chat.protocol.Protocol
import com.xalitoria.chat.protocol.Reassembler
import org.slf4j.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.jvm.optionals.getOrElse

/**
 * The proxy end of XaliChat's messaging.
 *
 * It holds the master announcements: `plugins/xalichat/announcements.yml`, shared with the servers through Redis (see
 * [AnnouncementSync]) when `redis` is on in its config.yml; saving the file offers what changed to Redis, and what
 * Redis holds that is newer is written into the file. A broadcast or announcement is passed to every server, the sender
 * included. A private message goes to the server its receiver is on, and how it went comes back to the server it was
 * sent from. The names of everyone on the proxy, less the vanished players each viewer can't see (PremiumVanish), go
 * to the servers for tab completion whenever someone joins, leaves or vanishes, a moment after the last of a burst.
 *
 * Messages are signed with the forwarding secret from velocity.toml and anything else is dropped.
 *
 * `/xalichatv reload` reads the configuration and the master file again and reconnects to Redis; `/xalichatv greload`
 * does that and asks every server to reload too (see [ReloadCommand]).
 */
class XaliChat(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val dataDirectory: Path,
    /** The loader Velocity knows as the plugin, which listeners are registered with. */
    private val plugin: Any,
) : Runnable, AutoCloseable {
    private val channel = MinecraftChannelIdentifier.from(Protocol.CHANNEL)

    /** One per server: a chunked transfer's id only means something to the server that made it. */
    private val reassemblers = ConcurrentHashMap<String, Reassembler>()

    /** Whispers still waiting on the receiving server's answer, by id, with the server that sent each. */
    private val whispers = ConcurrentHashMap<UUID, RegisteredServer>()

    private lateinit var store: AnnouncementStore

    @Volatile
    private var sync: AnnouncementSync? = null

    private var config = ProxyConfig()

    /** The servers' log database, while the log page is on. */
    @Volatile
    private var logs: LogDatabase? = null

    /** What tells the log page that a server wrote to the logs; see [LogPings]. */
    private val logChanges = LogChanges()
    private var pings: LogPings? = null
    private var planHooked = false
    private var planPage: PlanPage? = null

    /** PremiumVanish, when it is on the proxy. */
    private var vanish: Vanish? = null

    /** The player list waiting out its debounce. */
    private val listTask = AtomicReference<ScheduledTask?>()

    /**
     * The forwarding secret, found the way Velocity finds it: `VELOCITY_FORWARDING_SECRET`, else the file named by
     * `forwarding-secret-file` in velocity.toml (`forwarding.secret` when unset). The API doesn't expose it. Empty,
     * so every message is dropped, when it can't be read.
     */
    private var secret: String? = null

    /** Reads the forwarding secret again, if it hasn't been read: a variable or the file velocity.toml names. */
    private fun findSecret() {
        val root = dataDirectory.toAbsolutePath().parent.parent // plugins/<id> sits under the folder holding velocity.toml
        if (secret == null) secret = runCatching { System.getenv("VELOCITY_FORWARDING_SECRET") }.getOrNull()
        if (secret == null) secret = try {
            val file = secretFileRegex
                .find(root.resolve("velocity.toml").readText())
                ?.groupValues?.getOrNull(1)
                ?: "forwarding.secret"
            root.resolve(file).readText().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.error("Could not read the forwarding secret", e)
            null
        }
        if (secret == null) logger.error("Could not read the forwarding secret, so no message will be relayed")
    }

    /** Opens the connection to Redis from the settings as they are now, after closing the one there is. */
    private fun connect() {
        sync?.close()
        if (config.redis.enable) {
            // Connecting offers everything the master holds, so its first copy reaches Redis whenever that is up.
            sync = AnnouncementSync(config.redis, store, { null }, ::applyShared, SyncLog()).also { it.start() }
        } else {
            sync = null
            logger.info("Redis is off, so announcements are not shared; each server keeps its own announcements.yml")
        }
    }

    /**
     * Opens the servers' log database and puts the log page on Plan, when Plan is on this proxy and `plan.page` is on;
     * after closing what a reload finds there. A database that can't be reached leaves the page off, with the reason in the log.
     */
    private fun connectLogs() {
        pings?.close()
        pings = null
        logs?.close()
        logs = null
        if (!config.plan.page || !proxy.pluginManager.isLoaded("plan")) return
        try {
            logs = LogDatabase.connect(config.database, dataDirectory)
        } catch (e: Exception) {
            val why = generateSequence<Throwable>(e) { it.cause }.last().message ?: e.javaClass.simpleName
            logger.warn("Plan is on this proxy, but the log database can't be reached ({}), so the log page is off: check `database` in config.yml", why)
            return
        }
        pings = LogPings(config.redis, "proxy", logChanges, SyncLog()).also { it.start() }
        if (planHooked) return
        try {
            val version = proxy.pluginManager.fromInstance(plugin).flatMap { it.description.version }.orElse("")
            val page = PlanPage(logger, { logs != null && config.plan.page }, { config.plan.messages }, logChanges, { debug("{}", it) }, version)
            planHooked = page.hook()
            planPage = page
        } catch (e: NoClassDefFoundError) {
            logger.warn("Plan is loaded but its API couldn't be used, so the log page is off", e)
        }
    }

    override fun run() {
        findSecret()

        // What a first start has to put in the plugin's folder, so there is something to edit.
        dataDirectory.createDirectories()
        config = try {
            ProxyConfig.load(dataDirectory.resolve("config.yml"))
        } catch (e: Exception) {
            logger.error("Could not read config.yml; using the defaults", e)
            ProxyConfig()
        }
        val file = dataDirectory.resolve("announcements.yml")
        store = AnnouncementStore(file, dataDirectory.resolve("announcements.state.json"))
        // Only when nothing has ever been held: with a history, a missing file reads as every key deleted.
        if (!file.exists() && !store.hasHistory()) {
            XaliChat::class.java.getResourceAsStream("/announcements.yml")?.use { Files.copy(it, file) }
        }
        rescan()
        connect()
        connectLogs()
        watch(file)

        proxy.channelRegistrar.register(channel)
        proxy.eventManager.register(plugin, this)
        proxy.commandManager.register(proxy.commandManager.metaBuilder(ReloadCommand.LABEL).plugin(plugin).build(), ReloadCommand(this))
        if (proxy.pluginManager.isLoaded("premiumvanish")) try {
            vanish = Vanish(::refreshPlayers).also { proxy.eventManager.register(plugin, it) }
            logger.info("PremiumVanish found: vanished players are left out of tab completion for those who can't see them")
        } catch (e: Throwable) {
            logger.warn("PremiumVanish is loaded but its API couldn't be used, so vanished players are not filtered", e)
        }
        logger.info("XaliChat relay ready on {} with {} announcements", Protocol.CHANNEL, store.announcements.size)
    }

    override fun close() {
        sync?.close()
        pings?.close()
        try { planPage?.unhook() } catch (e: NoClassDefFoundError) { /* Plan is gone */ }
        logs?.close()
    }

    /**
     * `/xalichatv reload`: reads `config.yml` and the master `announcements.yml` again, and reconnects to Redis with the
     * settings as they are now, which offers what was saved and pulls what is newer. A `config.yml` that doesn't parse
     * is logged and the settings stay as they were. Returns whether the configuration was read.
     */
    fun reload(): Boolean {
        val read = try {
            config = ProxyConfig.load(dataDirectory.resolve("config.yml"))
            true
        } catch (e: Exception) {
            logger.error("Could not read config.yml; keeping the settings as they were", e)
            false
        }
        if (secret == null) findSecret()
        rescan()
        connect()
        connectLogs()
        refreshPlayers()
        logger.info("Reloaded the proxy's XaliChat")
        return read
    }

    /** What [reloadAll] did: the servers that were sent the request, and those that weren't (nobody on them to carry it, or no secret). */
    class Reached(val asked: List<String>, val missed: List<String>)

    /**
     * `/xalichatv greload`: [reload]s here and asks every server to reload as well, as `/xalichat reload` does on it.
     * A plugin message needs a player on the server to travel with, so a server with nobody on is in [Reached.missed].
     */
    fun reloadAll(): Reached {
        reload()
        val asked = ArrayList<String>()
        val missed = ArrayList<String>()
        proxy.allServers.sortedBy { it.serverInfo.name }.forEach { server ->
            (if (deliver(server, Reload())) asked else missed) += server.serverInfo.name
        }
        logger.info("Asked {} of {} servers to reload{}", asked.size, asked.size + missed.size, if (missed.isEmpty()) "" else "; nobody on " + missed.joinToString(", "))
        return Reached(asked, missed)
    }

    /** Whether the relay has a secret to sign what it sends with. */
    val canSign: Boolean get() = !secret.isNullOrEmpty()

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) = refreshPlayers()

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) = refreshPlayers()

    /**
     * A command the proxy handles itself (`/server`, `/glist`, a plugin's) never reaches the server the player is on, so
     * that server is told, and logs it with its own. Commands the proxy passes on are logged there as they run.
     */
    @Subscribe(order = PostOrder.LAST)
    fun onCommand(event: CommandExecuteEvent) {
        val player = event.commandSource as? Player ?: return
        val result = event.result
        if (!result.isAllowed || result.isForwardToServer) return
        val command = (result.command.orElse(null) ?: event.command).trim()
        val label = command.substringBefore(' ')
        if (label.isEmpty() || !proxy.commandManager.hasCommand(label)) return
        val server = player.currentServer.orElse(null)?.server ?: return
        val logged = if (':' in label || !proxy.commandManager.hasCommand("velocity:$label")) command else "velocity:$command"
        deliver(server, ProxyCommand(player.uniqueId, player.username, logged))
    }

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return

        // A client can send on any channel, and these frames would be taken as the proxy's own by the server behind
        // it, so nothing a player sends here goes anywhere. The secret would stop a forgery anyway; this saves the trip.
        val source = event.source as? ServerConnection
        if (source == null) {
            event.result = PluginMessageEvent.ForwardResult.handled()
            return
        }
        // Anything else on the channel isn't ours.
        if (Protocol.subchannel(event.data) !in listOf(Protocol.SIGNED, Protocol.CHUNK)) return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val origin = source.server
        val frame = reassemblers.getOrPut(origin.serverInfo.name) { Reassembler() }.accept(event.data) ?: return
        when (val message = Protocol.open(secret, frame)) {
            is Broadcast, is Announce -> {
                val reached = proxy.allServers.count { deliver(it, message) }
                when (message) {
                    // Who it came from and how far it went, not the text.
                    is Broadcast -> logger.info("[Broadcast from {}] relayed to {} of {} servers", origin.serverInfo.name, reached, proxy.allServers.size)
                    is Announce -> logger.info("[Announcement {} from {}] for {}", message.key, origin.serverInfo.name, message.name ?: message.uuid ?: "everyone")
                }
                debug("Relayed {} from {} to {} of {} servers", message::class.simpleName, origin.serverInfo.name, reached, proxy.allServers.size)
            }
            is Whisper -> whisper(origin, message)
            is WhisperAck -> whispers.remove(message.id)?.let { deliver(it, message) }
            is Players -> logger.warn("Dropped a Players from {}: only the proxy sends those", origin.serverInfo.name)
            is ProxyCommand -> logger.warn("Dropped a ProxyCommand from {}: only the proxy sends those", origin.serverInfo.name)
            is Reload -> logger.warn("Dropped a Reload from {}: only the proxy sends those", origin.serverInfo.name)
            null -> logger.warn("Dropped a message from {} with a missing or wrong signature", origin.serverInfo.name)
        }
    }

    /**
     * Finds the player a private message is for, by exact name and then by the start of one, and passes it to the
     * server they are on. The answer comes back from that server, and is passed on to [origin]; when nobody is
     * found, the answer is given here.
     */
    private fun whisper(origin: RegisteredServer, message: Whisper) {
        val target = proxy.getPlayer(message.to).orElse(null)
            ?: proxy.allPlayers.filter { it.username.startsWith(message.to, ignoreCase = true) }.minByOrNull { it.username.length }
        val server = target?.currentServer?.orElse(null)?.server
        // PremiumVanish, when it is here, knows what the sender may see; otherwise the receiving server checks the flag.
        val hidden = target != null && vanish?.let { vanish ->
            val viewer = proxy.getPlayer(message.from).orElse(null)
            viewer != null && target.uniqueId in vanish.invisible() && !vanish.canSee(viewer, target)
        } == true
        if (target == null || server == null || hidden || !participates(origin) || !participates(server)) {
            deliver(origin, WhisperAck(message.id, WhisperAck.Status.NOT_FOUND, null))
            return
        }
        whispers[message.id] = origin
        // The id means nothing once the answer is late, and a server that never answers shouldn't pile these up.
        proxy.scheduler.buildTask(plugin, Runnable { whispers.remove(message.id) }).delay(30, TimeUnit.SECONDS).schedule()
        deliver(server, Whisper(message.id, message.from, message.fromUuid, message.bypassIgnore, message.seesVanished, target.username, message.text))
    }

    /** Takes what Redis holds that is newer than the master file and writes the file; hand-written comments are lost. */
    private fun applyShared(entries: List<Entry>) {
        synchronized(store) {
            // The file first, so that an edit not picked up yet isn't written over (it goes to Redis as usual).
            rescan()
            // Anything saved here goes first, so that it isn't compared with what Redis sent on another clock.
            sync?.flush()
            val taken = entries.filter { store.apply(it) }
            if (taken.isEmpty()) return debug("Redis sent {} announcements, none newer than the master file", entries.size)
            store.write()
            // Always, whatever debug says: this changes the master file.
            logger.info("Synced announcements.yml from Redis: {}", AnnouncementSync.summarize(taken))
        }
    }

    /** Re-reads the master file, returning what changed in it. */
    private fun rescan(): List<Entry> = try {
        synchronized(store) { store.scan() }
    } catch (e: Exception) {
        logger.error("The master announcements.yml is not valid YAML, so nothing is synced and it is left as it was; the last working version is in announcements.yml.bak", e)
        emptyList()
    }

    /** Sends [message] to [target]; false when it can't be signed or nobody is on [target] to carry it. */
    private fun deliver(target: RegisteredServer, message: Message): Boolean {
        val secret = secret ?: return false
        return Protocol.seal(secret, message).map { target.sendPluginMessage(channel, it) }.all { it }
    }

    /** Whether private messages between servers include [server]: switched on, and named by `servers` if that has any. */
    private fun participates(server: RegisteredServer): Boolean =
        config.crossServerMessages.enable && serverMatches(config.crossServerMessages.servers, server.serverInfo.name)

    private fun debug(message: String, vararg args: Any?) {
        if (config.debug) logger.info(message, *args)
    }

    /** Sends every server the player list once nothing has changed for the configured debounce. */
    private fun refreshPlayers() {
        if (!config.tabCompletion.enable) return
        val next = proxy.scheduler.buildTask(plugin, Runnable { sendPlayers() })
            .delay(config.tabCompletion.debounce, TimeUnit.MILLISECONDS).schedule()
        listTask.getAndSet(next)?.cancel()
    }

    /**
     * Each server with someone on it gets the names of everyone on the proxy, and for each vanished player the
     * players on that server who can't see them, since what PremiumVanish lets someone see is up to them.
     */
    private fun sendPlayers() {
        val everyone = proxy.allPlayers.filter { player ->
            player.currentServer.map { participates(it.server) }.getOrElse { false }
        }
        val names = everyone.map { it.username }
        val invisible = try {
            vanish?.invisible().orEmpty()
        } catch (e: Throwable) {
            logger.warn("Could not ask PremiumVanish who is vanished, so the list is sent unfiltered", e)
            emptySet()
        }
        val vanished = everyone.filter { it.uniqueId in invisible }
        val reached = proxy.allServers.count { server ->
            val viewers = server.playersConnected
            if (viewers.isEmpty() || !participates(server)) return@count false
            val hidden = vanished.associate { target ->
                target.username to viewers.filter { it != target && vanish?.canSee(it, target) == false }.map { it.username }
            }.filterValues { it.isNotEmpty() }
            deliver(server, Players(names, hidden))
        }
        debug("Sent {} players ({} vanished) to {} servers", names.size, vanished.size, reached)
    }

    /** Applies edits made to the master file by hand, the same way a server's save is. */
    private fun watch(file: Path) {
        thread(isDaemon = true, name = "xalichat-announcements-watch") {
            FileSystems.getDefault().newWatchService().use { watcher ->
                file.parent.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
                while (true) {
                    val key = watcher.take()
                    val touched = key.pollEvents().any { it.context() == file.fileName }
                    key.reset() // always, or the next change to any other file in the folder (the state file) ends the watching
                    if (!touched) continue
                    Thread.sleep(300) // editors save in several steps
                    synchronized(store) { rescan(); sync?.flush() }
                }
            }
        }
    }

    private inner class SyncLog : AnnouncementSync.Log {
        override fun info(message: String) = logger.info(message)
        override fun warn(message: String, error: Throwable?) = logger.warn(message, error)
        override fun debug(message: String) = debug("{}", message)
    }

    companion object {
        private val secretFileRegex = Regex("""(?m)^\s*forwarding-secret-file\s*=\s*"([^"]*)"""")
    }
}
