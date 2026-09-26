package mc.xalitoria.chat.paper.data.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MainConfig(
    val debug: Boolean = false,
    val language: String = "en-us",
    @YamlComment(
        "{0} is the name, {1} the message. {vault_prefix}, {vault_suffix}, {vault_group} and",
        "PlaceholderAPI placeholders also expand.",
    )
    @SerialName("chat-format")
    val chatFormat: String = "<{0}> {1}",
    @YamlComment("Expands [item], [inv], [ender] and [ping] in chat into hoverable or clickable components. Applies on reload.")
    @SerialName("interactive-chat")
    val interactiveChat: Boolean = true,
    val database: Database = Database(),
    val nickname: Nickname = Nickname(),
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
    data class Nickname(
        @YamlComment("Max visible characters (formatting tags don't count); clamped to the 64 the database stores.")
        @SerialName("max-length")
        val maxLength: Int = 16,
        @YamlComment("Reject nicknames already used by another player's nickname or real name.")
        val unique: Boolean = true,
    )

    @Serializable
    data class Database(
        val type: String = "sqlite",
        @YamlComment("Prepended to every table name: letters, digits and underscores. Needs a restart; a new prefix means new, empty tables.")
        @SerialName("table-prefix")
        val tablePrefix: String = "xalichat_",
        @YamlComment("This server's row in the servers table that log rows link to. Use a different name on each server sharing a database.")
        @SerialName("server-name")
        val serverName: String = "server",
        val sqlite: Sqlite = Sqlite(),
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
