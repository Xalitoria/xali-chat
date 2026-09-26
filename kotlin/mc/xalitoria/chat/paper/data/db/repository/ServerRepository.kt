package mc.xalitoria.chat.paper.data.db.repository

import mc.xalitoria.chat.paper.data.db.tables.ServerTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

object ServerRepository {
    /** The id for [name], created if missing. Call inside a transaction; safe if servers register at once. */
    fun register(name: String): Int {
        ServerTable.insertIgnore { it[ServerTable.name] = name }
        return ServerTable.selectAll().where { ServerTable.name eq name }.single()[ServerTable.id]
    }
}
