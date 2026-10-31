package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.MessageLogTable
import mc.xalitoria.chat.paper.data.log.MessageLogEntry
import org.jetbrains.exposed.v1.jdbc.batchInsert
import kotlin.uuid.toKotlinUuid

object MessageLogRepository {
    suspend fun insertAll(entries: List<MessageLogEntry>) {
        dbTransaction {
            MessageLogTable.batchInsert(entries, shouldReturnGeneratedValues = false) { entry ->
                this[MessageLogTable.server] = DatabaseManager.serverId
                this[MessageLogTable.timestamp] = entry.timestampMillis
                this[MessageLogTable.senderId] = entry.senderId?.toKotlinUuid()
                this[MessageLogTable.sender] = entry.sender
                this[MessageLogTable.receiverId] = entry.receiverId?.toKotlinUuid()
                this[MessageLogTable.receiver] = entry.receiver
                this[MessageLogTable.message] = entry.message
            }
        }
    }
}
