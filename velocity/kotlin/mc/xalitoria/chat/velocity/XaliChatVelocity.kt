package mc.xalitoria.chat.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import mc.xalitoria.chat.announce.AnnouncementStore
import mc.xalitoria.chat.protocol.Entry
import mc.xalitoria.chat.protocol.Message
import mc.xalitoria.chat.protocol.Protocol
import mc.xalitoria.chat.protocol.Reassembler
import mc.xalitoria.loader.velocity.LibraryLoader
import org.slf4j.Logger
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/**
 * The proxy end of XaliChat's messaging.
 *
 * It holds the announcements: `plugins/xalichat/announcements.yml` is the master, and each server keeps only the keys
 * whose `servers` name it (patterns like `skyblock-*` are matched against the names in velocity.toml). A server sends
 * what it saved; the newest save of each key wins and goes on to the other servers that key names. A server that asks
 * (on restart or reload) is told what is newer for it and what it should drop. Saving the master file does the same
 * for every server. A broadcast or announcement is passed to every server, the sender included.
 *
 * Messages are signed with the forwarding secret from velocity.toml and anything else is dropped.
 */
class XaliChatVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    // First, so the libraries (kaml and kotlinx.serialization) are on the class path before any class that needs them loads.
    init {
        LibraryLoader.load(this, dataDirectory.resolve("libraries"), logger)
    }

    private val channel = MinecraftChannelIdentifier.from(Protocol.CHANNEL)

    /** One per server: a chunked transfer's id only means something to the server that made it. */
    private val reassemblers = ConcurrentHashMap<String, Reassembler>()

    /** Updates for servers with nobody on them (a plugin message needs a player), by server name then key. */
    private val pending = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()

    private lateinit var store: AnnouncementStore

    /**
     * The forwarding secret, found the way Velocity finds it: `VELOCITY_FORWARDING_SECRET`, else the file named by
     * `forwarding-secret-file` in velocity.toml (`forwarding.secret` when unset). The API doesn't expose it. Empty,
     * so every message is dropped, when it can't be read.
     */
    private var secret = ""

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        val root = dataDirectory.toAbsolutePath().parent.parent // plugins/<id> sits under the folder holding velocity.toml
        secret = try {
            System.getenv("VELOCITY_FORWARDING_SECRET") ?: root.resolve(
                Regex("""(?m)^\s*forwarding-secret-file\s*=\s*"([^"]*)"""").find(root.resolve("velocity.toml").readText())
                    ?.groupValues?.get(1) ?: "forwarding.secret"
            ).readText()
        } catch (e: Exception) {
            logger.error("Could not read the forwarding secret, so no message will be relayed", e)
            ""
        }

        dataDirectory.createDirectories()
        val file = dataDirectory.resolve("announcements.yml")
        store = AnnouncementStore(file, dataDirectory.resolve("announcements.state.json"))
        rescan()
        watch(file)

        proxy.channelRegistrar.register(channel)
        logger.info("XaliChat relay ready on {} with {} announcements", Protocol.CHANNEL, store.announcements.size)
    }

    /** Servers that were empty when an update was sent for them get it as soon as someone joins. */
    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        val server = event.player.currentServer.orElse(null)?.server ?: return
        val queued = pending.remove(server.serverInfo.name) ?: return
        val fresh = synchronized(store) {
            queued.keys.mapNotNull { store.entry(it) }.filter { it.json != null && naming(it, server) }
        }
        if (fresh.isNotEmpty()) deliver(server, Message.Update(fresh))
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
        // Other frames on the channel (such as the servers' player lookups) belong to whichever plugin answers them.
        if (Protocol.subchannel(event.data) !in listOf(Protocol.SIGNED, Protocol.CHUNK)) return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val origin = source.server
        val frame = reassemblers.getOrPut(origin.serverInfo.name) { Reassembler() }.accept(event.data) ?: return
        when (val message = Protocol.open(secret, frame)) {
            is Message.Push -> push(origin, message.entries)
            is Message.Pull -> pull(origin, message.known)
            is Message.Broadcast, is Message.Announce -> {
                val reached = proxy.allServers.count { deliver(it, message) }
                logger.info("Relayed {} from {} to {} of {} servers", message::class.simpleName, origin.serverInfo.name, reached, proxy.allServers.size)
            }
            is Message.Update -> logger.warn("Dropped an Update from {}: only the proxy sends those", origin.serverInfo.name)
            null -> logger.warn("Dropped a message from {} with a missing or wrong signature", origin.serverInfo.name)
        }
    }

    /**
     * Keeps what [origin] saved if it is newer than the master's copy and passes it to the other servers it names. What
     * is older than the master's copy is answered with the master's. A key that origin no longer names is left in its
     * file until it reloads.
     */
    private fun push(origin: RegisteredServer, entries: List<Entry>) {
        val (stale = first, accepted = second) = synchronized(store) {
            spread(rescan(), except = null)
            val accepted = entries.filter { store.apply(it) }
            if (accepted.isNotEmpty()) store.write()
            (entries - accepted.toSet()).mapNotNull { store.entry(it.key) } to accepted
        }
        logger.info("{} pushed {} entries: {} kept, {} out of date", origin.serverInfo.name, entries.size, accepted.size, stale.size)
        if (stale.isNotEmpty()) deliver(origin, Message.Update(stale))
        spread(accepted, except = origin)
    }

    private fun pull(origin: RegisteredServer, known: Map<String, Long>) {
        val reply = synchronized(store) { spread(rescan(), except = null); store.forServer(origin.serverInfo.name, known) }
        pending.remove(origin.serverInfo.name)
        logger.info("{} pulled with {} keys: {} to send", origin.serverInfo.name, known.size, reply.size)
        if (reply.isNotEmpty()) deliver(origin, Message.Update(reply))
    }

    /** Re-reads the master file, returning what changed in it. */
    private fun rescan(): List<Entry> = try {
        synchronized(store) { store.scan() }
    } catch (e: Exception) {
        logger.error("Could not read the master announcements.yml, so it is left as it was", e)
        emptyList()
    }

    /** Sends each of [entries] that still has content to every server it names, except [except]. */
    private fun spread(entries: List<Entry>, except: RegisteredServer?) {
        val live = entries.filter { it.json != null }
        if (live.isEmpty()) return
        proxy.allServers.filter { it != except }.forEach { target ->
            val named = live.filter { naming(it, target) }
            if (named.isNotEmpty()) deliver(target, Message.Update(named))
        }
    }

    private fun naming(entry: Entry, server: RegisteredServer): Boolean =
        store.announcements[entry.key]?.appliesTo(server.serverInfo.name) == true

    /** Sends [message] to [target], or keeps the entries of an update until someone is on it. */
    private fun deliver(target: RegisteredServer, message: Message): Boolean {
        val sent = Protocol.seal(secret, message).map { target.sendPluginMessage(channel, it) }.all { it }
        if (!sent && message is Message.Update) {
            val queue = pending.getOrPut(target.serverInfo.name) { ConcurrentHashMap() }
            message.entries.forEach { queue.merge(it.key, it) { old, new -> if (new.modified >= old.modified) new else old } }
        }
        return sent
    }

    /** Applies edits made to the master file by hand, the same way a server's save is. */
    private fun watch(file: Path) {
        thread(isDaemon = true, name = "xalichat-announcements-watch") {
            FileSystems.getDefault().newWatchService().use { watcher ->
                file.parent.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
                while (true) {
                    val key = watcher.take()
                    val touched = key.pollEvents().any { it.context() == file.fileName }
                    key.reset()
                    if (!touched) continue
                    Thread.sleep(300) // editors save in several steps
                    synchronized(store) { spread(rescan(), except = null) }
                }
            }
        }
    }
}
