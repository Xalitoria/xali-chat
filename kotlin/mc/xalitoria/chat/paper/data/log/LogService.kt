package mc.xalitoria.chat.paper.data.log

import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.concurrent.LogWriter
import mc.xalitoria.chat.paper.data.db.repository.ChatLogRepository
import mc.xalitoria.chat.paper.data.db.repository.CommandLogRepository
import java.util.Base64
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * Records chat and commands to the database or, per `logging.<kind>.database` or on write failure,
 * to the server log as `CHATLOG|ts|uuid|name|base64` / `CMDLOG|...` lines, which `/chatlog` reads.
 */
class LogService(private val plugin: XaliChat) {
    private val chatWriter = LogWriter<ChatLogEntry>(
        plugin.scope, "chat", plugin.logger,
        write = ChatLogRepository::insertAll,
        fallback = ::writeChatText,
    )
    private val commandWriter = LogWriter<CommandLogEntry>(
        plugin.scope, "command", plugin.logger,
        write = CommandLogRepository::insertAll,
        fallback = ::writeCommandText,
    )

    fun chat(entry: ChatLogEntry) {
        if (plugin.config.data.logging.chat.database) chatWriter.submit(entry) else writeChatText(entry)
    }

    fun command(entry: CommandLogEntry) {
        if (plugin.config.data.logging.commands.database) commandWriter.submit(entry) else writeCommandText(entry)
    }

    /** Flushes queued entries; call before the database closes. */
    suspend fun close() {
        chatWriter.close(5.seconds)
        commandWriter.close(5.seconds)
    }

    private fun writeChatText(entry: ChatLogEntry) {
        chatText.info("$CHAT_MARKER${entry.timestampMillis}|${entry.uniqueId}|${entry.username}|${encode(entry.message)}")
    }

    private fun writeCommandText(entry: CommandLogEntry) {
        commandText.info("$COMMAND_MARKER${entry.timestampMillis}|${entry.uniqueId}|${entry.username}|${encode(entry.command)}")
    }

    private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private companion object {
        const val CHAT_MARKER = "CHATLOG|"
        const val COMMAND_MARKER = "CMDLOG|"
        val chatText: Logger = Logger.getLogger("XALICHAT_CHATLOG")
        val commandText: Logger = Logger.getLogger("XALICHAT_CMDLOG")
    }
}
