package mc.xalitoria.chat.paper.data.log

import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Reads `CHATLOG|` lines back from `logs/`: `latest.log`, then rotated `*.log.gz` newest-first.
 * Blocking I/O capped at [HARD_LIMIT] matches; call off the main thread.
 */
object ChatLogReader {
    private const val MARKER = "CHATLOG|"
    const val HARD_LIMIT = 500

    fun search(
        player: String? = null,
        playerId: UUID? = null,
        sinceMillis: Long? = null,
        textFilter: String? = null,
        limit: Int = HARD_LIMIT,
    ): List<ChatLogEntry> =
        entries()
            .filter { player == null || it.uniqueId == playerId || it.username.equals(player, ignoreCase = true) }
            .filter { sinceMillis == null || it.timestampMillis >= sinceMillis }
            .filter { textFilter == null || it.message.contains(textFilter, ignoreCase = true) }
            .take(limit)
            .toList()

    private fun entries(): Sequence<ChatLogEntry> = sequence {
        val logsDir = File("logs")
        if (!logsDir.isDirectory) return@sequence

        val latest = logsDir.resolve("latest.log")
        if (latest.isFile) yieldAll(parseLines(latest.readLines().asReversed()))

        val rotated = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log.gz") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        for (file in rotated) {
            val lines = runCatching {
                GZIPInputStream(file.inputStream()).bufferedReader().use { it.readLines() }
            }.getOrNull() ?: continue
            yieldAll(parseLines(lines.asReversed()))
        }
    }

    private fun parseLines(lines: List<String>): Sequence<ChatLogEntry> =
        lines.asSequence().mapNotNull(::parseLine)

    private fun parseLine(line: String): ChatLogEntry? {
        val idx = line.indexOf(MARKER)
        if (idx < 0) return null
        val parts = line.substring(idx + MARKER.length).split("|", limit = 4)
        if (parts.size < 4) return null
        return try {
            ChatLogEntry(
                timestampMillis = parts[0].toLong(),
                uniqueId = UUID.fromString(parts[1]),
                username = parts[2],
                message = String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
