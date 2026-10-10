package mc.xalitoria.chat.paper.data.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.text.ifEmpty
import kotlin.text.trim

@Serializable
data class FilterConfig(
    val enabled: Boolean = true,
    @SerialName("whitelist")
    private val _whitelist: MutableList<String> = mutableListOf(),
    @SerialName("censored")
    private val _censored: MutableList<Phrase> = [
        Phrase("alabaman? hot pocket"),
        Phrase("alaskan? pipe line"),
        Phrase("argie"),
        Phrase(
            ["anus", "arrsse", "ass", "buty", "butt"],
            next = [
                Phrase(
                    ["bang", "call", "cheek", "face?", "head", "hole", "munch", "plugg", "staainn"],
                    Phrase(
                        ["er", "ed", "ing"],
                        required = false
                    ),
                    required = false
                ),
                Phrase(
                    ["man", "naked", "pirate"],
                    required = false
                )
            ]
        ),
        Phrase("bare nake(d|y)"),
    ],
    @SerialName("blacklist")
    private val _blacklist: MutableList<String> = mutableListOf(),
) {

    @Serializable
    data class Phrase(
        val match: List<String>? = null,
        val next: List<Phrase>? = null,
        val require: Boolean = true
    ) {
        constructor(match: String, next: List<Phrase>? = null, require: Boolean = true) : this([match], next, require)
        constructor(match: List<String>? = null, next: Phrase, require: Boolean = true) : this(match, [next], require)

        constructor(match: List<String>, require: Boolean = true) : this(match, null, require)
        constructor(next: List<Phrase>, require: Boolean = true) : this(null, next, require)

        constructor(match: String, require: Boolean = true) : this([match], null, require)
        constructor(next: Phrase, require: Boolean = true) : this(null, next, require)

        internal fun build(): Map<String, Regex> {
            val result = mutableMapOf<String, Regex>()
            val nonCaptured = match?.mapNotNull { match ->
                match.trim()
                    .ifEmpty { null }
                    ?.lowercase()
                    ?.replace(Regex("\\([a-z|]", IGNORE_CASE)) { "(?:" + it.value.last() }
            }.orEmpty()
            val children = next?.flatMap { it.build().entries }?.associate { it.key to it.value }.orEmpty()
            nonCaptured.forEach { match ->
                if (children.isNotEmpty()) {
                    // regex literals
                    val processed = if (match.matches(Regex("\\\\.+?\\\\"))) match
                    else buildString {
                        match
                            // double letters
                            .replace(Regex("([a-z])\\1")) { it.value.first() + "+" }
                            // spaces
                            .replace(" ", "[\\s-+=_]*?")
                            .forEach { append(charReplace[it] ?: it) }
                    }
                    runCatching { Regex(processed, IGNORE_CASE) }.getOrNull()
                        ?.let { result.put(match, processed) }
                }
            }
            return result
        }
    }

    @Transient val whitelist = mutableMapOf<String, Regex>()
    @Transient val censored = mutableMapOf<String, Regex>()
    @Transient val blacklist = mutableMapOf<String, Regex>()

    fun compile() {
        whitelist.clear()
        whitelist.putAll(_whitelist.build().toSortedMap())
        _whitelist.addAll(whitelist.keys)

        //censored.clear()
        //censored.putAll(_censored.build().toSortedMap())
        //_censored.addAll(censored.keys)

        censored.clear()
        _censored.clear()
        censored.putAll(_censored.build().toSortedMap())

        blacklist.clear()
        blacklist.putAll(_blacklist.build().toSortedMap())
        _blacklist.addAll(blacklist.keys)
    }

    companion object {
        private val charReplace = mapOf<Char, String>(
            'a' to "[aq4@]",
            'c' to "[ck]",
            'e' to "[e3]",
            'h' to "[hn]",
            'g' to "[gq9]",
            'i' to "[il1!|\\/]",
            'k' to "[kcqx]",
            'l' to "[il1!|\\/]",
            'm' to "(nn)",
            'n' to "[nh]",
            'o' to "[o0]",
            'q' to "[qk]",
            's' to "[zs5]",
            't' to "[t7]",
            'u' to "[uv|(?:o+)]",
            'v' to "[vuw]",
            'w' to "(?:w|v|uu)",
            'x' to "[xk]",
            'y' to "(?:y|ie)",
            'z' to "[zs2]",
        )

        private fun MutableCollection<String>.build() = buildMap<String, Regex> {
            this@build.mapNotNull { match ->
                match.trim().ifEmpty { null }
                    ?.lowercase()
                    ?.replace(Regex("\\([a-z]", IGNORE_CASE)) { "(?:" + it.value.last() }
            }.forEach { match ->
                if (match.matches(Regex("\\\\.+?\\\\"))) put(match, Regex(match.removeSurrounding("\\"), IGNORE_CASE))
                else {
                    // regex literals
                    if (match.matches(Regex("\\\\.+?\\\\"))) Regex(match, IGNORE_CASE)
                    else buildString {
                        match
                            // double letters
                            .replace(Regex("([a-z])\\1")) { it.value.first() + "+" }
                            // spaces
                            .replace(" ", "[\\s-+=|_]*?")
                            .forEach { append(charReplace[it] ?: it) }
                    }.let { put(match, Regex(it, IGNORE_CASE)) }
                }
            }
        }

    }
}
