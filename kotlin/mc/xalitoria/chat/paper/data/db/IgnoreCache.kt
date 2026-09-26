package mc.xalitoria.chat.paper.data.db

import mc.xalitoria.chat.paper.data.constants.Permissions
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Ignore state by viewer, loaded on join and written through by `IgnoreCommand`; chat only reads this. */
object IgnoreCache {
    private val cache = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun isIgnoring(owner: UUID, ignored: UUID): Boolean = cache[owner]?.contains(ignored) == true

    /**
     * Whether [viewer] shouldn't see [sender]'s message: they ignore the sender, who lacks
     * [Permissions.BYP_MSG_IGNORE]. Pass [senderBypasses] to evaluate that once for many viewers.
     */
    fun hidesFrom(
        viewer: UUID,
        sender: Player,
        senderBypasses: Boolean = sender.hasPermission(Permissions.BYP_MSG_IGNORE),
    ): Boolean = !senderBypasses && isIgnoring(viewer, sender.uniqueId)

    fun load(owner: UUID, ignored: Set<UUID>) {
        cache[owner] = ConcurrentHashMap.newKeySet<UUID>().apply { addAll(ignored) }
    }

    fun evict(owner: UUID) {
        cache.remove(owner)
    }

    fun set(owner: UUID, ignored: UUID, isIgnoring: Boolean) {
        // compute() is atomic per key on ConcurrentHashMap, unlike getOrPut (get-then-put), which
        // can lose a concurrent set() for a different `ignored` target under the same owner.
        cache.compute(owner) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply { if (isIgnoring) add(ignored) else remove(ignored) }
        }
    }
}
