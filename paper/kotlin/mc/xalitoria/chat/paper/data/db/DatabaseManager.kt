package mc.xalitoria.chat.paper.data.db

import com.xalitoria.chat.logs.ChatLogTable
import com.xalitoria.chat.logs.CommandLogTable
import com.xalitoria.chat.logs.LogStorage
import com.xalitoria.chat.logs.MessageLogTable
import com.xalitoria.chat.logs.ServerTable
import com.xalitoria.util.config.DatabaseConfig
import com.xalitoria.util.config.DatabaseType
import com.xalitoria.util.config.databaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.db.repository.ServerRepository
import mc.xalitoria.chat.paper.data.db.tables.IgnoreTable
import mc.xalitoria.chat.paper.data.db.tables.NicknameTable
import mc.xalitoria.chat.paper.data.db.tables.SettingTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.io.File

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

    fun tableName(name: String): String = tablePrefix + name

    fun connect(plugin: XaliChat, config: MainConfig.Database, nickname: MainConfig.Nickname = MainConfig.Nickname()) {
        val hikariConfig = HikariConfig()
        val type = config.databaseType
        if (type.isRemote) hikariConfig.remote(type, config.mysql) else hikariConfig.sqlite(plugin.dataFolder.resolve(config.sqlite.filename))
        hikariConfig.poolName = "XaliChat-DB"

        // A shared database only holds nicknames when asked to; otherwise they stay in a local file.
        val local = if (type.isRemote && !nickname.remote) {
            HikariConfig().apply { sqlite(plugin.dataFolder.resolve(LOCAL_NICKNAMES)); poolName = "XaliChat-Nicknames" }
                .let(::HikariDataSource)
        } else null
        attach(HikariDataSource(hikariConfig), config, local, plugin.pluginMeta.version)
    }

    /** Connects to a MariaDB server with its own driver or to a MySQL one with Connector/J (see [DatabaseConfig.Mysql.jdbcUrl]). */
    internal fun HikariConfig.remote(type: DatabaseType, mysql: DatabaseConfig.Mysql) {
        jdbcUrl = mysql.jdbcUrl(type)
        driverClassName = mysql.driverClass(type)
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
    internal fun attach(source: HikariDataSource, config: MainConfig.Database, localNicknames: HikariDataSource? = null, version: String? = null) {
        dataSource = source
        require(config.serverName.isNotBlank() && config.serverName.length <= 64) { "database.server-name must be 1-64 characters" }
        tablePrefix = DatabaseConfig.checkPrefix(config.tablePrefix)
        serverName = config.serverName
        reportedVersion = version
        database = Database.connect(source)
        LogStorage.use(database, tablePrefix)
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
            version?.let { ServerRepository.report(config.serverName, it) }
            debug("Schema ready; this server is id {0}", serverId)
        }
        if (localNicknames != null) transaction(nicknameDatabase) {
            MigrationUtils.statementsRequiredForDatabaseMigration(NicknameTable, withLogs = false)
                .onEach { debug("Nickname schema statement: {0}", it) }.forEach { exec(it) }
            debug("Nicknames are kept locally in {0}", LOCAL_NICKNAMES)
        }
    }

    /** The plugin version this server tells the database it runs, if it is to. */
    private var reportedVersion: String? = null

    /** Tells the database this server is still running, so a page can tell the servers in use from those long gone. */
    suspend fun heartbeat() {
        val version = reportedVersion ?: return
        dbTransaction { ServerRepository.report(serverName, version) }
    }

    fun close() {
        LogStorage.clear()
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
}
