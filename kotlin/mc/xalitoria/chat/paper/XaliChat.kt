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
import mc.xalitoria.chat.paper.modal.ic.*
import mc.xalitoria.util.config.YamlFile
import mc.xalitoria.util.message.*
import org.bukkit.plugin.java.JavaPlugin

class XaliChat : JavaPlugin() {
    lateinit var config: YamlFile<MainConfig>
        private set
    lateinit var messageConfig: YamlFile<MessageConfig>
        private set
    lateinit var messenger: ProxyMessenger
        private set
    //lateinit var chatProvider: XaliChat

    lateinit var scope: CoroutineScope
        private set

    lateinit var logs: LogService
        private set

    lateinit var interactive: InteractiveChat
        private set

    private var discord: DiscordSRVAddon? = null

    override fun onEnable() {
        INSTANCE = this
        //chatProvider = INSTANCE.server.servicesManager.getRegistration(XaliChat::class.java)!!.getProvider()
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                logger.fail("Unhandled coroutine failure", throwable)
            }
        )
        PROTO = ProtocolLibrary.getProtocolManager()

        // Vault (or its drop-in replacement VaultUnlocked) supplies the chat prefix/suffix/group
        // placeholders, and PlaceholderAPI (required in paper-plugin.yml) the rest.
        if (["Vault", "VaultUnlocked"].none { server.pluginManager.getPlugin(it) != null }) {
            logger.fail("Vault or VaultUnlocked is required; disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }

        config = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        messageConfig = YamlFile(
            dataPath.resolve("language", config.data.language + ".yml"),
            MessageConfig.serializer(),
            ::MessageConfig,
        )
        reload()

        try {
            DatabaseManager.connect(this, config.data.database)
        } catch (e: Exception) {
            logger.fail("Failed to connect to the database; disabling.", e)
            server.pluginManager.disablePlugin(this)
            return
        }

        debug("Connected to the {0} database as server '{1}'", config.data.database.type, config.data.database.serverName)
        logs = LogService(this)

        [
            BossBarListener(INSTANCE),
            ChatSessionDebugListener(INSTANCE),
            ChatComponentListener(INSTANCE),
            EntityListener(INSTANCE),
            ItemContainerListener(INSTANCE),
            ScoreboardListener(INSTANCE),
            TabListListener(INSTANCE),
            TileEntityListener(INSTANCE),
        ].forEach(PROTO::addPacketListener)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            listOf(
                XaliChatCommand,
                MessageCommand,
                SpamCommand,
                IgnoreCommand,
                MuteCommand,
                CommandSpyCommand,
                SocialSpyCommand,
                ChatLogCommand,
                BroadcastCommand,
                ViewCommand,
                NicknameCommand,
            ).forEach { it.register(registrar) }
        }

        messenger = ProxyMessenger(this)
        server.messenger.registerOutgoingPluginChannel(this, ProxyMessenger.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, ProxyMessenger.CHANNEL, messenger)

        VanishAPI.find()
        interactive = InteractiveChat(this)
        server.pluginManager.registerEvents(interactive, this)
        if (server.pluginManager.getPlugin("DiscordSRV") != null) {
            discord = DiscordSRVAddon(interactive).also { it.register() }
        }
        server.pluginManager.registerEvents(ChatEvent(INSTANCE), INSTANCE)
        server.pluginManager.registerEvents(PlayerCacheListener(INSTANCE), INSTANCE)
        server.pluginManager.registerEvents(CommandSpyEvent(INSTANCE), INSTANCE)
        server.pluginManager.registerEvents(CommandLogEvent(INSTANCE), INSTANCE)
        debug("Enabled: interactive chat={0}, DiscordSRV addon={1}", config.data.interactiveChat, discord != null)
    }

    override fun onDisable() {
        debug("Disabling: flushing logs and closing the database")
        // Flush queued log entries while the database is still open, before cancelling the scope they run in.
        if (::logs.isInitialized) runBlocking { logs.close() }
        discord?.unregister()
        scope.cancel("XaliChat is disabling")
        server.messenger.unregisterOutgoingPluginChannel(this)
        server.messenger.unregisterIncomingPluginChannel(this)
        DatabaseManager.close()
    }

    fun reload() {
        config.load()
        config.save()
        debugger =
            if (config.data.debug) { message: String, args: Array<out Any?> -> logger.send(message, *args) }
            else { message: String, args: Array<out Any?> -> }
        debug("Reloaded: language={0}, interactive-chat={1}, database={2}", config.data.language, config.data.interactiveChat, config.data.database.type)
        messageConfig.load()
        messageConfig.save()
        logger.pass(messageConfig.data.commands.reload.pass)
    }

    companion object {
        internal lateinit var INSTANCE: XaliChat
        internal lateinit var PROTO: ProtocolManager
        private var debugger = { message: String, args: Array<out Any?> -> }
        fun debug(message: String, vararg args: Any?) = debugger(message, args)
    }
}