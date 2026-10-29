package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.db.DatabaseManager.nicknameTransaction
import mc.xalitoria.chat.paper.data.db.tables.NicknameTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.uuid.toKotlinUuid

object NicknameRepository {
    suspend fun get(owner: UUID): String? =
        nicknameTransaction {
            NicknameTable.selectAll()
                .where { NicknameTable.owner eq owner.toKotlinUuid() }
                .firstOrNull()
                ?.get(NicknameTable.nickname)
        }

    /** Whether another player already uses a nickname with this lowercased visible text. */
    suspend fun isTaken(plain: String, except: UUID): Boolean =
        nicknameTransaction {
            NicknameTable.selectAll()
                .where { (NicknameTable.plain eq plain) and (NicknameTable.owner neq except.toKotlinUuid()) }
                .empty().not()
        }

    suspend fun set(owner: UUID, nickname: String, plain: String) {
        nicknameTransaction {
            NicknameTable.upsert {
                it[NicknameTable.owner] = owner.toKotlinUuid()
                it[NicknameTable.nickname] = nickname
                it[NicknameTable.plain] = plain
            }
        }
    }

    suspend fun clear(owner: UUID) {
        nicknameTransaction {
            NicknameTable.deleteWhere { NicknameTable.owner eq owner.toKotlinUuid() }
        }
    }
}
