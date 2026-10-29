package mc.xalitoria.chat.velocity

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.chat.announce.RedisSettings
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** `plugins/xalichat/config.yml` on the proxy. */
@Serializable
data class ProxyConfig(
    @YamlComment("Log what the relay does in more detail.")
    val debug: Boolean = false,
    @SerialName("tab-completion")
    @YamlComment("The player names every server tab-completes for /msg, taken from everyone on the proxy.")
    val tabCompletion: TabCompletion = TabCompletion(),
    @SerialName("cross-server-messages")
    @YamlComment("/msg and /reply between players on different servers.")
    val crossServerMessages: CrossServerMessages = CrossServerMessages(),
    @YamlComment("Where announcements are shared with the servers. The same host and prefix as theirs. Needs a restart.")
    val redis: RedisSettings = RedisSettings(),
) {
    @Serializable
    data class CrossServerMessages(
        @YamlComment("false stops them for every server. A server's own config.yml can switch them off for just that server.")
        val enable: Boolean = true,
        @YamlComment(
            "Servers that take part: names as in velocity.toml, with * and ? wildcards (skyblock-*).",
            "Empty means every server. A message needs both the sender's server and the receiver's to be listed.",
        )
        val servers: List<String> = [],
    )

    @Serializable
    data class TabCompletion(
        @YamlComment("false stops sending the player list.")
        val enable: Boolean = true,
        @YamlComment("Milliseconds to wait after a join, leave or vanish before sending, so a burst of them sends one list.")
        val debounce: Long = 500,
    )

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))

        /**
         * Reads [file], then writes it back so a first start has the defaults in it and an older file gains new keys.
         * A file that doesn't parse is left as it is, and the defaults are used.
         */
        fun load(file: Path): ProxyConfig {
            val config = try {
                if (file.exists()) yaml.decodeFromString(serializer(), file.readText()) else ProxyConfig()
            } catch (e: Exception) {
                throw IllegalStateException("config.yml doesn't parse, so the defaults are used", e)
            }
            file.writeText(yaml.encodeToString(serializer(), config))
            return config
        }
    }
}
