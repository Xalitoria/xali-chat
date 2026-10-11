package mc.xalitoria.chat.paper

import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.*
import mc.xalitoria.chat.paper.command.*
import mc.xalitoria.chat.paper.concurrent.ProxyMessenger
import mc.xalitoria.chat.paper.data.config.*
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.log.LogService
import mc.xalitoria.chat.paper.external.VanishAPI
import mc.xalitoria.chat.paper.external.protocol.*
import mc.xalitoria.chat.paper.listener.*
import mc.xalitoria.chat.paper.modal.announce.Announcements
import mc.xalitoria.chat.paper.modal.ic.*
import mc.xalitoria.util.config.YamlFile
import mc.xalitoria.util.message.*
import org.bukkit.plugin.java.JavaPlugin

class XaliChat : JavaPlugin() {
    lateinit var config: YamlFile<MainConfig>
        private set

    lateinit var messageConfig: YamlFile<MessageConfig>
        private set

    lateinit var filterConfig: YamlFile<FilterConfig>
        private set

    lateinit var messenger: ProxyMessenger
        private set

    lateinit var announcements: Announcements
        private set

    lateinit var scope: CoroutineScope
        private set

    lateinit var logs: LogService
        private set

    lateinit var interactive: Interactives
        private set

    private var discordSrv: DiscordSRVAddon? = null

    override fun onEnable() {
        INSTANCE = this
        //chatProvider = INSTANCE.server.servicesManager.getRegistration(XaliChat::class.java)!!.getProvider()
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                logger.fail("Unhandled coroutine failure", throwable)
            }
        )
        PROTO = ProtocolLibrary.getProtocolManager()

        val pm = server.pluginManager
        if (["Vault", "VaultUnlocked"].none { pm.getPlugin(it) != null }) {
            logger.fail("Vault or VaultUnlocked is required; disabling.")
            pm.disablePlugin(this)
            return
        }

        config = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        messageConfig = YamlFile(
            dataPath.resolve("language", config.data.language + ".yml"),
            MessageConfig.serializer(),
            ::MessageConfig,
        )
        filterConfig = YamlFile(dataPath.resolve("filter.yml"), FilterConfig.serializer(), ::FilterConfig)
        reload()

        try {
            DatabaseManager.connect(this, config.data.database)
        } catch (e: Exception) {
            logger.fail("Failed to connect to the database; disabling.", e)
            pm.disablePlugin(this)
            return
        }

        debug("Connected to the {0} database as server '{1}'", config.data.database.type, config.data.database.serverName)
        logs = LogService(this)

        applyPacketListeners()

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            (listOf(XaliChatCommand) + XaliChatCommand.commands).forEach { it.register(registrar) }
        }

        messenger = ProxyMessenger(this)
        server.messenger.registerOutgoingPluginChannel(this, ProxyMessenger.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, ProxyMessenger.CHANNEL, messenger)
        announcements = Announcements(this).also { it.start() }

        VanishAPI.find()
        interactive = Interactives(this)
        pm.registerEvents(interactive, this)
        if (pm.getPlugin("DiscordSRV") != null) {
            discordSrv = DiscordSRVAddon(interactive).also { it.register() }
        }
        pm.registerEvents(ChatEvent(INSTANCE), INSTANCE)
        pm.registerEvents(PlayerCacheListener(INSTANCE), INSTANCE)
        pm.registerEvents(CommandSpyEvent(INSTANCE), INSTANCE)
        pm.registerEvents(CommandLogEvent(INSTANCE), INSTANCE)
        pm.registerEvents(SignListener(INSTANCE), INSTANCE)
        debug("Enabled: interactive chat={0}, DiscordSRV addon={1}", config.data.interactiveChat, discordSrv != null)
    }

    override fun onDisable() {
        debug("Disabling: flushing logs and closing the database")
        PROTO.removePacketListeners(this)
        // Flush queued log entries while the database is still open, before cancelling the scope they run in.
        if (::logs.isInitialized) runBlocking { logs.close() }
        discordSrv?.unregister()
        scope.cancel("XaliChat is disabling")
        server.messenger.unregisterOutgoingPluginChannel(this)
        server.messenger.unregisterIncomingPluginChannel(this)
        DatabaseManager.close()
    }

    fun reload() {
        config.load()
        config.save()
        debugger =
            if (config.data.debug) { message: String, args: Array<out Any?> -> logger.info(message.formatArgs(*args)) }
            else { message: String, args: Array<out Any?> -> }
        debug("Reloaded: language={0}, interactive-chat={1}, database={2}", config.data.language, config.data.interactiveChat, config.data.database.type)
        // The database is only connected after the first load, so a reload before that is a no-op here.
        if (::logs.isInitialized) applyPacketListeners()
        messageConfig.load()
        messageConfig.save()
        filterConfig.load()
        filterConfig.data.compile()
        filterConfig.save()
        if (filterConfig.data.invalid.isNotEmpty()) logger.warning("filter.yml has ${filterConfig.data.invalid.size} invalid patterns: ${filterConfig.data.invalid}")
        // Not created yet on the first reload; starting it loads the file itself.
        if (::announcements.isInitialized) announcements.reload()
        logger.pass(messageConfig.data.commands.reload.pass)
    }

    /** Registers or unregisters the ProtocolLib listeners to match `protocol-listeners`, so a reload can flip it live. */
    private fun applyPacketListeners() {
        PROTO.removePacketListeners(this)
        if (!config.data.protocolor) {
            debug("Packet listeners disabled by config")
            return
        }
        listOf(
            BossBarListener(INSTANCE),
            ChatSessionDebugListener(INSTANCE),
            ChatComponentListener(INSTANCE),
            EntityListener(INSTANCE),
            ItemContainerListener(INSTANCE),
            ScoreboardListener(INSTANCE),
            TabListListener(INSTANCE),
        ).forEach(PROTO::addPacketListener)
        logger.info("Packet listeners registered")
    }

    companion object {
        internal lateinit var INSTANCE: XaliChat
        internal lateinit var PROTO: ProtocolManager
        private var debugger = { message: String, args: Array<out Any?> -> }
        fun debug(message: String, vararg args: Any?) = debugger(message, args)
    }
}