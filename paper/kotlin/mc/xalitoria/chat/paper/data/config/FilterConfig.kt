package mc.xalitoria.chat.paper.data.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.collections.listOf
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.text.ifEmpty
import kotlin.text.trim

@Serializable
data class FilterConfig(
    val enabled: Boolean = false,
    @SerialName("whitelist")
    private val _whitelist: MutableList<Phrase> = mutableListOf(),
    @SerialName("censored")
    private val _censored: MutableList<Phrase> =             [
        Phrase("alabaman? hot pocket"),
        Phrase("alaskan? pipe line"),
        Phrase("argie"),
        Phrase(
            ["anus", "arrsse", "ass", "buty", "butt"],
            [
                Phrase(
                    ["bang", "call", "cheek", "face?", "head", "hole", "munch", "plugg", "staainn"],
                    Phrase(
                        match = ["er", "ed", "ing"],
                        require = false
                    ),
                    require = false
                ),
                Phrase(
                    match = ["man", "naked", "pirate"],
                    require = false
                )
            ]
        ),
        Phrase(["bare nake(d|y)"]),
    ],
    @SerialName("blacklist")
    private val _blacklist: MutableList<Phrase> = mutableListOf(),
) {

    @Serializable
    data class Phrase(
        val match: List<String>? = null,
        val next: List<Phrase>? = null,
        val require: Boolean = true
    ) {
        constructor(match: String, next: List<Phrase>? = null, require: Boolean = true): this(listOf(match), next, require)
        constructor(match: List<String>? = null, next: Phrase, require: Boolean = true): this(match, listOf(next), require)

        fun build(): Map<String, Regex> {
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
                    runCatching { Regex(processed, IGNORE_CASE) }.getOrNull()?.let { result[match] = it }
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
        _whitelist.clear()
        _whitelist.forEach { whitelist.putAll(it.build().toSortedMap()) }

        censored.clear()
        _censored.clear()
        _censored.forEach { censored.putAll(it.build().toSortedMap()) }

        blacklist.clear()
        _blacklist.clear()
        _blacklist.forEach { blacklist.putAll(it.build().toSortedMap()) }
    }

    companion object {
        private val regexLiteral = Regex("""^\\.+?\\$""")
        private val regexGroupNonCapt = Regex("\\([a-z]", IGNORE_CASE)
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

        /*
        private fun MutableCollection<Phrase>.build() = buildMap<String, Regex> {
            this@build.mapNotNull { match ->
                match.trim().ifEmpty { null }
                    ?.lowercase()
                    ?.replace(regexGroupNonCapt) { "(?:" + it.value.last() }
            }.forEach { match ->
                val put = if (match.matches(regexLiteral)) {
                    match.removeSurrounding("\\")
                } else {
                    buildString {
                        match
                            // double letters
                            .replace(Regex("([a-z])\\1")) { it.value.first() + "+" }
                            // spaces
                            .replace(" ", "[\\s-+=|_]*?")
                            .forEach { append(charReplace[it] ?: it) }
                    }
                }
                put(match, Regex(put, IGNORE_CASE))
            }
        }
        */
    }
}
