package mc.xalitoria.chat.announce

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mc.xalitoria.chat.protocol.Entry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * An `announcements.yml` and what is known about when each key last changed, kept in a state file beside it. The proxy
 * holds the whole set; a server holds only the keys that name it. Both sides run this, so a key means the same thing
 * everywhere: it has the time it was last saved, a changed key is told apart from an untouched one by its content (not
 * by comments or layout), and the newest save wins.
 *
 * A deleted key stays in the state as a removal with its own time, so an older copy can't bring it back.
 *
 * Only a file that parses is ever taken, and so synced. When a save doesn't, nothing changes, and the last text that
 * did parse, held in memory, is written beside it as `<file>.bak` so the working version isn't lost with the edit.
 */
class AnnouncementStore(private val file: Path, private val stateFile: Path) {
    /**
     * [hash] is the content as of [modified]; null marks a removal. A [pending] stamp is a save made here that the
     * shared set hasn't been told about: [modified] is then when the file was saved by this machine's clock, and
     * [settle] replaces it with the time the shared set gave it, so that stamps are only ever compared on one clock.
     */
    @Serializable
    private class Stamp(val hash: String? = null, val modified: Long = 0, val pending: Boolean = false)

    /** A save or removal made here that is waiting to be sent: [saved] is when, by this machine's clock; [json] is null for a removal. */
    class Pending internal constructor(val key: String, val saved: Long, val json: String?, internal val hash: String?)

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** What travels: no fields left at their defaults, which the receiver fills in again. [json] is the form that is hashed. */
    private val wire = Json { ignoreUnknownKeys = true }
    private val fileSerializer = MapSerializer(String.serializer(), Announcement.serializer())
    private val stateSerializer = MapSerializer(String.serializer(), Stamp.serializer())
    private val stamps = HashMap<String, Stamp>()

    /** The file's text as of the last time it parsed or was written here; what [backup] keeps. */
    private var lastGood: String? = null

    /** What is loaded now. A new map for every change, so it is safe to read from any thread. */
    @Volatile
    var announcements: Map<String, Announcement> = emptyMap()
        private set

    init {
        if (stateFile.exists()) stamps.putAll(json.decodeFromString(stateSerializer, stateFile.readText()))
    }

    /**
     * Reads the file again and returns what changed since the last time it was known: new or edited keys, and keys
     * that are gone, each stamped with when the file was saved. Throws, changing nothing, if the file doesn't parse
     * (after writing the last working text to `<file>.bak`).
     */
    @Synchronized
    fun scan(): List<Entry> {
        val text = if (file.exists()) file.readText() else ""
        val loaded = try {
            if (text.lines().all { it.isBlank() || it.trimStart().startsWith('#') }) emptyMap() else yaml.decodeFromString(fileSerializer, text)
        } catch (e: Exception) {
            backup()
            throw e
        }
        lastGood = text
        val saved = if (file.exists()) file.getLastModifiedTime().toMillis() else System.currentTimeMillis()

        val changes = ArrayList<Entry>()
        loaded.forEach { (key = key, announcement = value) ->
            val hash = hash(announcement)
            if (stamps[key]?.hash != hash) {
                stamps[key] = Stamp(hash, saved, pending = true)
                changes += Entry(key, saved, wire.encodeToString(Announcement.serializer(), announcement))
            }
        }
        stamps.toMap().forEach { (key = key, stamp = value) ->
            if (stamp.hash != null && key !in loaded) {
                stamps[key] = Stamp(null, saved, pending = true)
                changes += Entry(key, saved, null)
            }
        }
        announcements = loaded
        saveState()
        return changes
    }

    /**
     * Takes [entry] if it is newer than what is held (a removal also if it is just as new), in memory only: [write]
     * puts it in the file. Returns whether it was taken; an entry that doesn't parse is not.
     */
    @Synchronized
    fun apply(entry: Entry): Boolean {
        // A save not yet sent is stamped by this machine's clock; it is sent first, and then compared.
        if (stamps[entry.key]?.pending == true) return false
        val held = stamps[entry.key]?.modified ?: 0
        if (if (entry.json == null) entry.modified < held else entry.modified <= held) return false

        if (entry.json == null) {
            announcements = announcements - entry.key
            stamps[entry.key] = Stamp(null, entry.modified)
        } else {
            val announcement = try {
                wire.decodeFromString(Announcement.serializer(), entry.json)
            } catch (e: Exception) {
                return false
            }
            announcements = announcements + (entry.key to announcement)
            stamps[entry.key] = Stamp(hash(announcement), entry.modified)
        }
        return true
    }

    /** Whether the file exists. */
    fun exists(): Boolean = file.exists()

    /** Whether any key has been seen, saved or removed; false for a store that has never held anything. */
    @Synchronized
    fun hasHistory(): Boolean = stamps.isNotEmpty()

    /**
     * Writes what is loaded to the file, under [HEADER], and keeps the state in step with it. Comments written by hand
     * are dropped. With nothing loaded the file is the header alone, so there is a file to edit.
     *
     * Not if the file has been saved since it was last read or written here: that save is newer than this, and the
     * next [scan] takes it. Returns whether the file was written.
     */
    @Synchronized
    fun write(): Boolean {
        file.parent?.createDirectories()
        val seen = lastGood
        if (seen != null && file.exists() && file.readText() != seen) return false
        val body = if (announcements.isEmpty()) "" else yaml.encodeToString(fileSerializer, announcements)
        val text = HEADER + body
        // Beside the file and then renamed over it, so that nothing reading it ever sees half of it.
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        temporary.writeText(text)
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
        lastGood = text
        saveState()
        return true
    }

    /** Keeps the last working text as `<file>.bak`, from what is loaded if the file never parsed. */
    private fun backup() {
        val good = lastGood?.takeIf { it.isNotBlank() && announcements.isNotEmpty() } ?: announcements.takeIf { it.isNotEmpty() }?.let { yaml.encodeToString(fileSerializer, it) } ?: return
        file.resolveSibling(file.fileName.toString() + ".bak").writeText(good)
    }

    /** [key] as it is held now, or as the removal that replaced it; null for a key never seen. */
    @Synchronized
    fun entry(key: String): Entry? {
        val stamp = stamps[key] ?: return null
        val announcement = announcements[key] ?: return Entry(key, stamp.modified, null)
        return Entry(key, stamp.modified, wire.encodeToString(Announcement.serializer(), announcement))
    }

    /** Every key seen, as it is held now or as the removal that replaced it. */
    @Synchronized
    fun allEntries(): List<Entry> = stamps.keys.mapNotNull { entry(it) }

    /** [allEntries] without the saves still waiting to be sent: those the shared set can be given as they are. */
    @Synchronized
    fun settledEntries(): List<Entry> = stamps.filterValues { !it.pending }.keys.mapNotNull { entry(it) }

    /** The saves and removals made here that the shared set hasn't taken yet, oldest key first. */
    @Synchronized
    fun pending(): List<Pending> = stamps.filterValues { it.pending }.toSortedMap().map { (key = key, stamp = value) ->
        val announcement = announcements[key]
        Pending(key, stamp.modified, if (stamp.hash == null || announcement == null) null else wire.encodeToString(Announcement.serializer(), announcement), stamp.hash)
    }

    /**
     * Records what became of [pending]: the shared set stamped it [assigned], or turned it down for holding a newer
     * copy (0), which [apply] then takes from it. Does nothing for a key saved again in the meantime, which is still pending.
     */
    @Synchronized
    fun settle(pending: Pending, assigned: Long) {
        val stamp = stamps[pending.key] ?: return
        if (!stamp.pending || stamp.hash != pending.hash || stamp.modified != pending.saved) return
        stamps[pending.key] = Stamp(stamp.hash, assigned)
        saveState()
    }

    /**
     * Which of [master], the shared copies, a holder of [server] should [apply]: those that name it (all of them for
     * null, the proxy), a removal for each key it holds that no longer names it, and the removals as they come, for
     * the times to be known. An entry older than the holder's is left in: [apply] turns it down.
     */
    @Synchronized
    fun relevant(server: String?, master: Collection<Entry>): List<Entry> = master.mapNotNull { entry ->
        val held = stamps[entry.key]
        if (entry.json == null) return@mapNotNull entry.takeIf { entry.key in announcements || held == null || held.modified < entry.modified }
        val named = server == null || try {
            wire.decodeFromString(Announcement.serializer(), entry.json).appliesTo(server)
        } catch (e: Exception) {
            return@mapNotNull null
        }
        when {
            named -> entry
            entry.key in announcements && entry.modified >= (held?.modified ?: 0) -> Entry(entry.key, entry.modified, null)
            else -> null
        }
    }

    private fun hash(announcement: Announcement): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(Announcement.serializer(), announcement).toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun saveState() {
        stateFile.parent?.createDirectories()
        stateFile.writeText(json.encodeToString(stateSerializer, stamps))
    }

    private companion object {
        const val HEADER = """# Announcements: each key below is one announcement. With redis on, saved changes are shared with the other servers and
# the proxy, and each server keeps the keys that name it in `servers`. An empty file has none yet.
#
# "example":
#   enable: true
#   delay: 300
#   repeat: 1800
#   servers:
#   - "survival-*"
#   messages:
#   - "&6Hello, {name}!"
#   commands: []
"""
    }
}
