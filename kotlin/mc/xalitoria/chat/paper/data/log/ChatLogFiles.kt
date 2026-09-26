package mc.xalitoria.chat.paper.data.log

import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import mc.xalitoria.util.serial.UniqueId
import kotlin.io.path.bufferedWriter
import kotlin.io.path.exists
import kotlin.io.path.useLines

/**
 * Plaintext chat log exports, one entry per line: `yyyy-MM-dd HH:mm:ss | <uuid> | <username> | <message>`.
 * Files live in a single `exports` folder; names are restricted so they cannot point outside of it.
 */
object ChatLogFiles {
    private const val SEPARATOR = " | "
    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private val NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}")

    /** The file for [name] inside [folder] (`.txt` is added when missing), or `null` if [name] is not a plain file name. */
    fun resolve(folder: Path, name: String): Path? {
        if (!NAME.matches(name)) return null
        return folder.resolve(if ('.' in name) name else "$name.txt")
    }

    fun write(file: Path, entries: List<ChatLogEntry>) {
        Files.createDirectories(file.parent)
        val format = SimpleDateFormat(DATE_PATTERN)
        file.bufferedWriter().use { out ->
            for (entry in entries) {
                val message = entry.message.replace('\n', ' ')
                out.write(listOf(format.format(Date(entry.timestampMillis)), entry.uniqueId, entry.username, message).joinToString(SEPARATOR))
                out.newLine()
            }
        }
    }

    /** Entries of an export in file order; lines that do not match the export format are skipped. */
    fun read(file: Path): List<ChatLogEntry>? {
        if (!file.exists()) return null
        val format = SimpleDateFormat(DATE_PATTERN)
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(SEPARATOR, limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val time = runCatching { format.parse(parts[0]).time }.getOrNull() ?: return@mapNotNull null
                val id = runCatching { UniqueId.fromString(parts[1]) }.getOrNull() ?: return@mapNotNull null
                ChatLogEntry(time, id, parts[2], parts[3])
            }.toList()
        }
    }
}
