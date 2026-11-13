import com.xalitoria.chat.logs.LogFilter
import com.xalitoria.chat.logs.LogKind
import com.xalitoria.chat.logs.LogRepository
import com.xalitoria.util.config.DatabaseConfig
import com.xalitoria.util.config.DatabaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.remote
import mc.xalitoria.chat.paper.data.db.repository.ChatLogRepository
import mc.xalitoria.chat.paper.data.log.ChatLogEntry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sets the schema up on a real MariaDB and a real MySQL server, and runs the log page's queries on it, which a plain SQLite run can't show. Set
 * `XALI_MARIADB_TEST` and `XALI_MYSQL_TEST` to `host:port` of servers with a root user, an empty `xalichat` database and
 * `XALI_DB_PASSWORD`, reachable over TLS; each is skipped when unset.
 */
class RemoteDatabase {
    private fun check(mariadb: Boolean, variable: String) {
        val [host, port] = System.getenv(variable)?.split(":") ?: return
        val mysql = DatabaseConfig.Mysql(database = "xalichat", host = host, port = port.toInt(), password = System.getenv("XALI_DB_PASSWORD") ?: "", useSsl = true, poolSize = 2)
        val source = HikariDataSource(HikariConfig().apply { remote(if (mariadb) DatabaseType.MARIADB else DatabaseType.MYSQL, mysql) })
        try {
            DatabaseManager.attach(source, MainConfig.Database(serverName = "survival"))
            assertTrue(DatabaseManager.serverId > 0)
            assertEquals(emptyList(), runBlocking { LogRepository.search(LogFilter(), 10) })
            // The queries the log page relies on, which differ between SQL dialects: the timeline's grouping by
            // `timestamp - timestamp % size`, a LIKE with an escaped `%`, and the newest-first order.
            val hour = 3_600_000L
            val base = 1_700_000_000_000L / hour * hour
            val player = UUID.randomUUID()
            runBlocking {
                ChatLogRepository.insertAll(listOf(
                    ChatLogEntry(base + 1_000, player, "Steve", "50% off"),
                    ChatLogEntry(base + 2_000, player, "Steve", "plain"),
                    ChatLogEntry(base + 5 * hour, player, "Steve", "later"),
                ))
                assertEquals(listOf("later", "plain", "50% off"), LogRepository.search(LogFilter(), 10).map { it.text })
                assertEquals(listOf("50% off"), LogRepository.search(LogFilter(text = "50%"), 10).map { it.text })
                val histogram = LogRepository.histogram(LogFilter(), 3)!!
                assertEquals(base + 1_000, histogram.from)
                assertEquals(base + 5 * hour, histogram.to)
                assertEquals(3L, histogram.counts.values.sumOf { it[LogKind.CHAT] ?: 0L })
                assertEquals(2L, histogram.counts[base]?.get(LogKind.CHAT))
            }
        } finally {
            DatabaseManager.close()
        }
    }

    @Test
    fun mariadb() = check(true, "XALI_MARIADB_TEST")

    @Test
    fun mysql() = check(false, "XALI_MYSQL_TEST")
}
