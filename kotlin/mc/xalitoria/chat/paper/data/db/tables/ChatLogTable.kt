package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/** One row per chat message that reached [mc.xalitoria.chat.paper.listener.ChatEvent]'s logging step. */
object ChatLogTable : Table(DatabaseManager.tableName("chat_log")) {
    val id = long("id").autoIncrement()
    /** Null for rows written before servers were tracked. */
    val server = reference("server_id", ServerTable.id).nullable()
    val timestamp = long("timestamp")
    val uniqueId = uuid("unique_id")
    val username = varchar("username", 32)
    val message = text("message")

    override val primaryKey = PrimaryKey(id)

    init {
        // Matches the searches: one server (or one player) over a time range, newest first.
        index(false, server, timestamp)
        index(false, uniqueId, timestamp)
    }
}
