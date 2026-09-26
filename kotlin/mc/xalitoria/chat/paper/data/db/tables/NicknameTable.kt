package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/** One nickname per player, by UUID. [plain] is the lowercased visible text, for the uniqueness check. */
object NicknameTable : Table(DatabaseManager.tableName("nickname")) {
    val owner = uuid("owner")
    val nickname = varchar("nickname", 256)
    val plain = varchar("plain", 64).index()

    override val primaryKey = PrimaryKey(owner)
}
