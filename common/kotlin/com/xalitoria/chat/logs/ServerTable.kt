package com.xalitoria.chat.logs

import org.jetbrains.exposed.v1.core.Table

/** The servers sharing this database; log rows link here by id. */
object ServerTable : Table(LogStorage.tableName("servers")) {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 64).uniqueIndex()

    /** The plugin version the server last reported, and when (milliseconds); null for a server on a release that never did. */
    val version = varchar("version", 32).nullable()
    val seen = long("seen").nullable()

    override val primaryKey = PrimaryKey(id)
}
