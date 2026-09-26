package mc.xalitoria.chat.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.modal.command.XCommand
import mc.xalitoria.chat.paper.data.log.ChatLogFiles
import mc.xalitoria.chat.paper.data.log.ChatLogReader
import mc.xalitoria.chat.paper.data.log.ChatLogSearch
import mc.xalitoria.chat.paper.data.log.ChatLogSessions
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.chat.paper.data.db.repository.ChatLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mc.xalitoria.chat.paper.util.*
import mc.xalitoria.chat.paper.util.DurationParseResult.Invalid
import mc.xalitoria.chat.paper.util.DurationParseResult.Ok
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.message.*
import mc.xalitoria.util.serial.toUuidOrNull
import mc.xalitoria.chat.paper.external.protocol.protocolor
import mc.xalitoria.color.applyCmiGradients
import mc.xalitoria.color.componentColors
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import java.text.SimpleDateFormat
import java.util.*

/**
 * `/chatlog player:<name|uuid> since:<duration> text:<word> server:<name|*> page:<n>`, all optional.
 * `server:` defaults to this server and `*` searches all (text logs only hold this server's chat).
 * `page:` alone reuses the sender's last search; `text:` is a single token. `/chatlog help` explains the flags.
 * `/chatlog export <filename> [flags]` writes the matches to `exports/<filename>`; `/chatlog import <filename>` views one.
 */
object ChatLogCommand: XCommand(XaliChat.INSTANCE) {
    override val description: String = "Chat log search, export and import"
    override val mainCommand = "xchatlog"
    override val aliases: List<String> = ["chatlog"]
    private const val PAGE_SIZE = 9
    private const val EXPORT_FOLDER = "exports"
    private const val EXPORT_PERMISSION = "xalichat.admin.chatlog.export"
    private const val IMPORT_PERMISSION = "xalichat.admin.chatlog.import"

    override fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal(mainCommand)
        .executes { ctx -> execute(plugin, ctx.source.sender, ""); Command.SINGLE_SUCCESS }
        .then(Commands
            .literal("help")
            .executes { ctx ->
                val sender = ctx.source.sender
                if (sender.hasPermission(Permissions.CHATLOG)) sender send msg.help("chatlog") else sender fail noPermission
                Command.SINGLE_SUCCESS
            }
        )
        .then(Commands
            .literal("export")
            .executes { ctx -> ctx.source.sender fail msg.exportUsage("chatlog"); Command.SINGLE_SUCCESS }
            .then(Commands
                .argument("filename", StringArgumentType.word())
                .executes { ctx -> export(plugin, ctx.source.sender, StringArgumentType.getString(ctx, "filename"), ""); Command.SINGLE_SUCCESS }
                .then(Commands
                    .argument("flags", StringArgumentType.greedyString())
                    .executes { ctx ->
                        export(plugin, ctx.source.sender, StringArgumentType.getString(ctx, "filename"), StringArgumentType.getString(ctx, "flags"))
                        Command.SINGLE_SUCCESS
                    }
                )
            )
        )
        .then(Commands
            .literal("import")
            .executes { ctx -> ctx.source.sender fail msg.importUsage("chatlog"); Command.SINGLE_SUCCESS }
            .then(Commands
                .argument("filename", StringArgumentType.word())
                .executes { ctx -> import(plugin, ctx.source.sender, StringArgumentType.getString(ctx, "filename")); Command.SINGLE_SUCCESS }
            )
        )
        .then(Commands
            .argument("flags", StringArgumentType.greedyString())
            .executes { ctx -> execute(plugin, ctx.source.sender, StringArgumentType.getString(ctx, "flags")); Command.SINGLE_SUCCESS }
        )

    private fun execute(plugin: XaliChat, sender: CommandSender, flagString: String) {
        if (!sender.hasPermission(Permissions.CHATLOG)) {
            debug("/chatlog denied for {0}: missing {1}", sender.name, Permissions.CHATLOG)
            return sender fail noPermission
        }

        val flags = parseFlags(flagString)
        val page = flags["page"]?.toIntOrNull() ?: 1

        if (flags.keys.all { it == "page" }) {
            val previous = ChatLogSessions.get(sender.name)
            debug("/chatlog page {0} for {1}: reusing last search ({2})", page, sender.name, previous?.let { "${it.results.size} results" } ?: "none, expired")
            if (previous != null) return render(sender, previous, page)
        }

        search(plugin, sender, flags) { search ->
            ChatLogSessions.put(sender.name, search)
            render(sender, search, page)
        }
    }

    private fun export(plugin: XaliChat, sender: CommandSender, filename: String, flagString: String) {
        if (!sender.hasPermission(EXPORT_PERMISSION)) {
            debug("/chatlog export denied for {0}: missing {1}", sender.name, EXPORT_PERMISSION)
            return sender fail noPermission
        }
        val file = ChatLogFiles.resolve(plugin.dataFolder.toPath().resolve(EXPORT_FOLDER), filename)
            ?: return sender fail msg.fileInvalid

        search(plugin, sender, parseFlags(flagString)) { search ->
            if (search.results.isEmpty()) return@search sender fail msg.noResults
            plugin.scope.launch(Dispatchers.IO) {
                try {
                    ChatLogFiles.write(file, search.results)
                    debug("{0} exported {1} entries to {2}", sender.name, search.results.size, file)
                    plugin.runTask { sender send msg.exported(search.results.size, "$EXPORT_FOLDER/${file.fileName}") }
                } catch (e: Exception) {
                    plugin.logger.fail("Chat log export failed", e)
                    plugin.runTask { sender fail unexpectedError }
                }
            }
        }
    }

    /** Shows an exported file in the chat menu; it is read-only and never touches the stored logs. */
    private fun import(plugin: XaliChat, sender: CommandSender, filename: String) {
        if (!sender.hasPermission(IMPORT_PERMISSION)) {
            debug("/chatlog import denied for {0}: missing {1}", sender.name, IMPORT_PERMISSION)
            return sender fail noPermission
        }
        val file = ChatLogFiles.resolve(plugin.dataFolder.toPath().resolve(EXPORT_FOLDER), filename)
            ?: return sender fail msg.fileInvalid

        plugin.scope.launch(Dispatchers.IO) {
            try {
                val entries = ChatLogFiles.read(file)
                debug("{0} imported {1}: {2}", sender.name, file, entries?.let { "${it.size} entries" } ?: "file not found")
                plugin.runTask {
                    if (entries == null) return@runTask sender fail msg.fileMissing(filename)
                    val search = ChatLogSearch(null, null, null, null, null, entries)
                    ChatLogSessions.put(sender.name, search)
                    render(sender, search, 1)
                }
            } catch (e: Exception) {
                plugin.logger.fail("Chat log import failed", e)
                plugin.runTask { sender fail unexpectedError }
            }
        }
    }

    private fun parseFlags(flagString: String): Map<String, String> = flagString.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .mapNotNull { token ->
            val idx = token.indexOf(':')
            if (idx <= 0) null else token.substring(0, idx).lowercase() to token.substring(idx + 1)
        }
        .toMap()

    /** Runs the filters in [flags] against the active log store, then hands the results to [onResults] on the main thread. */
    private fun search(plugin: XaliChat, sender: CommandSender, flags: Map<String, String>, onResults: (ChatLogSearch) -> Unit) {
        val playerFilter = flags["player"]
        // Logs outlive the offline-player cache, so an unknown name still filters by name only.
        val playerId = playerFilter?.let { plugin.server.findKnownPlayer(it)?.uniqueId ?: it.toUuidOrNull() }
        val textFilter = flags["text"]
        val sinceInput = flags["since"]
        val serverInput = flags["server"]
        val server = when (serverInput) {
            null -> plugin.config.data.database.serverName
            "*" -> null
            else -> serverInput
        }

        val sinceMillis: Long? = if (sinceInput != null) {
            when (val parsed = DurationParser.parse(sinceInput)) {
                is Invalid -> return sender fail msg.usage("chatlog")
                is Ok -> parsed.duration?.let { System.currentTimeMillis() - it.inWholeMilliseconds }
            }
        } else null

        // Read from wherever chat is currently being written: the database, or the text logs.
        val fromDatabase = plugin.config.data.logging.chat.database
        plugin.scope.launch(Dispatchers.IO) {
            try {
                val results = when {
                    fromDatabase -> ChatLogRepository.search(playerFilter, playerId, sinceMillis, textFilter, server, ChatLogReader.HARD_LIMIT)
                    // Text logs only hold this server's chat.
                    server != null && server != plugin.config.data.database.serverName -> emptyList()
                    else -> ChatLogReader.search(playerFilter, playerId, sinceMillis, textFilter)
                }
                debug(
                    "Chat log search by {0}: player={1}, since={2}, text={3}, server={4}, source={5} -> {6} results",
                    sender.name, playerFilter, sinceInput, textFilter, serverInput ?: server, if (fromDatabase) "database" else "text logs", results.size,
                )
                val search = ChatLogSearch(playerFilter, sinceMillis, sinceInput, serverInput, textFilter, results)
                plugin.runTask { onResults(search) }
            } catch (e: Exception) {
                plugin.logger.fail("Chat log search failed", e)
                plugin.runTask { sender fail unexpectedError }
            }
        }
    }

    private fun render(sender: CommandSender, search: ChatLogSearch, page: Int) {
        if (search.results.isEmpty()) return sender fail msg.noResults

        val paged = ChatMenu.paginate(search.results, page, PAGE_SIZE)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        // Date and time, then the sender and message below it; hovering the message shows its tagged source.
        val entries = paged.items.map { entry ->
            val message = entry.message.toSafeComponent().hoverEvent(
                HoverEvent.showText(Component.text(entry.message.applyCmiGradients().componentColors()))
            )
            Component.text()
                .append(msg.time(dateFormat.format(Date(entry.timestampMillis))).toComponent())
                .appendNewline()
                .append(Component.text().append(msg.entry(entry.username).toComponent()).append(message).build().protocolor())
                .build()
        }

        sender send msg.header(paged.page, paged.totalPages, paged.totalItems)

        val commandForPage: (Int) -> String = { targetPage ->
            buildString {
                append("/chatlog ")
                search.player?.let { append("player:$it ") }
                search.sinceInput?.let { append("since:$it ") }
                search.textFilter?.let { append("text:$it ") }
                search.serverInput?.let { append("server:$it ") }
                append("page:$targetPage")
            }
        }

        val menu = ChatMenu.render(
            entries,
            paged.page,
            paged.totalPages,
            commandForPage,
            msg.pagePrev.toComponent(),
            msg.pageNext.toComponent(),
        )
        sender.sendMessage(menu)
    }

    private val cmdMsg get() = XaliChat.INSTANCE.messageConfig.data.commands
    private val msg get() = cmdMsg.chatLog
    private val noPermission get() = cmdMsg.noPermission
    private val unexpectedError get() = cmdMsg.unexpectedError
}
