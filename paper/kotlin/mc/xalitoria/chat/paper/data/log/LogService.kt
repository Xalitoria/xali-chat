package mc.xalitoria.chat.paper.data.log

import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.concurrent.LogWriter
import mc.xalitoria.chat.paper.data.db.repository.ChatLogRepository
import mc.xalitoria.chat.paper.data.db.repository.CommandLogRepository
import mc.xalitoria.chat.paper.data.db.repository.MessageLogRepository
import java.util.Base64
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * Records chat and commands to the database or, per `logging.<kind>.database` or on write failure,
 * to the server log as `CHATLOG|ts|uuid|name|base64` / `CMDLOG|...` lines, which `/chatlog` reads. Private messages
 * only go to the database, and only when `logging.messages.enable` is on. [written] is called after each batch has
 * reached the database, which is how the log page and the other servers learn that there is something new.
 */
class LogService(private val plugin: XaliChat, private val written: () -> Unit) {
    private val chatWriter = LogWriter<ChatLogEntry>(
        plugin.scope, "chat", plugin.logger,
        write = { ChatLogRepository.insertAll(it); written() },
        fallback = ::writeChatText,
    )
    private val commandWriter = LogWriter<CommandLogEntry>(
        plugin.scope, "command", plugin.logger,
        write = { CommandLogRepository.insertAll(it); written() },
        fallback = ::writeCommandText,
    )
    /** Private messages have no text log to fall back to: one that can't be written is dropped. */
    private val messageWriter = LogWriter<MessageLogEntry>(
        plugin.scope, "message", plugin.logger,
        write = { MessageLogRepository.insertAll(it); written() },
        fallback = {},
    )

    fun chat(entry: ChatLogEntry) {
        if (plugin.config.data.logging.chat.database) chatWriter.submit(entry) else writeChatText(entry)
    }

    fun command(entry: CommandLogEntry) {
        if (plugin.config.data.logging.commands.database) commandWriter.submit(entry) else writeCommandText(entry)
    }

    fun message(entry: MessageLogEntry) {
        if (plugin.config.data.logging.messages.enable) messageWriter.submit(entry)
    }

    /** Flushes queued entries; call before the database closes. */
    suspend fun close() {
        chatWriter.close(5.seconds)
        commandWriter.close(5.seconds)
        messageWriter.close(5.seconds)
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
