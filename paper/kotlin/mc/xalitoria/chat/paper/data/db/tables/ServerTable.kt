package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/** The servers sharing this database; log rows link here by id. */
object ServerTable : Table(DatabaseManager.tableName("servers")) {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 64).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}
