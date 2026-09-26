package mc.xalitoria.chat.paper.data.db

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.db.tables.ChatLogTable
import mc.xalitoria.chat.paper.data.db.tables.CommandLogTable
import mc.xalitoria.chat.paper.data.db.tables.IgnoreTable
import mc.xalitoria.chat.paper.data.db.repository.ServerRepository
import mc.xalitoria.chat.paper.data.db.tables.NicknameTable
import mc.xalitoria.chat.paper.data.db.tables.ServerTable
import mc.xalitoria.chat.paper.data.db.tables.SettingTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun connect(plugin: XaliChat, config: MainConfig.Database) {
        val hikariConfig = HikariConfig()

        when (config.type.lowercase()) {
            "mysql", "mariadb" -> {
                val mysql = config.mysql
                hikariConfig.jdbcUrl =
                    "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}?useSSL=${mysql.useSsl}"
                hikariConfig.username = mysql.username
                hikariConfig.password = mysql.password
                hikariConfig.driverClassName = "com.mysql.cj.jdbc.Driver"
                hikariConfig.maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
            }
            else -> {
                val dataFolder = plugin.dataFolder
                if (!dataFolder.exists()) dataFolder.mkdirs()
                val dbFile = dataFolder.resolve(config.sqlite.filename)
                hikariConfig.jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                hikariConfig.driverClassName = "org.sqlite.JDBC"
                // Single-writer: SQLite does not support real concurrent writes.
                hikariConfig.maximumPoolSize = 1
            }
        }
        hikariConfig.poolName = "XaliChat-DB"

        attach(HikariDataSource(hikariConfig), config)
    }

    /** Sets up the schema and this server's row on [source]. The prefix is set first, as table objects read it once. */
    internal fun attach(source: HikariDataSource, config: MainConfig.Database) {
        dataSource = source
        require(TABLE_PREFIX.matches(config.tablePrefix)) { "database.table-prefix may only contain letters, digits and underscores" }
        require(config.serverName.isNotBlank() && config.serverName.length <= 64) { "database.server-name must be 1-64 characters" }
        tablePrefix = config.tablePrefix
        serverName = config.serverName
        database = Database.connect(source)
        debug("Attaching database: prefix='{0}', server='{1}'", tablePrefix, serverName)

        // SchemaUtils.createMissingTablesAndColumns is deprecated in Exposed 1.0 in favor of
        // generating the required DDL up front and running it ourselves.
        transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                ServerTable, IgnoreTable, SettingTable, NicknameTable, ChatLogTable, CommandLogTable, withLogs = false,
            ).onEach { debug("Schema statement: {0}", it) }.forEach { exec(it) }
            serverId = ServerRepository.register(config.serverName)
            debug("Schema ready; this server is id {0}", serverId)
        }
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    /** Runs [statement] in a suspending transaction on [Dispatchers.IO]; nested calls join the outer one. */
    suspend fun <T> dbTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = database, statement = statement) }
}
