package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.log.CommandLogEntry
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.CommandLogTable
import org.jetbrains.exposed.v1.jdbc.batchInsert
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
}
