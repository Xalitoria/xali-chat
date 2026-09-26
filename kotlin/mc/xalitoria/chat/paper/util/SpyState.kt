package mc.xalitoria.chat.paper.util

import mc.xalitoria.util.kotlin.toggle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who has commandspy/socialspy on. Not persisted; cleared on quit. */
object SpyState {
    val commandSpies: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val socialSpies: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun toggleCommandSpy(uuid: UUID): Boolean = commandSpies.toggle(uuid)

    fun toggleSocialSpy(uuid: UUID): Boolean = socialSpies.toggle(uuid)
}
