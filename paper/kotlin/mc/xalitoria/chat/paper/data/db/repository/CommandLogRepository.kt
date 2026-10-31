package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.log.CommandLogEntry
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.CommandLogTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

object CommandLogRepository {
    suspend fun insertAll(entries: List<CommandLogEntry>) {
        dbTransaction {
            CommandLogTable.batchInsert(entries, shouldReturnGeneratedValues = false) { entry ->
                this[CommandLogTable.server] = DatabaseManager.serverId
                this[CommandLogTable.timestamp] = entry.timestampMillis
                this[CommandLogTable.uniqueId] = entry.uniqueId.toKotlinUuid()
                this[CommandLogTable.username] = entry.username
                this[CommandLogTable.command] = entry.command
            }
        }
    }

    /** How many commands [playerId] ran on this server (everyone when null), since [sinceMillis] if given. */
    suspend fun count(playerId: UUID?, sinceMillis: Long?): Long = dbTransaction {
        val conditions = mutableListOf<Op<Boolean>>(CommandLogTable.server eq DatabaseManager.serverId or CommandLogTable.server.isNull())
        if (playerId != null) conditions += CommandLogTable.uniqueId eq playerId.toKotlinUuid()
        if (sinceMillis != null) conditions += CommandLogTable.timestamp greaterEq sinceMillis
        CommandLogTable.selectAll().where { conditions.compoundAnd() }.count()
    }

    /** [playerId]'s newest commands on this server, newest first. */
    suspend fun recent(playerId: UUID, limit: Int): List<CommandLogEntry> = dbTransaction {
        CommandLogTable.selectAll()
            .where { (CommandLogTable.uniqueId eq playerId.toKotlinUuid()) and (CommandLogTable.server eq DatabaseManager.serverId or CommandLogTable.server.isNull()) }
            .orderBy(CommandLogTable.timestamp to SortOrder.DESC, CommandLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map {
                CommandLogEntry(it[CommandLogTable.timestamp], it[CommandLogTable.uniqueId].toJavaUuid(), it[CommandLogTable.username], it[CommandLogTable.command])
            }
    }
}
