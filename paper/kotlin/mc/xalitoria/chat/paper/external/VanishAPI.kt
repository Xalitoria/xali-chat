package mc.xalitoria.chat.paper.external

import mc.xalitoria.chat.paper.XaliChat.Companion.INSTANCE
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import de.myzelyam.api.vanish.VanishAPI as SuperVanishApi

object VanishAPI {
    private var installed = false
    private var _isVanished: Player.() -> Boolean = { isInvisible }
    private var _canSee: (viewer: Player, target: Player) -> Boolean = { _, _ -> true }

    fun find(): Boolean {
        val pm = INSTANCE.server.pluginManager
        val support = ["SuperVanish", "PremiumVanish"].find {
            pm.getPlugin(it) != null
        } ?: return false
        _isVanished = { SuperVanishApi.isInvisible(this) }
        _canSee = { viewer, target -> SuperVanishApi.canSee(viewer, target) }
        installed = true
        INSTANCE.logger.info("$support support found")
        return true
    }

    fun isVanished(player: Player): Boolean = _isVanished(player)

    /**
     * Whether [viewer] may know [target] is online. Use this, not [isVanished], for anything
     * shown to a specific sender.
     */
    fun canSee(viewer: CommandSender, target: Player): Boolean =
        (viewer !is Player) || (viewer.canSee(target) && _canSee(viewer, target))

    /**
     * Whether [viewer] may see vanished players at all (`pv.see` or `sv.see`), for a message that reaches a player on
     * another server, where [canSee] can't be asked about someone who isn't here. Always true without a vanish plugin.
     */
    fun seesVanished(viewer: CommandSender): Boolean =
        !installed || viewer !is Player || viewer.hasPermission("pv.see") || viewer.hasPermission("sv.see")
}
