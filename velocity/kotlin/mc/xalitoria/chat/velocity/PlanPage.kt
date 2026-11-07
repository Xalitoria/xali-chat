package mc.xalitoria.chat.velocity

import com.djrapitops.plan.capability.CapabilityService
import com.djrapitops.plan.extension.ExtensionService
import mc.xalitoria.chat.logs.LogChanges
import mc.xalitoria.chat.logs.LogPage
import org.slf4j.Logger

/**
 * Puts the log page on the Plan whose web server runs on this proxy: Plan's UI, and so its pages, are served by the
 * one proxy of a network, while the servers only feed it their data through the shared database. Everything that
 * names a Plan class is here, and this is only used after the plugin manager has said Plan is loaded. It also gives the
 * proxy an extension of its own ([ProxyPlanExtension]), which is what makes Plan list XaliChat among the network's plugins.
 */
class PlanPage(
    private val logger: Logger,
    private val enabled: () -> Boolean,
    private val messages: () -> Boolean,
    private val changes: LogChanges,
    private val debug: (String) -> Unit,
    /** The proxy plugin's version, which the page shows next to the servers'. */
    private val version: String,
) {
    /** Takes the extension out of Plan again; for closing. */
    fun unhook() {
        try {
            ExtensionService.getInstance().unregister(extension)
        } catch (e: Throwable) {
            // Plan went first, or was never there: nothing left to take it out of.
        }
    }

    /** Registers the page now if Plan's web server is up, and again whenever Plan enables (it may reload). Returns false if this Plan can't take pages. */
    fun hook(): Boolean {
        if (!LogPage.supported()) {
            logger.warn("This version of Plan can't take pages with web permissions; not adding the chat log page.")
            return false
        }
        CapabilityService.getInstance().registerEnableListener { enabled -> if (enabled) register() }
        register()
        return true
    }

    private val extension = ProxyPlanExtension(messages) { debug(it) }

    private fun register() {
        try {
            // The card the log is framed into on the network's pages; without it Plan has no XaliChat entry there.
            if (CapabilityService.getInstance().hasCapability("DATA_EXTENSION_VALUES")) ExtensionService.getInstance().register(extension)
            LogPage.register(
                { LogPage(enabled, messages, changes, { debug("The log page could not read the database: $it") }, LogPage.Host(proxy = true, version = version)) },
                { logger.info(it) },
                { message, error -> debug("$message: $error") },
            )
        } catch (e: IllegalStateException) {
            debug("Plan is not enabled yet; waiting for it: $e")
        } catch (e: IllegalArgumentException) {
            logger.warn("Plan rejected the log page", e)
        }
    }
}
