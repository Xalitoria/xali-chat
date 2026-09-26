package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/** One row per command a player ran, as typed (without the leading slash). */
object CommandLogTable : Table(DatabaseManager.tableName("command_log")) {
    val id = long("id").autoIncrement()
    /** Null for rows written before servers were tracked. */
    val server = reference("server_id", ServerTable.id).nullable()
    val timestamp = long("timestamp")
    val uniqueId = uuid("unique_id")
    val username = varchar("username", 32)
    val command = text("command")

    override val primaryKey = PrimaryKey(id)

    init {
        // Matches the searches: one server (or one player) over a time range, newest first.
        index(false, server, timestamp)
        index(false, uniqueId, timestamp)
    }
}
