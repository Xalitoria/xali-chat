package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.logs.ServerTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

object ServerRepository {
    /** The id for [name], created if missing. Call inside a transaction; safe if servers register at once. */
    fun register(name: String): Int {
        ServerTable.insertIgnore { it[ServerTable.name] = name }
        return ServerTable.selectAll().where { ServerTable.name eq name }.single()[ServerTable.id]
    }

    /** Records that [name] is running plugin [version] now. Call inside a transaction. */
    fun report(name: String, version: String) {
        ServerTable.update({ ServerTable.name eq name }) {
            it[ServerTable.version] = version.take(32)
            it[seen] = System.currentTimeMillis()
        }
    }
}
