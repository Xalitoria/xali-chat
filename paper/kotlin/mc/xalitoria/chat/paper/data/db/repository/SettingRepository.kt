package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.Setting
import mc.xalitoria.chat.paper.data.db.tables.SettingTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.uuid.toKotlinUuid

object SettingRepository {
    /** Every stored setting of [owner]; rows whose name is no longer a [Setting] are skipped. */
    suspend fun load(owner: UUID): Map<Setting, String> =
        dbTransaction {
            SettingTable.selectAll()
                .where { SettingTable.owner eq owner.toKotlinUuid() }
                .mapNotNull { row ->
                    Setting.entries.find { it.name == row[SettingTable.setting] }?.let { it to row[SettingTable.value] }
                }
                .toMap()
        }

    /** Stores [value] for [setting], or removes the row when it is null. */
    suspend fun set(owner: UUID, setting: Setting, value: String?) {
        dbTransaction {
            if (value == null) {
                SettingTable.deleteWhere { (SettingTable.owner eq owner.toKotlinUuid()) and (SettingTable.setting eq setting.name) }
            } else {
                SettingTable.upsert {
                    it[SettingTable.owner] = owner.toKotlinUuid()
                    it[SettingTable.setting] = setting.name
                    it[SettingTable.value] = value
                }
            }
        }
    }
}
