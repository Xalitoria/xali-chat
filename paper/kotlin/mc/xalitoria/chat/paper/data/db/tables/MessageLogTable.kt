package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/**
 * One row per private message that was delivered, on the sender's server. The console has no id, and neither does a
 * receiver on another server, which the sender's server only knows by name.
 */
object MessageLogTable : Table(DatabaseManager.tableName("message_log")) {
    val id = long("id").autoIncrement()
    /** Null for rows written before servers were tracked. */
    val server = reference("server_id", ServerTable.id).nullable()
    val timestamp = long("timestamp")
    val senderId = uuid("sender_id").nullable()
    val sender = varchar("sender", 32)
    val receiverId = uuid("receiver_id").nullable()
    val receiver = varchar("receiver", 32)
    val message = text("message")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, server, timestamp)
        index(false, senderId, timestamp)
        index(false, receiverId, timestamp)
    }
}
