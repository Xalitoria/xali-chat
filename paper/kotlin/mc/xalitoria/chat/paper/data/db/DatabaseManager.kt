package mc.xalitoria.chat.paper.data.db

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.db.tables.ChatLogTable
import mc.xalitoria.chat.paper.data.db.tables.CommandLogTable
import mc.xalitoria.chat.paper.data.db.tables.IgnoreTable
import mc.xalitoria.chat.paper.data.db.tables.MessageLogTable
import mc.xalitoria.chat.paper.data.db.repository.ServerRepository
import mc.xalitoria.chat.paper.data.db.tables.NicknameTable
import mc.xalitoria.chat.paper.data.db.tables.ServerTable
import mc.xalitoria.chat.paper.data.db.tables.SettingTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/** HikariCP pool and Exposed [Database] for ignore, setting, nickname and log storage. */
object DatabaseManager {
    lateinit var dataSource: HikariDataSource
        private set
    lateinit var database: Database
        private set

    /** Where nicknames live: [database], or a local SQLite file when the main one is shared and `nickname.remote` is off. */
    lateinit var nicknameDatabase: Database
        private set
    private var localNicknames: HikariDataSource? = null

    var tablePrefix = "xalichat_"
        private set

    /** This server's name, from `database.server-name`. */
    var serverName = ""
        private set

    /** This server's id in [ServerTable]; set once the schema is ready. */
    var serverId = 0
        private set

    private val TABLE_PREFIX = Regex("[A-Za-z0-9_]*")

    fun tableName(name: String): String = tablePrefix + name

    fun connect(plugin: XaliChat, config: MainConfig.Database, nickname: MainConfig.Nickname = MainConfig.Nickname()) {
        val hikariConfig = HikariConfig()
        val type = Type.valueOf(config.type.uppercase())
        when (type) {
            MARIADB, MYSQL -> hikariConfig.remote(type == Type.MARIADB, config.mysql)
            else -> hikariConfig.sqlite(plugin.dataFolder.resolve(config.sqlite.filename))
        }
        hikariConfig.poolName = "XaliChat-DB"

        // A shared database only holds nicknames when asked to; otherwise they stay in a local file.
        val local = if (type != Type.SQLITE && !nickname.remote) {
            HikariConfig().apply { sqlite(plugin.dataFolder.resolve(LOCAL_NICKNAMES)); poolName = "XaliChat-Nicknames" }
                .let(::HikariDataSource)
        } else null
        attach(HikariDataSource(hikariConfig), config, local)
    }

    /** Connects to a MariaDB server with its own driver or to a MySQL one with Connector/J. */
    internal fun HikariConfig.remote(mariadb: Boolean, mysql: MainConfig.Database.Mysql) {
        // Connector/J asks a MariaDB server for INFORMATION_SCHEMA columns it doesn't have ("Unknown column
        // 'RESERVED'") and fails before the schema is read, so each server gets its own driver.
        if (mariadb) {
            jdbcUrl = "jdbc:mariadb://${mysql.host}:${mysql.port}/${mysql.database}" +
                "?sslMode=${if (mysql.useSsl) "trust" else "disable"}"
            driverClassName = "org.mariadb.jdbc.Driver"
        } else {
            jdbcUrl = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}?useSSL=${mysql.useSsl}"
            driverClassName = "com.mysql.cj.jdbc.Driver"
        }
        username = mysql.username
        password = mysql.password
        maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
    }

    private fun HikariConfig.sqlite(file: File) {
        file.parentFile.mkdirs()
        jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
        driverClassName = "org.sqlite.JDBC"
        // Single-writer: SQLite does not support real concurrent writes.
        maximumPoolSize = 1
    }

    /**
     * Sets up the schema and this server's row on [source]. The prefix is set first, as table objects read it once.
     * Nicknames go to [localNicknames] when given, otherwise to [source].
     */
    internal fun attach(source: HikariDataSource, config: MainConfig.Database, localNicknames: HikariDataSource? = null) {
        dataSource = source
        require(TABLE_PREFIX.matches(config.tablePrefix)) { "database.table-prefix may only contain letters, digits and underscores" }
        require(config.serverName.isNotBlank() && config.serverName.length <= 64) { "database.server-name must be 1-64 characters" }
        tablePrefix = config.tablePrefix
        serverName = config.serverName
        database = Database.connect(source)
        this.localNicknames = localNicknames
        nicknameDatabase = localNicknames?.let { Database.connect(it) } ?: database
        debug("Attaching database: prefix='{0}', server='{1}'", tablePrefix, serverName)

        // SchemaUtils.createMissingTablesAndColumns is deprecated in Exposed 1.0 in favor of
        // generating the required DDL up front and running it ourselves.
        transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                *listOfNotNull(ServerTable, IgnoreTable, SettingTable, NicknameTable.takeIf { localNicknames == null }, ChatLogTable, CommandLogTable, MessageLogTable).toTypedArray(),
                withLogs = false,
            ).onEach { debug("Schema statement: {0}", it) }.forEach { exec(it) }
            serverId = ServerRepository.register(config.serverName)
            debug("Schema ready; this server is id {0}", serverId)
        }
        if (localNicknames != null) transaction(nicknameDatabase) {
            MigrationUtils.statementsRequiredForDatabaseMigration(NicknameTable, withLogs = false)
                .onEach { debug("Nickname schema statement: {0}", it) }.forEach { exec(it) }
            debug("Nicknames are kept locally in {0}", LOCAL_NICKNAMES)
        }
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
        localNicknames?.close()
        localNicknames = null
    }

    /** Runs [statement] in a suspending transaction on [Dispatchers.IO]; nested calls join the outer one. */
    suspend fun <T> dbTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = database, statement = statement) }

    /** Nickname rows in [nicknameDatabase], which is [database] unless nicknames are kept in a local file. */
    suspend fun <T> nicknameTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = nicknameDatabase, statement = statement) }

    private const val LOCAL_NICKNAMES = "nicknames.db"

    enum class Type() {
        SQLITE,
        MARIADB,
        MYSQL;
    }
}
