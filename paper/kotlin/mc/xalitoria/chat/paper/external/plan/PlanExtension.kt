package mc.xalitoria.chat.paper.external.plan

import com.djrapitops.plan.extension.CallEvents
import com.djrapitops.plan.extension.DataExtension
import com.djrapitops.plan.extension.ElementOrder
import com.djrapitops.plan.extension.annotation.BooleanProvider
import com.djrapitops.plan.extension.annotation.Conditional
import com.djrapitops.plan.extension.annotation.NumberProvider
import com.djrapitops.plan.extension.annotation.PluginInfo
import com.djrapitops.plan.extension.annotation.Tab
import com.djrapitops.plan.extension.annotation.TabInfo
import com.djrapitops.plan.extension.annotation.TableProvider
import com.djrapitops.plan.extension.icon.Color
import com.djrapitops.plan.extension.icon.Family
import com.djrapitops.plan.extension.icon.Icon
import com.djrapitops.plan.extension.table.Table
import com.djrapitops.plan.extension.table.TableColumnFormat
import kotlinx.coroutines.runBlocking
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.db.repository.ChatLogRepository
import mc.xalitoria.chat.paper.data.db.repository.CommandLogRepository
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * What XaliChat's chat and command log adds to Plan: per player the number of messages and commands on this server and
 * their latest ones, and per server what was said and run in the last day. Only what `logging.<kind>.database` keeps
 * in the database is there to show.
 *
 * Plan calls these methods off the server thread, so they read the database directly. Every Plan class is used only
 * from here and [PlanHook], which the plugin touches only once Plan is known to be installed.
 */
@PluginInfo(name = "XaliChat", iconName = "comments", iconFamily = Family.SOLID, color = Color.LIGHT_BLUE)
@TabInfo(tab = "Chat", iconName = "comment", iconFamily = Family.SOLID, elementOrder = [ElementOrder.VALUES, ElementOrder.TABLE])
@TabInfo(tab = "Commands", iconName = "terminal", iconFamily = Family.SOLID, elementOrder = [ElementOrder.VALUES, ElementOrder.TABLE])
class PlanExtension(private val plugin: XaliChat) : DataExtension {
    private val settings get() = plugin.config.data.plan
    private val rows get() = settings.rows.coerceIn(1, 100)

    /** Nothing here needs polling: a player's page is refreshed when they join and leave, the server's every so often. */
    override fun callExtensionMethodsOn() = arrayOf(
        CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL,
    )

    @BooleanProvider(text = "Chat shown", conditionName = CHAT, hidden = true)
    fun chatShown(): Boolean = settings.chat && plugin.config.data.logging.chat.database

    @BooleanProvider(text = "Commands shown", conditionName = COMMANDS, hidden = true)
    fun commandsShown(): Boolean = settings.commands && plugin.config.data.logging.commands.database

    @Conditional(CHAT)
    @Tab("Chat")
    @NumberProvider(text = "Chat messages", description = "On this server", iconName = "comment", iconFamily = Family.SOLID, iconColor = Color.LIGHT_BLUE, priority = 20)
    fun chatMessages(playerUUID: UUID): Long = query(0) { ChatLogRepository.count(playerUUID, null) }

    @Conditional(CHAT)
    @Tab("Chat")
    @TableProvider(tableColor = Color.LIGHT_BLUE)
    fun latestChat(playerUUID: UUID): Table {
        val table = Table.builder()
            .columnOne("When", Icon(Family.SOLID, "clock", Color.NONE))
            .columnOneFormat(TableColumnFormat.DATE_SECOND)
            .columnTwo("Message", Icon(Family.SOLID, "comment", Color.NONE))
        query(emptyList()) { ChatLogRepository.recent(playerUUID, rows) }
            .forEach { table.addRow(it.timestampMillis, cut(it.message)) }
        return table.build()
    }

    @Conditional(COMMANDS)
    @Tab("Commands")
    @NumberProvider(text = "Commands", description = "On this server", iconName = "terminal", iconFamily = Family.SOLID, iconColor = Color.DEEP_PURPLE, priority = 19)
    fun commands(playerUUID: UUID): Long = query(0) { CommandLogRepository.count(playerUUID, null) }

    @Conditional(COMMANDS)
    @Tab("Commands")
    @TableProvider(tableColor = Color.DEEP_PURPLE)
    fun latestCommands(playerUUID: UUID): Table {
        val table = Table.builder()
            .columnOne("When", Icon(Family.SOLID, "clock", Color.NONE))
            .columnOneFormat(TableColumnFormat.DATE_SECOND)
            .columnTwo("Command", Icon(Family.SOLID, "terminal", Color.NONE))
        query(emptyList()) { CommandLogRepository.recent(playerUUID, rows) }
            .forEach { table.addRow(it.timestampMillis, cut("/" + it.command)) }
        return table.build()
    }

    @Conditional(CHAT)
    @NumberProvider(text = "Chat messages (24 h)", description = "Sent on this server in the last day", iconName = "comment", iconFamily = Family.SOLID, iconColor = Color.LIGHT_BLUE, priority = 20)
    fun chatMessagesToday(): Long = query(0) { ChatLogRepository.count(null, System.currentTimeMillis() - 1.days.inWholeMilliseconds) }

    @Conditional(COMMANDS)
    @NumberProvider(text = "Commands (24 h)", description = "Run on this server in the last day", iconName = "terminal", iconFamily = Family.SOLID, iconColor = Color.DEEP_PURPLE, priority = 19)
    fun commandsToday(): Long = query(0) { CommandLogRepository.count(null, System.currentTimeMillis() - 1.days.inWholeMilliseconds) }

    /** Runs a database read; a failure shows [fallback] on the page and a line in the debug log, never an error in Plan. */
    private fun <T> query(fallback: T, read: suspend () -> T): T = try {
        runBlocking { read() }
    } catch (e: Exception) {
        debug("Plan could not read the chat log: {0}", e)
        fallback
    }

    /** Plan keeps 50 characters of a table value; longer text is cut with an ellipsis. */
    private fun cut(text: String): String = if (text.length <= MAX) text else text.take(MAX - 1) + "…"

    private companion object {
        const val CHAT = "xalichat-chat"
        const val COMMANDS = "xalichat-commands"
        const val MAX = 50
    }
}
