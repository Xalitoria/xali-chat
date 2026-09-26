package mc.xalitoria.chat.paper.data.db.tables

import mc.xalitoria.chat.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/** Per-player settings as name/value rows, so a new [Setting] needs no schema change. Absent rows use the default. */
object SettingTable : Table(DatabaseManager.tableName("setting")) {
    val owner = uuid("owner")
    val setting = varchar("setting", 32)
    val value = varchar("value", 64)

    override val primaryKey = PrimaryKey(owner, setting)
}

/** A stored player setting; the enum name is its row key. */
enum class Setting {
    /** Hides global chat from the player. */
    MUTE_PLAYERS,

    /** Hides broadcasts from the player. */
    MUTE_BROADCASTS,
}
