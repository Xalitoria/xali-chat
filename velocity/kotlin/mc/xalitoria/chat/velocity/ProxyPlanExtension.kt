package mc.xalitoria.chat.velocity

import com.djrapitops.plan.extension.CallEvents
import com.djrapitops.plan.extension.DataExtension
import com.djrapitops.plan.extension.annotation.BooleanProvider
import com.djrapitops.plan.extension.annotation.Conditional
import com.djrapitops.plan.extension.annotation.NumberProvider
import com.djrapitops.plan.extension.annotation.PluginInfo
import com.djrapitops.plan.extension.icon.Color
import com.djrapitops.plan.extension.icon.Family
import kotlinx.coroutines.runBlocking
import mc.xalitoria.chat.logs.LogFilter
import mc.xalitoria.chat.logs.LogKind
import mc.xalitoria.chat.logs.LogRepository
import kotlin.time.Duration.Companion.days

/**
 * XaliChat's card on Plan's network pages: what the logs of every server hold, counted. Plan shows a plugin on
 * `/network/plugins/XaliChat` when the proxy has an extension of that name, and that card is where [PlanPage]'s
 * script frames the log of every server. Each server's own XaliChat adds the card for itself.
 *
 * Plan calls these off the proxy's threads, so they read the database directly; a read that fails shows 0 and a line
 * in the debug log.
 */
@PluginInfo(name = "XaliChat", iconName = "comments", iconFamily = Family.SOLID, color = Color.LIGHT_BLUE)
class ProxyPlanExtension(
    /** `plan.messages`: whether private messages are shown at all. */
    private val messagesWanted: () -> Boolean,
    private val debug: (String) -> Unit,
) : DataExtension {
    override fun callExtensionMethodsOn() = arrayOf(CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL)

    /** Private messages are only logged by servers that were asked to, so they are counted only once there are some. */
    @BooleanProvider(text = "Private messages shown", conditionName = MESSAGES, hidden = true)
    fun messagesShown(): Boolean = messagesWanted() && count(LogKind.MESSAGE, null) > 0

    @NumberProvider(text = "Chat messages (24 h)", description = "Sent on every server in the last day", iconName = "comment", iconFamily = Family.SOLID, iconColor = Color.LIGHT_BLUE, priority = 20)
    fun chatToday(): Long = count(LogKind.CHAT, since())

    @NumberProvider(text = "Commands (24 h)", description = "Run on every server in the last day", iconName = "terminal", iconFamily = Family.SOLID, iconColor = Color.DEEP_PURPLE, priority = 19)
    fun commandsToday(): Long = count(LogKind.COMMAND, since())

    @Conditional(MESSAGES)
    @NumberProvider(text = "Private messages (24 h)", description = "Sent on every server in the last day", iconName = "envelope", iconFamily = Family.SOLID, iconColor = Color.ORANGE, priority = 18)
    fun messagesToday(): Long = count(LogKind.MESSAGE, since())

    @NumberProvider(text = "Chat messages (total)", description = "Logged on every server since the log began", iconName = "comment", iconFamily = Family.SOLID, iconColor = Color.LIGHT_BLUE, priority = 17)
    fun chatTotal(): Long = count(LogKind.CHAT, null)

    @NumberProvider(text = "Commands (total)", description = "Logged on every server since the log began", iconName = "terminal", iconFamily = Family.SOLID, iconColor = Color.DEEP_PURPLE, priority = 16)
    fun commandsTotal(): Long = count(LogKind.COMMAND, null)

    @Conditional(MESSAGES)
    @NumberProvider(text = "Private messages (total)", description = "Sent on every server since the log began", iconName = "envelope", iconFamily = Family.SOLID, iconColor = Color.ORANGE, priority = 15)
    fun messagesTotal(): Long = count(LogKind.MESSAGE, null)

    private fun since() = System.currentTimeMillis() - 1.days.inWholeMilliseconds

    private fun count(kind: LogKind, from: Long?): Long = try {
        runBlocking { LogRepository.count(LogFilter(setOf(kind), from = from)) }
    } catch (e: Exception) {
        debug("Plan could not count the chat log: $e")
        0
    }

    private companion object {
        const val MESSAGES = "xalichat-messages"
    }
}
