package mc.xalitoria.chat.paper.data.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import mc.xalitoria.chat.announce.RedisSettings

@Serializable
data class MainConfig(
    val debug: Boolean = false,
    val language: String = "en-us",
    @YamlComment(
        "{0} is the name, {1} the message.",
        "{vault_prefix}, {vault_suffix}, {vault_group} and PlaceholderAPI placeholders are supported.",
    )
    @SerialName("chat-format")
    val chatFormat: String = "<{0}> {1}",
    @YamlComment("Expands [item], [inv], [ender] and [ping] in chat into hoverable or clickable components. Applies on reload.")
    @SerialName("interactive-chat")
    val interactiveChat: Boolean = true,
    @YamlComment(
        "ProtocolLib packet listeners: chat signing, colored tab list/scoreboard/signs/etc. Applies on reload.",
        "Disable if a ProtocolLib conflict or bug needs ruling out without removing the plugin.",
    )
    val protocolor: Boolean = true,
    @YamlComment(
        "Lets /msg and /reply reach players on other servers behind the proxy, and this server receive theirs.",
        "false keeps private messages to this server. The proxy's config.yml can switch it off for servers too.",
    )
    @SerialName("cross-server-messages")
    val crossServerMessages: Boolean = true,
    val database: Database = Database(),
    @YamlComment("Where announcements are shared with the other servers and the proxy. Needs a restart.")
    val redis: RedisSettings = RedisSettings(),
    val nickname: Nickname = Nickname(),
    @YamlComment("Shows the chat and command log on Plan's player and server pages when Plan is installed. Needs a restart.")
    val plan: Plan = Plan(),
    @YamlComment(
        "Where chat and commands are recorded: the database when 'database' is true, otherwise (or",
        "if a write fails) the server log as CHATLOG|... / CMDLOG|... lines.",
    )
    val logging: Logging = Logging(),
) {
    @Serializable
    data class Logging(
        val chat: Chat = Chat(),
        val commands: Commands = Commands(),
    ) {
        @Serializable
        data class Chat(
            val database: Boolean = true,
        )

        @Serializable
        data class Commands(
            val database: Boolean = true,
            @YamlComment("Labels never recorded (case-insensitive, no namespace:): auth commands and DMs by default.")
            @SerialName("ignored-commands")
            val ignoredCommands: List<String> = [
                "login", "l", "register", "reg", "unregister", "changepassword", "changepass", "2fa", "totp",
                "xmessage", "xmsg", "msg", "tell", "w", "whisper", "m", "r", "reply",
            ],
        ) {
            @Transient
            private val ignored = ignoredCommands.mapTo(HashSet()) { it.lowercase() }

            fun isIgnored(label: String): Boolean = label.lowercase() in ignored
        }
    }

    @Serializable
    data class Plan(
        val enable: Boolean = true,
        @YamlComment("Chat messages on the player pages. Needs logging.chat.database.")
        val chat: Boolean = true,
        @YamlComment("Commands on the player pages. Needs logging.commands.database.")
        val commands: Boolean = true,
        @YamlComment("How many of a player's latest messages and commands each table lists. Plan shows 50 characters of each.")
        val rows: Int = 25,
    )

    @Serializable
    data class Nickname(
        @YamlComment("Turns /nickname and the stored nicknames off. Command registration applies on restart.")
        val enable: Boolean = true,
        @YamlComment("sync to the database if using anything but sqlite", "false keeps nicknames in a local nicknames.db next to this config. Needs a restart.")
        val remote: Boolean = false,
        @YamlComment("Max visible characters (formatting tags don't count); clamped to the 64 the database stores.")
        @SerialName("max-length")
        val maxLength: Int = 16,
        @YamlComment("Reject nicknames already used by another player's nickname or real name.")
        val unique: Boolean = true,
    )

    @Serializable
    data class Database(
        @YamlComment("Database types: mariadb or mysql, sqlite")
        val type: String = "sqlite",
        @YamlComment("Prepended to every table name: letters, digits and underscores. Needs a restart; a new prefix means new, empty tables.")
        @SerialName("table-prefix")
        val tablePrefix: String = "xalichat_",
        @YamlComment("This server's row in the servers table that log rows link to. Use a different name on each server sharing a database.")
        @SerialName("server-name")
        val serverName: String = "server",
        val sqlite: Sqlite = Sqlite(),
        @YamlComment("MySQL/MariaDB connection settings")
        val mysql: Mysql = Mysql(),
    ) {
        @Serializable
        data class Sqlite(
            val filename: String = "xalichat.db",
        )

        @Serializable
        data class Mysql(
            val host: String = "localhost",
            val port: Int = 3306,
            val database: String = "xalichat",
            val username: String = "root",
            val password: String = "",
            @SerialName("use-ssl")
            val useSsl: Boolean = false,
            @SerialName("pool-size")
            val poolSize: Int = 10,
        )
    }
}
