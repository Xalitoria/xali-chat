package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.ChatLogTable
import mc.xalitoria.chat.paper.data.db.tables.CommandLogTable
import mc.xalitoria.chat.paper.data.db.tables.MessageLogTable
import mc.xalitoria.chat.paper.data.db.tables.ServerTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** The three logs, which the log page shows as one. [code] prefixes a row's key. */
enum class LogKind(val code: Char) {
    CHAT('c'),
    COMMAND('k'),
    MESSAGE('m');

    companion object {
        fun of(code: Char): LogKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One entry of any log. [text] is the message, the command without its slash or the private message; [target] is the
 * receiver of a private message and null otherwise. [server] is null for rows from before servers were tracked.
 */
data class LogRow(
    val kind: LogKind,
    val id: Long,
    val timestampMillis: Long,
    val uniqueId: UUID?,
    val username: String,
    val text: String,
    val target: String?,
    val server: String?,
) {
    /** Names the row across the three tables, whose ids overlap. */
    val key get() = "${kind.code}$id"
}

/**
 * What to look for. [player] is a name or a uuid (a private message matches on either end), [server] a server name,
 * [text] a case-insensitive substring of the text or of a name. [from] and [until] bound the time, both inclusive.
 */
data class LogFilter(
    val kinds: Set<LogKind> = LogKind.entries.toSet(),
    val player: String? = null,
    val server: String? = null,
    val text: String? = null,
    val from: Long? = null,
    val until: Long? = null,
)

object LogRepository {
    /** The oldest first, whatever a search's order is, so a window reads top to bottom. */
    val chronological: Comparator<LogRow> = compareBy<LogRow>({ it.timestampMillis }, { it.kind.ordinal }, { it.id })

    /** Up to [limit] matching rows, newest first or oldest first. The same millisecond may repeat across two pages. */
    suspend fun search(filter: LogFilter, limit: Int, newestFirst: Boolean = true): List<LogRow> =
        dbTransaction { query(filter, limit, newestFirst) }

    suspend fun find(kind: LogKind, id: Long): LogRow? = dbTransaction { find0(kind, id) }

    /** The names of the servers that have written to these logs. */
    suspend fun servers(): List<String> = dbTransaction { ServerTable.selectAll().map { it[ServerTable.name] } }

    /**
     * The row [kind]/[id] and the up to [radius] matching rows on each side of it in time, oldest first. The row is in
     * even when it doesn't match [filter]. Unless [filter] names a server the window is the row's own server. Null when
     * there is no such row.
     */
    suspend fun around(kind: LogKind, id: Long, filter: LogFilter, radius: Int): List<LogRow>? = dbTransaction {
        val focus = find0(kind, id) ?: return@dbTransaction null
        val scoped = if (filter.server != null) filter else filter.copy(server = focus.server)
        val older = query(scoped.copy(until = focus.timestampMillis), radius + 1, newestFirst = true)
        val newer = query(scoped.copy(from = focus.timestampMillis), radius + 1, newestFirst = false)
        (older + newer + focus).distinctBy { it.key }.sortedWith(chronological)
    }

    private fun query(filter: LogFilter, limit: Int, newestFirst: Boolean): List<LogRow> {
        val merged = sources.filter { it.kind in filter.kinds }.flatMap { it.select(filter, limit, newestFirst) }
        return merged.sortedWith(if (newestFirst) chronological.reversed() else chronological).take(limit)
    }

    private fun find0(kind: LogKind, id: Long): LogRow? =
        sources.first { it.kind == kind }.let { source ->
            source.joined().selectAll().where { source.id eq id }.limit(1).map(source.read).singleOrNull()
        }

    /** What differs between the tables, so one query serves all of them. */
    private class Source(
        val kind: LogKind,
        val table: Table,
        val id: Column<Long>,
        val server: Column<Int?>,
        val timestamp: Column<Long>,
        val username: Column<String>,
        val text: Column<String>,
        val target: Column<String>?,
        /** Whether a row involves the player, by uuid or by name (lowercase). */
        val byPlayer: (Uuid?, String) -> Op<Boolean>,
        val read: (ResultRow) -> LogRow,
    ) {
        fun joined() = table.join(ServerTable, JoinType.LEFT, server, ServerTable.id)

        fun select(filter: LogFilter, limit: Int, newestFirst: Boolean): List<LogRow> {
            val conditions = mutableListOf<Op<Boolean>>()
            filter.server?.let { conditions += ServerTable.name.lowerCase() eq it.lowercase() }
            filter.player?.let { conditions += byPlayer(runCatching { Uuid.parse(it) }.getOrNull(), it.lowercase()) }
            filter.from?.let { conditions += timestamp greaterEq it }
            filter.until?.let { conditions += timestamp lessEq it }
            filter.text?.let { text ->
                // `\` escapes %, _ and itself, so a filter like "50%" matches literally.
                val escaped = text.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                val pattern = LikePattern("%$escaped%", '\\')
                val matches: List<Op<Boolean>> = listOfNotNull(this.text, username, target).map { it.lowerCase() like pattern }
                conditions += matches.reduce { a, b -> a or b }
            }
            val order = if (newestFirst) SortOrder.DESC else SortOrder.ASC
            val selected = if (conditions.isEmpty()) joined().selectAll() else joined().selectAll().where { conditions.compoundAnd() }
            return selected.orderBy(timestamp to order, id to order).limit(limit).map(read)
        }
    }

    private val sources = listOf(
        Source(
            LogKind.CHAT, ChatLogTable, ChatLogTable.id, ChatLogTable.server, ChatLogTable.timestamp,
            ChatLogTable.username, ChatLogTable.message, null,
            byPlayer = { id, name -> if (id != null) ChatLogTable.uniqueId eq id else ChatLogTable.username.lowerCase() eq name },
            read = {
                LogRow(
                    LogKind.CHAT, it[ChatLogTable.id], it[ChatLogTable.timestamp], it[ChatLogTable.uniqueId].toJavaUuid(),
                    it[ChatLogTable.username], it[ChatLogTable.message], null, it.getOrNull(ServerTable.name),
                )
            },
        ),
        Source(
            LogKind.COMMAND, CommandLogTable, CommandLogTable.id, CommandLogTable.server, CommandLogTable.timestamp,
            CommandLogTable.username, CommandLogTable.command, null,
            byPlayer = { id, name -> if (id != null) CommandLogTable.uniqueId eq id else CommandLogTable.username.lowerCase() eq name },
            read = {
                LogRow(
                    LogKind.COMMAND, it[CommandLogTable.id], it[CommandLogTable.timestamp], it[CommandLogTable.uniqueId].toJavaUuid(),
                    it[CommandLogTable.username], it[CommandLogTable.command], null, it.getOrNull(ServerTable.name),
                )
            },
        ),
        Source(
            LogKind.MESSAGE, MessageLogTable, MessageLogTable.id, MessageLogTable.server, MessageLogTable.timestamp,
            MessageLogTable.sender, MessageLogTable.message, MessageLogTable.receiver,
            byPlayer = { id, name ->
                if (id != null) (MessageLogTable.senderId eq id) or (MessageLogTable.receiverId eq id)
                else (MessageLogTable.sender.lowerCase() eq name) or (MessageLogTable.receiver.lowerCase() eq name)
            },
            read = {
                LogRow(
                    LogKind.MESSAGE, it[MessageLogTable.id], it[MessageLogTable.timestamp], it[MessageLogTable.senderId]?.toJavaUuid(),
                    it[MessageLogTable.sender], it[MessageLogTable.message], it[MessageLogTable.receiver], it.getOrNull(ServerTable.name),
                )
            },
        ),
    )
}
