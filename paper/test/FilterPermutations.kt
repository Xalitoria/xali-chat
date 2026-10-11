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
 *  - `permutations.txt`: each entry with the words it spells out: alternatives, optional parts, `{n}` counts, spaces
 *    (with and without the separator) and doubled letters (stretched), but no look-alikes.
 *  - `permutations-leet.txt`: the same with every letter swapped for each of its look-alikes.
 *  - `duplicates.txt`: entries listed twice, alternatives repeated inside an entry, words spelled by several entries,
 *    and entries that other entries already match once look-alikes are counted (`nlgger` is matched by `nigger`).
 *
 * The rules are those of `FilterConfig`: the entry is lowercased; `\regex\` is a regex as written; otherwise a pair of
 * identical letters is that letter repeated (`ss` is `s+`, `kkk` is `k+k`), a space is any run of separators (or none)
 * and each letter may be any of its look-alikes. The look-alike table is read from `FilterConfig.kt` itself, so it
 * can't drift from the filter. Where the rules are unbounded, the tool stops at a few repeats: see the settings below.
 *
 * Settings are system properties or environment variables (`filter.stretch` or `FILTER_STRETCH`):
 *  - `filter.stretch` (2): how many times a repeated letter may repeat.
 *  - `filter.list` (2000): at most this many permutations of an entry are listed or compared; a bigger entry is sampled.
 *  - `filter.leet.list` (200): the same for `permutations-leet.txt`, whose entries grow with every look-alike.
 *  - `filter.config`: where `FilterConfig.kt` is, if not in the usual place.
 */
class FilterPermutations {
    private val directory = Path("test-resources")
    private val stretch = setting("filter.stretch", 2)
    private val listCap = setting("filter.list", 2000)
    private val leetCap = setting("filter.leet.list", 200)

    private fun setting(name: String, default: Int) =
        (System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_')))?.toIntOrNull() ?: default

    // ===== The pattern, as the filter reads it =====

    private sealed interface Node
    private class Lit(val text: String) : Node
    private class Letter(val c: Char) : Node
    private class Stretch(val c: Char) : Node
    private object Sep : Node
    private class CharClass(val chars: List<Char>) : Node
    private class Seq(val parts: List<Node>) : Node
    private class Alt(val options: List<Node>) : Node
    private class Repeat(val node: Node, val min: Int, val max: Int) : Node

    private class Unsupported(message: String) : Exception(message)

    /**
     * [letters]: a letter may be swapped for its look-alikes. [rules]: a pair of letters is a stretch and a space a
     * separator. Both are off for a raw regex and for the look-alike table, which are read as plain regex.
     */
    private inner class Parser(private val text: String, private val letters: Boolean, private val rules: Boolean) {
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
                    letters -> Letter(c)
                    else -> Lit(c.toString())
                }
                else -> Lit(c.toString())
            }
        }
    }

    // ===== The look-alike table, read from FilterConfig.kt =====

    private val lookAlikes: Map<Char, String> by lazy {
        val path = (System.getProperty("filter.config") ?: System.getenv("FILTER_CONFIG"))?.let(::Path)
            ?: Path("kotlin/mc/xalitoria/chat/paper/data/config/FilterConfig.kt")
        val source = path.readText().substringAfter("charReplace = mapOf")
        Regex("""'(.)'\s+to\s+"((?:[^"\\]|\\.)*)"""").findAll(source)
            .associate { it.groupValues[1].single() to it.groupValues[2].replace(Regex("""\\(.)""")) { escape -> escape.groupValues[1] } }
    }

    /** What each letter may be written as: the table's regex read as the strings it matches. */
    private val alternatives: Map<Char, List<String>> by lazy {
        lookAlikes.mapValues { all(Parser(it.value, letters = false, rules = false).parse(), leet = false, Long.MAX_VALUE) }
    }

    private fun fragment(c: Char) = lookAlikes[c] ?: c.toString()

    // ===== Permutations =====

    private val saturated = 1_000_000_000_000L

    private fun count(node: Node, leet: Boolean): Long = when (node) {
        is Lit -> 1
        is Letter -> if (leet) alternatives[node.c]?.size?.toLong() ?: 1 else 1
        is Stretch -> stretch * (if (leet) alternatives[node.c]?.size?.toLong() ?: 1 else 1)
        Sep -> 2
        is CharClass -> node.chars.size.toLong()
        is Seq -> node.parts.fold(1L) { total, part -> times(total, count(part, leet)) }
        is Alt -> node.options.sumOf { count(it, leet) }.coerceAtMost(saturated)
        is Repeat -> {
            val each = count(node.node, leet)
            (node.min..node.max).sumOf { n -> (1..n).fold(1L) { total, _ -> times(total, each) } }.coerceAtMost(saturated)
        }
    }

    private fun times(a: Long, b: Long) = if (a != 0L && b > saturated / a) saturated else a * b

    /** Every permutation of [node] (with repeats where it can be spelled several ways), when there are at most [limit] of them. */
    private fun all(node: Node, leet: Boolean, limit: Long): List<String> {
        require(count(node, leet) <= limit) { "too many permutations" }
        return expand(node, leet)
    }

    private fun expand(node: Node, leet: Boolean): List<String> = when (node) {
        is Lit -> listOf(node.text)
        is Letter -> if (leet) alternatives[node.c] ?: listOf(node.c.toString()) else listOf(node.c.toString())
        is Stretch -> (if (leet) alternatives[node.c] ?: listOf(node.c.toString()) else listOf(node.c.toString()))
            .let { letters -> (1..stretch).flatMap { n -> product(List(n) { letters }) } }
        Sep -> listOf("", " ")
        is CharClass -> node.chars.map { it.toString() }
        is Seq -> product(node.parts.map { expand(it, leet) })
        is Alt -> node.options.flatMap { expand(it, leet) }
        is Repeat -> (node.min..node.max).flatMap { n -> product(List(n) { expand(node.node, leet) }) }
    }

    private fun product(lists: List<List<String>>): List<String> =
        lists.fold(listOf("")) { prefixes, options -> prefixes.flatMap { prefix -> options.map { prefix + it } } }

    /** One permutation of [node], chosen at random. */
    private fun sample(node: Node, leet: Boolean, random: Random): String = when (node) {
        is Lit -> node.text
        is Letter -> if (leet) alternatives[node.c]?.random(random) ?: node.c.toString() else node.c.toString()
        is Stretch -> (1..random.nextInt(1, stretch + 1)).joinToString("") { sample(Letter(node.c), leet, random) }
        Sep -> if (random.nextBoolean()) "" else " "
        is CharClass -> node.chars.random(random).toString()
        is Seq -> node.parts.joinToString("") { sample(it, leet, random) }
        is Alt -> sample(node.options.random(random), leet, random)
        is Repeat -> (1..random.nextInt(node.min, node.max + 1)).joinToString("") { sample(node.node, leet, random) }
    }

    /** The distinct permutations, all of them if there are at most [cap] ways to spell them, else a random [cap]. Also whether it is a sample. */
    private class Spellings(val words: List<String>, val sampled: Boolean)

    /** A separator that ends up at either end of a word, or twice in a row (around an optional part that is left out), is no part of the word. */
    private fun String.tidy() = trim().replace(Regex(" {2,}"), " ")

    private fun permutations(node: Node, leet: Boolean, cap: Int): Spellings {
        if (count(node, leet) <= cap * 4L) {
            val words = all(node, leet, cap * 4L).map { it.tidy() }.distinct().sorted()
            return Spellings(words.take(cap), words.size > cap)
        }
        val random = Random(1)
        return Spellings(List(cap * 2) { sample(node, leet, random).tidy() }.distinct().sorted().take(cap), true)
    }

    // ===== Matching: the regex the filter makes of an entry =====

    private class Entry(val text: String, val node: Node?, val regex: Regex?, val error: String?) {
        val key = text.trim().lowercase()
    }

    private fun regexOf(node: Node): String = when (node) {
        is Lit -> node.text.map { if (it.isLetterOrDigit()) "$it" else "\\$it" }.joinToString("")
        is Letter -> fragment(node.c)
        is Stretch -> fragment(node.c) + "+"
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
                Entry(text, Parser(body, letters = false, rules = false).parse(), Regex(body, RegexOption.IGNORE_CASE), null)
            } else {
                val node = Parser(key, letters = true, rules = true).parse()
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
            is Letter -> (alternatives[node.c] ?: listOf(node.c.toString())).also { options -> alphabet += options.flatMap { it.toList() } }
                .let { options -> options.minOf { it.length }..options.maxOf { it.length } }
            is Stretch -> lengths(Letter(node.c)).let { it.first..Int.MAX_VALUE / 2 }
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
        println("Entries: ${entries.size}, readable: ${valid.size}, look-alikes: ${lookAlikes.size}")

        fun header(title: String) = listOf("# $title", "# ${entries.size} entries from $source; repeated letters stretch up to $stretch; look-alikes from FilterConfig.kt", "")

        // Permutations, with and without look-alikes
        for (leet in listOf(false, true)) {
            val file = if (leet) "permutations-leet.txt" else "permutations.txt"
            val cap = if (leet) leetCap else listCap
            var total = 0L
            directory.resolve(file).bufferedWriter().use { out ->
                header(if (leet) "Permutations with every look-alike" else "Permutations").forEach { out.appendLine(it) }
                parsed.forEach { entry ->
                    val node = entry.node
                    if (node == null) {
                        out.appendLine("${entry.text}\tNOT READ: ${entry.error}")
                        return@forEach
                    }
                    val possible = count(node, leet)
                    val spelled = permutations(node, leet, cap)
                    total += possible
                    out.appendLine("${entry.text}\t$possible ways" + if (spelled.sampled) " (listing ${spelled.words.size})" else "")
                    spelled.words.forEach { out.appendLine("\t$it") }
                }
            }
            println("$file: ${"%,d".format(total)} ways to spell the entries")
        }

        // Duplicates
        val report = mutableListOf<String>()
        val random = Random(1)
        val spellings = valid.associateWith { permutations(it.node!!, leet = false, listCap) }
        val words = spellings.mapValues { it.value.words }
        val sampled = valid.filter { count(it.node!!, false) > listCap * 4L }.size

        // 0. The look-alike table
        report += "## 0. Look-alike table (${lookAlikes.size} letters)"
        lookAlikes.forEach { (letter = key, regex = value) ->
            val options = alternatives.getValue(letter)
            val notes = listOfNotNull(
                "does not match the letter itself".takeIf { letter.toString() !in options },
                "has regex syntax among its characters".takeIf { options.any { option -> option.any { it in "()?+:" } } },
                lookAlikes.filter { it.key != letter && alternatives.getValue(it.key).toSet() == options.toSet() }.keys.takeIf { it.isNotEmpty() }
                    ?.let { "same as ${it.joinToString()}" },
            )
            report += "\t$letter\t$regex\t${options.size}" + if (notes.isEmpty()) "" else "\t<- ${notes.joinToString("; ")}"
        }

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
                    val options = node.options.map { if (count(it, false) <= 5000) expand(it, false).toSet() else emptySet() }
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
        report += "## 3. Words spelled by more than one entry, as written, without look-alikes (${shared.size} words, ${pairs.size} pairs of entries)"
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
        report += "## 4. Entries that other entries already match, look-alikes counted (${whole.size} entirely, ${rows.size - whole.size} partly)"
        report += "\tEach spelling of an entry is tried against every other entry's regex. An entry that is matched entirely adds nothing."
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
        println("Written to $directory: permutations.txt, permutations-leet.txt, duplicates.txt")
    }

    // ===== Checks that the tool reads the rules the way the filter does =====

    private fun spell(text: String, leet: Boolean = false) = permutations(entry(text).node!!, leet, 100_000).words

    @Test
    fun rules() {
        assertEquals(listOf("beaner", "beaners"), spell("beaners?"))
        assertEquals(listOf("blacks", "blacky"), spell("black(s|y)"))
        assertEquals(listOf("bang bros", "bangbros"), spell("bang bros"))
        assertEquals(listOf("s", "ss"), spell("ss"))
        assertEquals(listOf("kk", "kkk"), spell("kkk"), "a pair stretches and the third letter stays: k+k")
        assertEquals(listOf("bbc"), spell("\\b{2}c\\"))
        assertEquals(listOf("s.o.b."), spell("s\\.o\\.b\\."))
        assertEquals(listOf("fuck", "fuk"), spell("fu(c)?k"))
        assertEquals(listOf("a", "ab"), spell("a(b)?"))
        assertTrue("@" in alternatives.getValue('a') && "4" in alternatives.getValue('a'))
        assertTrue(spell("ash", leet = true).containsAll(listOf("@sh", "4zn", "asn")))
    }

    @Test
    fun matching() {
        assertTrue(entry("nigger").regex!!.matches("nlgger"), "a look-alike of the entry")
        assertTrue(entry("bang bros").regex!!.matches("bangbros") && entry("bang bros").regex!!.matches("bang-bros"))
        assertTrue(entry("ass").regex!!.matches("@ss"))
        assertTrue(entry("ass").regex!!.matches("as"), "a pair of letters is `s+`, so one is enough")
        assertFalse(entry("kkk").regex!!.matches("k"), "but `kkk` is `k+k`")
        assertTrue(entry("\\b{2}c\\").regex!!.matches("BBC"))
        assertEquals("'.' matches any character", entry("a.b").error)
    }
}
