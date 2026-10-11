package mc.xalitoria.chat.paper.data.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.regex.PatternSyntaxException
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * The chat filter. Entries are patterns, not plain words: they match whole words, case-insensitively, whatever
 * look-alike characters are used (`@ss`, `f4ck`), and with or without separators between the words of a phrase.
 *
 * - `?`, `|` and `( )` work as in a regex (`beaners?`, `black(s|y)`; groups never capture).
 * - A space stands for any run of separators, or none: `bang bros` also matches `bangbros` and `bang-bros`.
 * - A letter written twice stands for that letter repeated: `ss` matches the `ss` of `ass`, `asss` and `a$$` alike.
 * - `\regex\` between backslashes is used as is, with no look-alikes or separators.
 */
@Serializable
data class FilterConfig(
    val enabled: Boolean = true,
    @YamlComment(
        "Words that are fine in chat even though a censored pattern matches them. They are removed from a message",
        "before it is checked, so a censored word next to one is still caught. The default list is the everyday words",
        "(no, ok, pls, white, soccer...) that the look-alike rules would otherwise read as something else.",
    )
    @SerialName("whitelist")
    private val _whitelist: MutableList<String> = defaultWhitelist.toMutableList(),
    @YamlComment("Patterns that block a message. The default list is bundled with the plugin; keeping this key overrides it.")
    @SerialName("censored")
    private val _censored: MutableList<String> = defaults.toMutableList(),
    @YamlComment("Patterns that block a message even when a whitelisted phrase surrounds them.")
    @SerialName("blacklist")
    private val _blacklist: MutableList<String> = mutableListOf(),
) {
    @Transient val whitelist = mutableMapOf<String, Regex>()
    @Transient val censored = mutableMapOf<String, Regex>()
    @Transient val blacklist = mutableMapOf<String, Regex>()

    /** Entries of the three lists that aren't valid patterns, left out of the maps above. */
    @Transient val invalid = mutableListOf<String>()

    /** Turns the lists into regexes. Run it after the config is loaded. */
    fun compile() {
        invalid.clear()
        fun compile(list: List<String>, regexes: MutableMap<String, Regex>, leet: Boolean) {
            regexes.clear()
            list.forEach { entry ->
                val key = entry.trim()
                if (key.isNotEmpty()) toRegex(key, leet)?.let { regexes[key] = it } ?: invalid.add(key)
            }
        }
        compile(_whitelist, whitelist, leet = false)
        compile(_censored, censored, leet = true)
        compile(_blacklist, blacklist, leet = true)
    }

    /** The entry of [censored] or [blacklist] that blocks [text], or null when it is fine. */
    fun blockedBy(text: String): String? {
        if (!enabled) return null
        val cleaned = whitelist.values.fold(text) { result, regex -> regex.replace(result, " ") }
        return blacklist.entries.firstOrNull { it.value.containsMatchIn(text) }?.key
            ?: censored.entries.firstOrNull { it.value.containsMatchIn(cleaned) }?.key
    }

    fun isBlocked(text: String): Boolean = blockedBy(text) != null

    companion object {
        private val letterOrDigit = "[\\p{L}\\p{N}]"

        /** Lowercase letter to what may stand in for it. */
        private val lookAlikes = mapOf(
            'a' to "[aq4@]",
            'c' to "[ck]",
            'e' to "[e3]",
            'h' to "[hn]",
            'g' to "[gq9]",
            'i' to "[il1!|\\/]",
            'k' to "[kcqx]",
            'l' to "[il1!|\\/]",
            'm' to "(?:m|nn)",
            'n' to "[nh]",
            'o' to "[o0]",
            'q' to "[qk]",
            's' to "[zs5$]",
            't' to "[t7]",
            'u' to "(?:[uv]|[o0]+)",
            'v' to "[vuw]",
            'w' to "(?:w|v|uu)",
            'x' to "[xk]",
            'y' to "(?:y|ie)",
            'z' to "[zs2]",
        )

        /** `\regex\` between backslashes: the regex as written. */
        private fun raw(entry: String) = entry.length > 2 && entry.first() == '\\' && entry.last() == '\\'

        /** Common words that a censored pattern matches by way of its look-alikes (`ho` reads as `no`, `uk` as `ok`), or by being a colour. */
        val defaultWhitelist = listOf(
            "no", "ok", "k", "c", "x", "q", "pls", "less", "lost", "slot", "slots", "sock", "soccer", "mother", "title", "titles",
            "cone", "con", "com", "coming", "count", "county", "cover", "cow", "cows", "cowboy", "cowboys", "fox", "hook", "hip",
            "shot", "snag", "stag", "node", "refer", "goon", "gunner", "pop", "neil", "nell", "heli",
            "ball", "black", "white", "brown", "yellow", "blond", "blonde",
        )

        /** The censored words that ship with the plugin, from `censored-words.txt`. */
        val defaults: List<String> by lazy {
            FilterConfig::class.java.getResourceAsStream("/censored-words.txt")?.use { parseList(it.readBytes().decodeToString()) }.orEmpty()
        }

        /**
         * Reads a list written like Kotlin source: `"quoted"` entries separated by commas, parts of one entry joined
         * with `+` (and allowed to span lines), `//` comments, and `\\` and `\"` escapes in the strings.
         */
        internal fun parseList(text: String): List<String> {
            val entries = mutableListOf<String>()
            var entry: StringBuilder? = null
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    text[i] == ',' -> {
                        entry?.let { entries.add(it.toString()) }
                        entry = null
                    }
                    text[i] == '"' -> {
                        val part = entry ?: StringBuilder().also { entry = it }
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) i++
                            part.append(text[i++])
                        }
                    }
                }
                i++
            }
            entry?.let { entries.add(it.toString()) }
            return entries
        }

        /**
         * The regex for [entry], or null if it isn't one. [leet] allows the look-alike characters and optional
         * separators; without it letters are as written and a space is any whitespace.
         */
        private fun toRegex(entry: String, leet: Boolean): Regex? {
            val body = if (raw(entry)) entry.substring(1, entry.length - 1) else buildString {
                var i = 0
                while (i < entry.length) {
                    val c = entry[i]
                    when {
                        // Escapes and classes are the author's own; groups never capture, `(?:` and lookarounds stay.
                        c == '\\' -> append(entry, i, minOf(i + 2, entry.length)).also { i++ }
                        c == '[' -> {
                            val end = generateSequence(i + 1) { if (entry[it] == '\\') it + 2 else it + 1 }
                                .firstOrNull { it >= entry.length || entry[it] == ']' } ?: entry.length
                            append(entry, i, minOf(end + 1, entry.length))
                            i = end
                        }
                        c == '(' && entry.getOrNull(i + 1) != '?' -> append("(?:")
                        c == '(' -> {
                            append('(')
                            while (entry.getOrNull(i + 1)?.let { it in "?:<=!" } == true) append(entry[++i])
                        }
                        c == '{' -> {
                            val end = entry.indexOf('}', i).takeIf { it >= 0 } ?: entry.lastIndex
                            append(entry, i, end + 1)
                            i = end
                        }
                        c == ' ' -> append(if (leet) "[\\s\\-+=|_]*?" else "\\s+")
                        c.isLetter() -> {
                            // A letter written more than once stands for that letter any number of times.
                            var end = i
                            while (end + 1 < entry.length && entry[end + 1].equals(c, ignoreCase = true)) end++
                            val letter = if (leet) lookAlikes[c.lowercaseChar()] ?: c.toString() else c.toString()
                            append(letter)
                            if (end > i) {
                                append('+')
                                i = end
                                // The quantifier after a repeated letter is already covered by the `+`.
                                if (entry.getOrNull(i + 1)?.let { it in "?*+" } == true) i++
                            }
                        }
                        else -> append(c)
                    }
                    i++
                }
            }
            return try {
                Regex("(?<!$letterOrDigit)(?:$body)(?!$letterOrDigit)", IGNORE_CASE)
            } catch (e: PatternSyntaxException) {
                null
            }
        }
    }
}
