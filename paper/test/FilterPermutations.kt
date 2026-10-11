import java.nio.file.Path
import java.util.regex.PatternSyntaxException
import kotlin.io.path.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lists every permutation the entries of `test-resources/bad-words-rev.txt` stand for, and finds the entries that
 * overlap, so the filter can be trimmed. Writes to `test-resources`:
 *
 *  - `permutations.txt`: each entry with the words it spells out: alternatives, optional parts, `{n}` counts and
 *    doubled letters (stretched). Only alphabetic words are listed, so a separator between words is left out
 *    (`bang bros` is `bangbros`), and an entry with other characters in it (`women's`) lists none of those.
 *    Look-alike characters (`FilterConfig`'s `charReplace`) are not used at all.
 *  - `duplicates.txt`: entries listed twice, alternatives repeated inside an entry, words spelled by several entries,
 *    and entries that other entries already match.
 *
 * The rules are those of `FilterConfig` apart from the look-alikes: the entry is lowercased; `\regex\` is a regex as
 * written; otherwise a pair of identical letters is that letter repeated (`ss` is `s+`, `kkk` is `k+k`) and a space is
 * any run of separators, or none. Where the rules are unbounded, the tool stops at a few repeats.
 *
 * Settings are system properties or environment variables (`filter.stretch` or `FILTER_STRETCH`):
 *  - `filter.stretch` (2): how many times a repeated letter may repeat.
 *  - `filter.list` (2000): at most this many permutations of an entry are listed or compared; a bigger entry is sampled.
 */
class FilterPermutations {
    private val directory = Path("test-resources")
    private val stretch = setting("filter.stretch", 2)
    private val listCap = setting("filter.list", 2000)

    private fun setting(name: String, default: Int) =
        (System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_')))?.toIntOrNull() ?: default

    // ===== The pattern, as the filter reads it =====

    private sealed interface Node
    private class Lit(val text: String) : Node
    private class Stretch(val c: Char) : Node
    private object Sep : Node
    private class CharClass(val chars: List<Char>) : Node
    private class Seq(val parts: List<Node>) : Node
    private class Alt(val options: List<Node>) : Node
    private class Repeat(val node: Node, val min: Int, val max: Int) : Node

    private class Unsupported(message: String) : Exception(message)

    /** [rules]: a pair of letters is a stretch and a space a separator. Off for a regex written as is. */
    private inner class Parser(private val text: String, private val rules: Boolean) {
        private var i = 0

        fun parse(): Node = parseAlt().also { if (i < text.length) throw Unsupported("unbalanced ')' at $i") }

        private fun parseAlt(): Node {
            val options = mutableListOf(parseSeq())
            while (i < text.length && text[i] == '|') {
                i++
                options += parseSeq()
            }
            return if (options.size == 1) options[0] else Alt(options)
        }

        private fun parseSeq(): Node {
            val parts = mutableListOf<Node>()
            while (i < text.length && text[i] != '|' && text[i] != ')') parts += parseQuantified()
            return Seq(parts)
        }

        private fun parseQuantified(): Node {
            var node = parseAtom()
            while (i < text.length) {
                val bounds: IntRange = when (text[i]) {
                    '?' -> 0..1
                    '*' -> 0..2
                    '+' -> 1..2
                    '{' -> {
                        val end = text.indexOf('}', i).takeIf { it > i } ?: throw Unsupported("unclosed '{'")
                        val count = text.substring(i + 1, end)
                        val low = count.substringBefore(',').toInt()
                        i = end
                        low..if (',' !in count) low else minOf(count.substringAfter(',').toIntOrNull() ?: (low + 1), low + 2)
                    }
                    else -> break
                }
                i++
                node = Repeat(node, bounds.first, bounds.last)
            }
            return node
        }

        private fun parseAtom(): Node {
            val c = text[i++]
            return when {
                c == '(' -> {
                    if (text.startsWith("?:", i)) i += 2
                    else if (text.startsWith("?", i)) throw Unsupported("lookaround at $i")
                    parseAlt().also {
                        if (i >= text.length) throw Unsupported("unclosed '('")
                        i++
                    }
                }
                c == '[' -> {
                    val chars = mutableListOf<Char>()
                    while (i < text.length && text[i] != ']') {
                        val member = if (text[i] == '\\') text[++i] else text[i]
                        i++
                        if (i + 1 < text.length && text[i] == '-' && text[i + 1] != ']') {
                            val end = text[i + 1].also { i += 2 }
                            chars += member..end
                        } else chars += member
                    }
                    if (i >= text.length) throw Unsupported("unclosed '['")
                    i++
                    CharClass(chars.distinct())
                }
                c == '\\' -> Lit(text.getOrNull(i++)?.toString() ?: throw Unsupported("trailing backslash"))
                c == '.' -> throw Unsupported("'.' matches any character")
                c in "?*+{" -> throw Unsupported("nothing to repeat at ${i - 1}")
                rules && c == ' ' -> Sep
                c in 'a'..'z' -> when {
                    // A pair of identical letters is that letter repeated: `ss` is `s+`. A `?` or `+` after it only
                    // changes how the regex backtracks, not what it matches.
                    rules && text.getOrNull(i) == c -> {
                        i++
                        while (text.getOrNull(i) == '?' || text.getOrNull(i) == '+') i++
                        Stretch(c)
                    }
                    else -> Lit(c.toString())
                }
                else -> Lit(c.toString())
            }
        }
    }

    // ===== Permutations =====

    private val saturated = 1_000_000_000_000L

    private fun count(node: Node): Long = when (node) {
        is Lit -> 1
        is Stretch -> stretch.toLong()
        Sep -> 1
        is CharClass -> node.chars.size.toLong()
        is Seq -> node.parts.fold(1L) { total, part -> times(total, count(part)) }
        is Alt -> node.options.sumOf { count(it) }.coerceAtMost(saturated)
        is Repeat -> {
            val each = count(node.node)
            (node.min..node.max).sumOf { n -> (1..n).fold(1L) { total, _ -> times(total, each) } }.coerceAtMost(saturated)
        }
    }

    private fun times(a: Long, b: Long) = if (a != 0L && b > saturated / a) saturated else a * b

    /** Every permutation of [node], with repeats where it can be spelled several ways. Only call it when [count] is small. */
    private fun expand(node: Node): List<String> = when (node) {
        is Lit -> listOf(node.text)
        is Stretch -> (1..stretch).map { node.c.toString().repeat(it) }
        Sep -> listOf("")
        is CharClass -> node.chars.map { it.toString() }
        is Seq -> product(node.parts.map { expand(it) })
        is Alt -> node.options.flatMap { expand(it) }
        is Repeat -> (node.min..node.max).flatMap { n -> product(List(n) { expand(node.node) }) }
    }

    private fun product(lists: List<List<String>>): List<String> =
        lists.fold(listOf("")) { prefixes, options -> prefixes.flatMap { prefix -> options.map { prefix + it } } }

    /** One permutation of [node], chosen at random. */
    private fun sample(node: Node, random: Random): String = when (node) {
        is Lit -> node.text
        is Stretch -> node.c.toString().repeat(random.nextInt(1, stretch + 1))
        Sep -> ""
        is CharClass -> node.chars.random(random).toString()
        is Seq -> node.parts.joinToString("") { sample(it, random) }
        is Alt -> sample(node.options.random(random), random)
        is Repeat -> (1..random.nextInt(node.min, node.max + 1)).joinToString("") { sample(node.node, random) }
    }

    /** The distinct permutations, all of them if there are at most [cap] ways to spell them, else a random [cap]. Also whether it is a sample. */
    private class Spellings(val words: List<String>, val sampled: Boolean)

    /** The alphabetic permutations only: a word with a space, a dot or a digit in it is not one. */
    private fun String.alphabetic() = isNotEmpty() && all { it in 'a'..'z' }

    /** The distinct alphabetic permutations, all of them if there are few ways to spell them, else a random [cap]. */
    private fun permutations(node: Node, cap: Int): Spellings {
        if (count(node) <= cap * 4L) {
            val words = expand(node).filter { it.alphabetic() }.distinct().sorted()
            return Spellings(words.take(cap), words.size > cap)
        }
        val random = Random(1)
        return Spellings(List(cap * 4) { sample(node, random) }.filter { it.alphabetic() }.distinct().sorted().take(cap), true)
    }

    // ===== Matching: the regex the filter makes of an entry =====

    private class Entry(val text: String, val node: Node?, val regex: Regex?, val error: String?) {
        val key = text.trim().lowercase()
    }

    private fun regexOf(node: Node): String = when (node) {
        is Lit -> node.text.map { if (it.isLetterOrDigit()) "$it" else "\\$it" }.joinToString("")
        is Stretch -> "${node.c}+"
        Sep -> "[\\s\\-+=|_]*?"
        is CharClass -> node.chars.joinToString("", "[", "]") { "\\$it" }
        is Seq -> node.parts.joinToString("") { regexOf(it) }
        is Alt -> node.options.joinToString("|", "(?:", ")") { regexOf(it) }
        is Repeat -> "(?:${regexOf(node.node)}){${node.min},${node.max}}"
    }

    private fun entry(text: String): Entry {
        val key = text.trim().lowercase()
        return try {
            // `\regex\` between backslashes is a regex as written; the rest is a pattern.
            val raw = key.length > 2 && key.first() == '\\' && key.last() == '\\'
            if (raw) {
                val body = key.substring(1, key.length - 1)
                Entry(text, Parser(body, rules = false).parse(), Regex(body, RegexOption.IGNORE_CASE), null)
            } else {
                val node = Parser(key, rules = true).parse()
                Entry(text, node, Regex(regexOf(node), RegexOption.IGNORE_CASE), null)
            }
        } catch (e: Unsupported) {
            Entry(text, null, null, e.message)
        } catch (e: PatternSyntaxException) {
            Entry(text, null, null, e.description)
        } catch (e: NumberFormatException) {
            Entry(text, null, null, "bad count in { }")
        }
    }

    /** What an entry can be, for skipping the entries that can't match a word before running their regex on it. */
    private class Shape(val alphabet: Set<Char>?, val min: Int, val max: Int)

    private fun shape(entry: Entry): Shape {
        val node = entry.node ?: return Shape(null, 0, Int.MAX_VALUE)
        if (entry.key.startsWith("\\")) return Shape(null, 0, Int.MAX_VALUE)
        val alphabet = mutableSetOf<Char>()
        fun lengths(node: Node): IntRange = when (node) {
            is Lit -> node.text.also { alphabet += it.toList() }.length.let { it..it }
            is Stretch -> 1.also { alphabet += node.c }..Int.MAX_VALUE / 2
            Sep -> 0.also { alphabet += " -+=|_\t".toList() }..1
            is CharClass -> 1.also { alphabet += node.chars }..1
            is Seq -> node.parts.map(::lengths).let { parts -> parts.sumOf { it.first }..parts.sumOf { it.last.toLong() }.coerceAtMost(Int.MAX_VALUE / 2L).toInt() }
            is Alt -> node.options.map(::lengths).let { options -> options.minOf { it.first }..options.maxOf { it.last } }
            is Repeat -> lengths(node.node).let { it.first * node.min..(it.last.toLong() * node.max).coerceAtMost(Int.MAX_VALUE / 2L).toInt() }
        }
        return lengths(node).let { Shape(alphabet, it.first, it.last) }
    }

    // ===== The tool =====

    /** The entries of a list written like Kotlin source: `"quoted"` entries, commas, `+` between parts, `//` comments. */
    private fun parseList(text: String): List<String> {
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

    @Test
    fun generate() {
        val source = directory.resolve("bad-words-rev.txt")
        val entries = parseList(source.readText())
        val parsed = entries.map(::entry)
        val valid = parsed.filter { it.node != null }
        println("Entries: ${entries.size}, readable: ${valid.size}")

        fun header(title: String) = listOf("# $title", "# ${entries.size} entries from $source; repeated letters stretch up to $stretch; alphabetic words only, no look-alikes", "")

        // Permutations
        var total = 0L
        var listed = 0L
        directory.resolve("permutations.txt").bufferedWriter().use { out ->
            header("Permutations").forEach { out.appendLine(it) }
            parsed.forEach { entry ->
                val node = entry.node
                if (node == null) {
                    out.appendLine("${entry.text}\tNOT READ: ${entry.error}")
                    return@forEach
                }
                val spelled = permutations(node, listCap)
                total += count(node)
                listed += spelled.words.size
                out.appendLine("${entry.text}\t${spelled.words.size} words" + if (spelled.sampled) " (a sample: there are more)" else if (spelled.words.isEmpty()) " (none are alphabetic)" else "")
                spelled.words.forEach { out.appendLine("\t$it") }
            }
        }
        println("permutations.txt: ${"%,d".format(listed)} words listed, ${"%,d".format(total)} ways to spell the entries")

        // Duplicates
        val report = mutableListOf<String>()
        val random = Random(1)
        val spellings = valid.associateWith { permutations(it.node!!, listCap) }
        val words = spellings.mapValues { it.value.words }
        val sampled = valid.filter { count(it.node!!) > listCap * 4L }.size

        // 1. The same entry twice
        val twice = parsed.groupBy { it.key }.filter { it.value.size > 1 }
        report += ""
        report += "## 1. Entries written more than once (${twice.size})"
        twice.forEach { report += "\t${it.value.size}x\t${it.key}" }

        // 2. An alternative that repeats inside one entry
        val repeated = mutableListOf<String>()
        fun walk(entry: Entry, node: Node) {
            when (node) {
                is Alt -> {
                    val options = node.options.map { if (count(it) <= 5000) expand(it).toSet() else emptySet() }
                    val overlap = options.flatMapIndexed { i, a -> options.drop(i + 1).flatMap { b -> a intersect b } }.toSet()
                    if (overlap.isNotEmpty()) repeated += "\t${entry.key}\t(${node.options.size} alternatives) both spell: ${overlap.take(8).joinToString(", ")}"
                    node.options.forEach { walk(entry, it) }
                }
                is Seq -> node.parts.forEach { walk(entry, it) }
                is Repeat -> walk(entry, node.node)
                else -> {}
            }
        }
        valid.forEach { walk(it, it.node!!) }
        report += ""
        report += "## 2. Alternatives that spell the same word inside one entry (${repeated.size})"
        report += repeated

        // 3. The same word spelled by different entries (without look-alikes)
        val spellers = mutableMapOf<String, MutableSet<Entry>>()
        words.forEach { (entry = key, list = value) -> list.filter { it.isNotBlank() }.forEach { word -> spellers.getOrPut(word) { mutableSetOf() }.add(entry) } }
        val shared = spellers.filter { entry -> entry.value.map { it.key }.distinct().size > 1 }
        val pairs = mutableMapOf<List<String>, MutableList<String>>()
        shared.forEach { word ->
            val keys = word.value.map { it.key }.distinct().sorted()
            keys.forEachIndexed { i, a -> keys.drop(i + 1).forEach { b -> pairs.getOrPut(listOf(a, b)) { mutableListOf() }.add(word.key) } }
        }
        report += ""
        report += "## 3. Words spelled by more than one entry (${shared.size} words, ${pairs.size} pairs of entries)"
        pairs.entries.sortedByDescending { it.value.size }.forEach { pair ->
            val sizes = pair.key.map { key -> spellings.entries.first { it.key.key == key }.value.let { "${it.words.size}" + if (it.sampled) "+" else "" } }
            report += "\t${pair.value.size} shared\t${pair.key[0]}  (spells ${sizes[0]})\t${pair.key[1]}  (spells ${sizes[1]})\te.g. ${pair.value.take(4).joinToString(", ")}"
        }

        // 4. Entries that others match once look-alikes are counted
        val shapes = valid.associateWith(::shape)
        val covers = valid.associateWith { mutableMapOf<Entry, Int>() }
        val covered = mutableMapOf<Entry, Int>()
        val candidates = words.mapValues { it.value.filter { word -> word.isNotBlank() } }
        candidates.forEach { (entry = key, list = value) ->
            val others = valid.filter { it !== entry && it.key != entry.key }
            list.forEach { word ->
                var any = false
                others.forEach { other ->
                    val shape = shapes.getValue(other)
                    if (word.length < shape.min || word.length > shape.max) return@forEach
                    if (shape.alphabet != null && word.any { it !in shape.alphabet }) return@forEach
                    if (other.regex!!.matches(word)) {
                        covers.getValue(entry).merge(other, 1, Int::plus)
                        any = true
                    }
                }
                if (any) covered.merge(entry, 1, Int::plus)
            }
        }
        val rows = covered.entries.sortedWith(compareByDescending<Map.Entry<Entry, Int>> { it.value.toDouble() / candidates.getValue(it.key).size }.thenByDescending { it.value })
        val whole = rows.filter { it.value == candidates.getValue(it.key).size }
        report += ""
        report += "## 4. Entries that other entries already match (${whole.size} entirely, ${rows.size - whole.size} partly)"
        report += "\tEach spelling of an entry is tried against every other entry's regex (stretched letters and optional separators included). An entry that is matched entirely adds nothing."
        rows.forEach { row ->
            val total = candidates.getValue(row.key).size
            val by = covers.getValue(row.key).entries.sortedByDescending { it.value }.take(4)
            val mutual = by.filter { (covers.getValue(it.key)[row.key] ?: 0) == candidates.getValue(it.key).size && row.value == total }
            report += "\t${row.value}/$total\t${row.key.key}\tmatched by ${by.joinToString("; ") { "${it.key.key} (${it.value})" }}" +
                if (mutual.isNotEmpty()) "\tBOTH WAYS with ${mutual.joinToString { it.key.key }}" else ""
        }

        val notRead = parsed.filter { it.node == null }
        report += ""
        report += "## Not read (${notRead.size})"
        notRead.forEach { report += "\t${it.key}\t${it.error}" }

        directory.resolve("duplicates.txt").writeLines(
            header("Duplicates") + listOf("# $sampled entries have more spellings than the list limit ($listCap); their spellings are a random sample.", "") + report,
        )
        println("Duplicates: ${twice.size} repeated entries, ${repeated.size} repeated alternatives, ${shared.size} shared words, ${whole.size} entries matched entirely by others, ${rows.size - whole.size} partly")
        println("Written to $directory: permutations.txt, duplicates.txt")
    }

    // ===== Checks that the tool reads the rules the way the filter does =====

    private fun spell(text: String) = permutations(entry(text).node!!, 100_000).words

    @Test
    fun rules() {
        assertEquals(listOf("beaner", "beaners"), spell("beaners?"))
        assertEquals(listOf("blacks", "blacky"), spell("black(s|y)"))
        assertEquals(listOf("bangbros"), spell("bang bros"), "only alphabetic words: the separator is left out")
        assertEquals(listOf("s", "ss"), spell("ss"))
        assertEquals(listOf("kk", "kkk"), spell("kkk"), "a pair stretches and the third letter stays: k+k")
        assertEquals(listOf("bbc"), spell("\\b{2}c\\"))
        assertEquals(emptyList(), spell("s\\.o\\.b\\."), "a dot is not alphabetic")
        assertEquals(listOf("fuck", "fuk"), spell("fu(c)?k"))
        assertEquals(listOf("a", "ab"), spell("a(b)?"))
        assertEquals(listOf("a"), spell("a(\\-b)?"))
    }

    @Test
    fun matching() {
        assertFalse(entry("nigger").regex!!.matches("nlgger"), "no look-alikes")
        assertTrue(entry("bang bros").regex!!.matches("bangbros") && entry("bang bros").regex!!.matches("bang-bros"))
        assertTrue(entry("ass").regex!!.matches("as"), "a pair of letters is `s+`, so one is enough")
        assertFalse(entry("kkk").regex!!.matches("k"), "but `kkk` is `k+k`")
        assertTrue(entry("\\b{2}c\\").regex!!.matches("BBC"))
        assertEquals("'.' matches any character", entry("a.b").error)
    }
}
