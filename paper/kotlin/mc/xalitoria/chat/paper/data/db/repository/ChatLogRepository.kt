package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.log.ChatLogEntry
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.ChatLogTable
import mc.xalitoria.chat.paper.data.db.tables.ServerTable
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

object ChatLogRepository {
    suspend fun insertAll(entries: List<ChatLogEntry>) {
        dbTransaction {
            ChatLogTable.batchInsert(entries, shouldReturnGeneratedValues = false) { entry ->
                this[ChatLogTable.server] = DatabaseManager.serverId
                this[ChatLogTable.timestamp] = entry.timestampMillis
                this[ChatLogTable.uniqueId] = entry.uniqueId.toKotlinUuid()
                this[ChatLogTable.username] = entry.username
                this[ChatLogTable.message] = entry.message
            }
        }
    }

    /**
     * Newest-first search: [player] matches by name or [playerId], [textFilter] is a case-insensitive
     * substring, [sinceMillis] a lower bound. [server] limits to one server by name (`null` searches
     * all; an unknown name matches nothing). This server's search also includes rows from before
     * servers were tracked.
     */
    suspend fun search(
        player: String?,
        playerId: UUID?,
        sinceMillis: Long?,
        textFilter: String?,
        server: String?,
        limit: Int,
    ): List<ChatLogEntry> = dbTransaction {
        val conditions = mutableListOf<Op<Boolean>>()
        if (server != null) {
            val serverId = ServerTable.selectAll().where { ServerTable.name eq server }.singleOrNull()?.get(ServerTable.id)
                ?: return@dbTransaction emptyList()
            val onServer = ChatLogTable.server eq serverId
            conditions += if (server == DatabaseManager.serverName) onServer or ChatLogTable.server.isNull() else onServer
        }
        if (player != null) {
            val byName = ChatLogTable.username.lowerCase() eq player.lowercase()
            conditions += if (playerId != null) (ChatLogTable.uniqueId eq playerId.toKotlinUuid()) or byName else byName
        }
        if (sinceMillis != null) conditions += ChatLogTable.timestamp greaterEq sinceMillis
        if (textFilter != null) {
            // `\` escapes %, _ and itself, so a filter like "50%" matches literally.
            val escaped = textFilter.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            conditions += ChatLogTable.message.lowerCase() like LikePattern("%$escaped%", '\\')
        }

        val query = if (conditions.isEmpty()) ChatLogTable.selectAll() else ChatLogTable.selectAll().where { conditions.compoundAnd() }
        query.orderBy(ChatLogTable.timestamp to SortOrder.DESC, ChatLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map {
                ChatLogEntry(
                    timestampMillis = it[ChatLogTable.timestamp],
                    uniqueId = it[ChatLogTable.uniqueId].toJavaUuid(),
                    username = it[ChatLogTable.username],
                    message = it[ChatLogTable.message],
                )
            }
    }

    /** How many messages [playerId] sent on this server (everyone when null), since [sinceMillis] if given. */
    suspend fun count(playerId: UUID?, sinceMillis: Long?): Long = dbTransaction {
        val conditions = mutableListOf<Op<Boolean>>(ChatLogTable.server eq DatabaseManager.serverId or ChatLogTable.server.isNull())
        if (playerId != null) conditions += ChatLogTable.uniqueId eq playerId.toKotlinUuid()
        if (sinceMillis != null) conditions += ChatLogTable.timestamp greaterEq sinceMillis
        ChatLogTable.selectAll().where { conditions.compoundAnd() }.count()
    }

    /** [playerId]'s newest messages on this server, newest first. */
    suspend fun recent(playerId: UUID, limit: Int): List<ChatLogEntry> = dbTransaction {
        ChatLogTable.selectAll()
            .where { (ChatLogTable.uniqueId eq playerId.toKotlinUuid()) and (ChatLogTable.server eq DatabaseManager.serverId or ChatLogTable.server.isNull()) }
            .orderBy(ChatLogTable.timestamp to SortOrder.DESC, ChatLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map {
                ChatLogEntry(it[ChatLogTable.timestamp], it[ChatLogTable.uniqueId].toJavaUuid(), it[ChatLogTable.username], it[ChatLogTable.message])
            }
    }
}
