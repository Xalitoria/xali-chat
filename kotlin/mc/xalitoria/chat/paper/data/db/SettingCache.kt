package mc.xalitoria.chat.paper.data.db

import mc.xalitoria.chat.paper.data.db.tables.Setting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Settings by player, loaded on join and written through by their commands; everything else only reads this. */
object SettingCache {
    private val cache = ConcurrentHashMap<UUID, Map<Setting, String>>()

    fun get(owner: UUID, setting: Setting): String? = cache[owner]?.get(setting)

    fun isEnabled(owner: UUID, setting: Setting): Boolean = get(owner, setting) == "true"

    fun load(owner: UUID, settings: Map<Setting, String>) {
        cache[owner] = settings
    }

    fun evict(owner: UUID) {
        cache.remove(owner)
    }

    /** Stores [value], or removes the setting when it is null. */
    fun set(owner: UUID, setting: Setting, value: String?) {
        // compute() is atomic per key, so concurrent sets of different settings can't lose each other.
        cache.compute(owner) { _, existing ->
            val settings = existing.orEmpty()
            if (value == null) settings - setting else settings + (setting to value)
        }
    }
}
