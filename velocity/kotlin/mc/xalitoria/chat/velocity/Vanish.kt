package mc.xalitoria.chat.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.Player
import de.myzelyam.api.vanish.VelocityPlayerHideEvent
import de.myzelyam.api.vanish.VelocityPlayerShowEvent
import de.myzelyam.api.vanish.VelocityVanishAPI
import java.util.UUID

/**
 * PremiumVanish's Velocity API. Everything that touches it is in this class, which is only created when PremiumVanish
 * is loaded, so the proxy never looks for its classes without it. [changed] is called when someone vanishes or shows.
 */
internal class Vanish(private val changed: () -> Unit) {
    fun invisible(): Set<UUID> = VelocityVanishAPI.getInvisiblePlayers().orEmpty().toSet()

    fun canSee(viewer: Player, target: Player): Boolean = VelocityVanishAPI.canSee(viewer, target)

    @Subscribe
    fun onHide(event: VelocityPlayerHideEvent) = changed()

    @Subscribe
    fun onShow(event: VelocityPlayerShowEvent) = changed()
}
