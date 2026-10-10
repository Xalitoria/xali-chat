package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/**
 * `owner` ignores `ignored` — i.e. messages/chat from `ignored` are hidden from `owner`.
 */
object IgnoreTable : Table(DatabaseManager.tableName("ignore")) {
    val owner = uuid("owner")
    val ignored = uuid("ignored")

    override val primaryKey = PrimaryKey(owner, ignored)
}
