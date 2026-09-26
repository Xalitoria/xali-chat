package mc.xalitoria.chat.paper.util

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val PATTERN = Regex("^(\\d+)([smhd])$", RegexOption.IGNORE_CASE)

sealed interface DurationParseResult {
    /** null = permanent (no expiry) */
    data class Ok(val duration: Duration?) : DurationParseResult
    data object Invalid : DurationParseResult
}

/** Parses `10m`, `2h`, `1d`, `30s`; blank, `perm` or `permanent` mean no expiry. Anything else is [DurationParseResult.Invalid]. */
object DurationParser {
    fun parse(input: String?): DurationParseResult {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("perm", true) || trimmed.equals("permanent", true)) {
            return DurationParseResult.Ok(null)
        }
        val match = PATTERN.matchEntire(trimmed) ?: return DurationParseResult.Invalid
        val amount = match.groupValues[1].toLongOrNull() ?: return DurationParseResult.Invalid
        val duration = when (match.groupValues[2].lowercase()) {
            "s" -> amount.seconds
            "m" -> amount.minutes
            "h" -> amount.hours
            "d" -> amount.days
            else -> return DurationParseResult.Invalid
        }
        return DurationParseResult.Ok(duration)
    }
}
