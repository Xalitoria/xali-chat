import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.remote
import mc.xalitoria.chat.paper.data.db.repository.LogFilter
import mc.xalitoria.chat.paper.data.db.repository.LogRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sets the schema up on a real MariaDB and a real MySQL server, which a plain SQLite run can't show. Set
 * `XALI_MARIADB_TEST` and `XALI_MYSQL_TEST` to `host:port` of servers with a root user, an empty `xalichat` database and
 * `XALI_DB_PASSWORD`, reachable over TLS; each is skipped when unset.
 */
class RemoteDatabase {
    private fun check(mariadb: Boolean, variable: String) {
        val [host, port] = System.getenv(variable)?.split(":") ?: return
        val mysql = MainConfig.Database.Mysql(host = host, port = port.toInt(), password = System.getenv("XALI_DB_PASSWORD") ?: "", useSsl = true, poolSize = 2)
        val source = HikariDataSource(HikariConfig().apply { remote(mariadb, mysql) })
        try {
            DatabaseManager.attach(source, MainConfig.Database(serverName = "survival"))
            assertTrue(DatabaseManager.serverId > 0)
            assertEquals(emptyList(), runBlocking { LogRepository.search(LogFilter(), 10) })
        } finally {
            DatabaseManager.close()
        }
    }

    @Test
    fun mariadb() = check(true, "XALI_MARIADB_TEST")

    @Test
    fun mysql() = check(false, "XALI_MYSQL_TEST")
}
