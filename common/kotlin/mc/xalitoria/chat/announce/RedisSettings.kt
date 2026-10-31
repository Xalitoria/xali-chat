package mc.xalitoria.chat.announce

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `redis` section of the server's and the proxy's `config.yml`: where announcements are shared and log writes are announced. */
@Serializable
data class RedisSettings(
    @YamlComment(
        "Shares announcements between the servers and the proxy through Redis, so a server gets them on startup",
        "without a player online, and tells them when logs were written (log-pings). Every server and the proxy",
        "need the same host and prefix. false keeps them local.",
    )
    val enable: Boolean = false,
    val host: String = "localhost",
    val port: Int = 6379,
    @YamlComment("Leave empty when Redis has no ACL users.")
    val username: String = "",
    val password: String = "",
    val ssl: Boolean = false,
    @YamlComment("Starts every key and channel this plugin uses in Redis; change it to keep two networks apart.")
    val prefix: String = "xalichat",
    @YamlComment(
        "Tells the other servers and the proxy when chat, commands or messages were written to the database (at most once",
        "a second), so Plan's log page only reads the database when there is something new. Needs enable.",
    )
    @SerialName("log-pings")
    val logPings: Boolean = true,
)
