package com.xalitoria.chat.logs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * The database the log tables are read from, set by whichever plugin owns the connection: a server writes the logs
 * and reads them for its own Plan, the proxy only reads them, for the Plan whose web server it hosts. Nothing here
 * opens a connection, as the platforms ship their own driver and pool.
 *
 * The prefix has to be set before the tables are first used: each table object reads it once.
 */
object LogStorage {
    /** Prepended to every table name, from `database.table-prefix`. */
    @Volatile
    var prefix = "xalichat_"

    @Volatile
    private var database: Database? = null

    /** Starts using [database], with every table named after [prefix]. */
    fun use(database: Database, prefix: String) {
        this.prefix = prefix
        this.database = database
    }

    /** Lets go of the database, for a plugin that is closing it. */
    fun clear() {
        database = null
    }

    fun tableName(name: String): String = prefix + name

    /** Runs [statement] in a suspending transaction on [Dispatchers.IO]; nested calls join the outer one. */
    suspend fun <T> transaction(statement: suspend JdbcTransaction.() -> T): T {
        val db = checkNotNull(database) { "The log database is not connected" }
        return withContext(Dispatchers.IO) { suspendTransaction(db = db, statement = statement) }
    }
}
