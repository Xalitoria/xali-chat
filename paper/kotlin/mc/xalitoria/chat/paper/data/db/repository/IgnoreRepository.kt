package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.chat.paper.data.db.tables.IgnoreTable
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

object IgnoreRepository {
    suspend fun isIgnoring(owner: UUID, ignored: UUID): Boolean =
        dbTransaction {
            IgnoreTable.selectAll()
                .where { (IgnoreTable.owner eq owner.toKotlinUuid()) and (IgnoreTable.ignored eq ignored.toKotlinUuid()) }
                .empty().not()
        }

    /** Toggles ignore state for `owner` -> `ignored`. Returns the new state (true = now ignoring). */
    suspend fun toggle(owner: UUID, ignored: UUID): Boolean =
        dbTransaction {
            val exists = IgnoreTable.selectAll()
                .where { (IgnoreTable.owner eq owner.toKotlinUuid()) and (IgnoreTable.ignored eq ignored.toKotlinUuid()) }
                .empty().not()

            if (exists) {
                IgnoreTable.deleteWhere { (IgnoreTable.owner eq owner.toKotlinUuid()) and (IgnoreTable.ignored eq ignored.toKotlinUuid()) }
                false
            } else {
                IgnoreTable.insert {
                    it[IgnoreTable.owner] = owner.toKotlinUuid()
                    it[IgnoreTable.ignored] = ignored.toKotlinUuid()
                }
                true
            }
        }

    suspend fun ignoredByViewer(viewer: UUID): Set<UUID> =
        dbTransaction {
            IgnoreTable.selectAll()
                .where { IgnoreTable.owner eq viewer.toKotlinUuid() }
                .map { it[IgnoreTable.ignored].toJavaUuid() }
                .toSet()
        }
}
