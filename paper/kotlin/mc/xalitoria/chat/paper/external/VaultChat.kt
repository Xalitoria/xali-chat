package mc.xalitoria.chat.paper.external

import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Vault-family chat metadata: VaultUnlocked's API first, then classic Vault's. Each API is only
 * touched after checking its class exists, and providers register late, so it's looked up per call.
 */
object VaultChat {
    data class Info(val prefix: String, val suffix: String, val group: String)

    private val unlockedPresent = classExists("net.milkbowl.vault2.chat.Chat")
    private val classicPresent = classExists("net.milkbowl.vault.chat.Chat")

    /** `null` when no Vault-family chat provider is installed and enabled. */
    fun info(player: Player): Info? =
        (if (unlockedPresent) Unlocked.info(player) else null)
            ?: (if (classicPresent) Classic.info(player) else null)

    private fun classExists(name: String): Boolean =
        runCatching { Class.forName(name, false, VaultChat::class.java.classLoader) }.isSuccess

    private object Unlocked {
        fun info(player: Player): Info? {
            val chat = Bukkit.getServicesManager().load(net.milkbowl.vault2.chat.Chat::class.java)
                ?.takeIf { it.isEnabled } ?: return null
            return Info(
                chat.getPlayerPrefix(player).orEmpty(),
                chat.getPlayerSuffix(player).orEmpty(),
                chat.getPrimaryGroup(player).orEmpty(),
            )
        }
    }

    // VaultUnlocked deprecates the classic API in favor of vault2, but classic Vault only has this one.
    @Suppress("DEPRECATION")
    private object Classic {
        fun info(player: Player): Info? {
            val chat = Bukkit.getServicesManager().load(net.milkbowl.vault.chat.Chat::class.java)
                ?.takeIf { it.isEnabled } ?: return null
            return Info(
                chat.getPlayerPrefix(player).orEmpty(),
                chat.getPlayerSuffix(player).orEmpty(),
                chat.getPrimaryGroup(player).orEmpty(),
            )
        }
    }
}
