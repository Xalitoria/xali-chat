package mc.xalitoria.chat.paper.external.plan

import com.djrapitops.plan.capability.CapabilityService
import com.djrapitops.plan.delivery.web.ResolverService
import com.djrapitops.plan.delivery.web.ResourceService
import com.djrapitops.plan.extension.ExtensionService
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.util.message.fail

/**
 * Registers [PlanExtension] and the [LogPage] with Plan when it is installed and `plan.enable` is on. Everything that names a Plan class
 * is in this package, and this object is only used after the plugin manager has said Plan is there, so a server
 * without Plan never loads them.
 */
object PlanHook {
    private var extension: PlanExtension? = null

    /** Hooks in if Plan is present and wanted; returns whether it did. Plan may enable later and reload, which registers again. */
    fun hook(plugin: XaliChat): Boolean {
        if (!plugin.config.data.plan.enable || plugin.server.pluginManager.getPlugin("Plan") == null) return false
        try {
            val capabilities = CapabilityService.getInstance()
            val extension = if (capabilities.hasCapability("DATA_EXTENSION_VALUES") && capabilities.hasCapability("DATA_EXTENSION_TABLES")) {
                PlanExtension(plugin).also { this.extension = it }
            } else {
                plugin.logger.warning("This version of Plan can't take extension values and tables; not adding the chat log to its pages.")
                null
            }
            val page = plugin.config.data.plan.page && capabilities.hasCapability("PAGE_EXTENSION_RESOLVERS") && capabilities.hasCapability("PAGE_EXTENSION_USER_PERMISSIONS")
            if (plugin.config.data.plan.page && !page) plugin.logger.warning("This version of Plan can't take pages with web permissions; not adding the chat log page.")
            if (extension == null && !page) return false
            capabilities.registerEnableListener { enabled -> if (enabled) register(plugin, extension, page) }
            register(plugin, extension, page)
            return true
        } catch (e: NoClassDefFoundError) {
            debug("Plan's API is not available: {0}", e)
        } catch (e: Exception) {
            plugin.logger.fail("Could not hook into Plan.", e)
        }
        return false
    }

    /** Takes the extension out of Plan again; for disabling. */
    fun unhook() {
        val extension = extension ?: return
        this.extension = null
        try {
            ExtensionService.getInstance().unregister(extension)
        } catch (e: Throwable) {
            // Plan went first, or was never there: nothing left to take it out of.
        }
    }

    private fun register(plugin: XaliChat, extension: PlanExtension?, page: Boolean) {
        try {
            if (extension != null) {
                val caller = ExtensionService.getInstance().register(extension)
                if (caller.isPresent) plugin.logger.info("Added the chat and command log to Plan.")
                else debug("Plan has XaliChat's extension switched off in its own config")
            }
            if (page) registerPage(plugin)
        } catch (e: IllegalStateException) {
            debug("Plan is not enabled yet; waiting for it: {0}", e)
        } catch (e: IllegalArgumentException) {
            plugin.logger.fail("Plan rejected XaliChat.", e)
        }
    }

    /** Called again after a Plan reload, which may have dropped the resolver. */
    private fun registerPage(plugin: XaliChat) {
        val resolvers = ResolverService.getInstance()
        if (resolvers.getResolver(LogPage.PATH).isPresent) return
        resolvers.registerResolver("XaliChat", LogPage.PATH, LogPage({ plugin.isEnabled }, { plugin.config.data.logging.messages.enable }, plugin.logChanges))
        try {
            resolvers.registerPermissions(LogPage.VIEW, LogPage.VIEW_MESSAGES)
        } catch (e: Exception) {
            debug("Plan did not take the log page's permissions: {0}", e)
        }
        // The log in XaliChat's card on Plan's plugin pages, which is Plan's own page given a script.
        if (CapabilityService.getInstance().hasCapability("PAGE_EXTENSION_RESOURCES")) try {
            ResourceService.getInstance().addScriptsToResource("XaliChat", "index.html", ResourceService.Position.PRE_MAIN_SCRIPT, LogPage.SCRIPT_PATH)
        } catch (e: Exception) {
            debug("Plan did not take the log script: {0}", e)
        }
        plugin.logger.info("Added the log page to Plan at ${LogPage.PATH}; give Plan users ${LogPage.VIEW} to open it.")
    }
}
