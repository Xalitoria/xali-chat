package mc.xalitoria.chat.announce

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mc.xalitoria.chat.protocol.Entry
import java.nio.file.Path
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
 */
class AnnouncementStore(private val file: Path, private val stateFile: Path) {
    /** [hash] is the content as of [modified]; null marks a removal. */
    @Serializable
    private class Stamp(val hash: String? = null, val modified: Long = 0)

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val fileSerializer = MapSerializer(String.serializer(), Announcement.serializer())
    private val stateSerializer = MapSerializer(String.serializer(), Stamp.serializer())
    private val stamps = HashMap<String, Stamp>()

    /** What is loaded now. A new map for every change, so it is safe to read from any thread. */
    @Volatile
    var announcements: Map<String, Announcement> = emptyMap()
        private set

    init {
        if (stateFile.exists()) stamps.putAll(json.decodeFromString(stateSerializer, stateFile.readText()))
    }

    /**
     * Reads the file again and returns what changed since the last time it was known: new or edited keys, and keys
     * that are gone, each stamped with when the file was saved. Throws, changing nothing, if the file doesn't parse.
     */
    @Synchronized
    fun scan(): List<Entry> {
        val text = if (file.exists()) file.readText() else ""
        val loaded = if (text.isBlank()) emptyMap() else yaml.decodeFromString(fileSerializer, text)
        val saved = if (file.exists()) file.getLastModifiedTime().toMillis() else System.currentTimeMillis()

        val changes = ArrayList<Entry>()
        loaded.forEach { (key = key, announcement = value) ->
            val hash = hash(announcement)
            if (stamps[key]?.hash != hash) {
                stamps[key] = Stamp(hash, saved)
                changes += Entry(key, saved, json.encodeToString(Announcement.serializer(), announcement))
            }
        }
        stamps.toMap().forEach { (key = key, stamp = value) ->
            if (stamp.hash != null && key !in loaded) {
                stamps[key] = Stamp(null, saved)
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
        val held = stamps[entry.key]?.modified ?: 0
        if (if (entry.json == null) entry.modified < held else entry.modified <= held) return false

        if (entry.json == null) {
            announcements = announcements - entry.key
            stamps[entry.key] = Stamp(null, entry.modified)
        } else {
            val announcement = try {
                json.decodeFromString(Announcement.serializer(), entry.json)
            } catch (e: Exception) {
                return false
            }
            announcements = announcements + (entry.key to announcement)
            stamps[entry.key] = Stamp(hash(announcement), entry.modified)
        }
        return true
    }

    /** Writes what is loaded to the file, which drops its comments, and keeps the state in step with it. */
    @Synchronized
    fun write() {
        file.parent?.createDirectories()
        file.writeText(yaml.encodeToString(fileSerializer, announcements))
        saveState()
    }

    /** The keys held and when each was saved, for asking the proxy what is newer. */
    @Synchronized
    fun known(): Map<String, Long> = announcements.keys.associateWith { stamps[it]?.modified ?: 0 }

    /** [key] as it is held now, or as the removal that replaced it; null for a key never seen. */
    @Synchronized
    fun entry(key: String): Entry? {
        val stamp = stamps[key] ?: return null
        val announcement = announcements[key] ?: return Entry(key, stamp.modified, null)
        return Entry(key, stamp.modified, json.encodeToString(Announcement.serializer(), announcement))
    }

    /**
     * What to tell [server], which holds [known]: every key that names it and is newer than its copy or missing from
     * it, plus a removal for each of its keys that is gone or no longer names it.
     */
    @Synchronized
    fun forServer(server: String, known: Map<String, Long>): List<Entry> {
        val newer = announcements.filter { it.value.appliesTo(server) }.keys
            .mapNotNull { entry(it) }
            .filter { it.modified > (known[it.key] ?: -1) }
        val removed = known.keys
            .filter { key -> announcements[key]?.appliesTo(server) != true }
            .mapNotNull { key -> stamps[key]?.let { Entry(key, it.modified, null) } }
        return newer + removed
    }

    private fun hash(announcement: Announcement): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(Announcement.serializer(), announcement).toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun saveState() {
        stateFile.parent?.createDirectories()
        stateFile.writeText(json.encodeToString(stateSerializer, stamps))
    }
}
