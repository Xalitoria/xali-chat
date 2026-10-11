import mc.xalitoria.chat.paper.data.config.FilterConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterMatching {
    private fun filter(censored: List<String> = emptyList(), whitelist: List<String> = emptyList(), blacklist: List<String> = emptyList()) =
        FilterConfig(_whitelist = whitelist.toMutableList(), _censored = censored.toMutableList(), _blacklist = blacklist.toMutableList())
            .also { it.compile() }

    @Test
    fun listParsing() {
        val text = """
            "plain",
            // "commented out",
            "a" +
                "(b" + "|c)" ,
            "quote\"d", "back\\slash",
            "last"
        """.trimIndent()
        assertContentEquals(listOf("plain", "a(b|c)", "quote\"d", "back\\slash", "last"), FilterConfig.parseList(text))
    }

    @Test
    fun bundledList() {
        val defaults = FilterConfig.defaults
        assertTrue(defaults.size > 900, "the bundled list loads (${defaults.size})")
        assertTrue(defaults.any { it.startsWith("(breed|big(ger)?") && it.endsWith("(er|ed|ing|s)?") }, "multi-line entries are joined")
        assertFalse(defaults.any { it.contains("piece of") && it.startsWith("//") })
        val config = FilterConfig().also { it.compile() }
        assertEquals(emptyList(), config.invalid, "every bundled pattern compiles")
        assertTrue(config.censored.size > 900)
    }

    @Test
    fun wholeWordsOnly() {
        val f = filter(["ass"])
        assertTrue(f.isBlocked("you ass"))
        assertTrue(f.isBlocked("Ass!"))
        assertFalse(f.isBlocked("class"), "inside a word")
        assertFalse(f.isBlocked("assassin"))
        assertFalse(f.isBlocked("pass the salt"))
    }

    @Test
    fun lookAlikesAndRepeats() {
        val f = filter(["fuck"])
        listOf("fuck", "FUCK", "fvck", "f0ck", "fuck").forEach { assertTrue(f.isBlocked(it), it) }
        assertTrue(f.isBlocked("what the f0ck"))
        assertFalse(f.isBlocked("fun"))
        assertTrue(filter(["nasty"]).isBlocked("n4sty"), "a reads as 4")
        val ass = filter(["ss"]) // a repeated letter stands for itself any number of times
        assertTrue(ass.isBlocked("s")); assertTrue(ass.isBlocked("sssss")); assertTrue(ass.isBlocked("\$\$"))
        assertTrue(filter(["milk"]).isBlocked("nnilk"), "m also reads as nn")
    }

    @Test
    fun spacesAreOptionalSeparators() {
        val f = filter(["bang bros"])
        listOf("bang bros", "bangbros", "bang-bros", "bang_bros", "bang   bros", "BANG+BROS").forEach { assertTrue(f.isBlocked(it), it) }
        assertFalse(f.isBlocked("bang the bros"))
    }

    @Test
    fun regexPartsKeepTheirMeaning() {
        val f = filter(["beaners?", "black(s|y)", "(as{2,}|booty) hole", "bimbos?"])
        assertTrue(f.isBlocked("beaner")); assertTrue(f.isBlocked("beaners"))
        assertTrue(f.isBlocked("blacky")); assertFalse(f.isBlocked("black"))
        assertTrue(f.isBlocked("asshole")); assertTrue(f.isBlocked("booty hole")); assertFalse(f.isBlocked("ashole"))
        assertTrue(f.isBlocked("bimbo"))
        assertEquals("beaners?", f.blockedBy("those beaners"))
    }

    @Test
    fun rawRegexIsUsedAsWritten() {
        val f = filter(["\\b{2}c\\"])
        assertTrue(f.isBlocked("BBC")); assertTrue(f.isBlocked("bbc news"))
        assertFalse(f.isBlocked("b8c"), "no look-alikes in a raw regex")
        assertFalse(f.isBlocked("abbc"))
    }

    @Test
    fun whitelistAndBlacklist() {
        val f = filter(censored = ["black"], whitelist = ["black ice", "black ?stone"], blacklist = ["shoot"])
        assertTrue(f.isBlocked("black"))
        assertFalse(f.isBlocked("watch out for black ice"))
        assertFalse(f.isBlocked("BLACK   ICE"))
        assertTrue(f.isBlocked("black ice and black"), "only the whitelisted phrase is excused")
        assertTrue(f.isBlocked("black"))
        // The blacklist sees the message as typed, so a whitelisted phrase doesn't hide it.
        val g = filter(whitelist = ["shoot the moon"], blacklist = ["shoot"])
        assertTrue(g.isBlocked("shoot the moon"))
    }

    @Test
    fun whitelistedWordsDontHideCensoredOnes() {
        val f = FilterConfig().also { it.compile() }
        assertNull(f.blockedBy("black"))
        assertNotNull(f.blockedBy("black cock"), "the next word is still checked")
        assertNotNull(f.blockedBy("no fuck"))
    }

    @Test
    fun disabledAndInvalid() {
        val f = FilterConfig(enabled = false, _censored = mutableListOf("ass"))
        f.compile()
        assertFalse(f.isBlocked("ass"))
        val g = filter(["fine", "bad(", "[oops", "also fine"])
        assertEquals(listOf("bad(", "[oops"), g.invalid)
        assertEquals(setOf("fine", "also fine"), g.censored.keys)
        assertNull(g.blockedBy("nothing here"))
        assertNotNull(g.blockedBy("this is fine"))
    }

    @Test
    fun realChatIsNotBlockedAndIsFast() {
        val f = FilterConfig().also { it.compile() }
        listOf(
            "hello everyone, anyone want to trade diamonds?", "lets go mining at the nether fortress", "the class assignment is due on Monday",
            "i love this server, the staff are great", "can someone help me with my claim", "good game, well played",
            "wow thats a nice build", "who wants to join my town", "brb getting food", "where is the shop",
            "no", "OK!", "pls help", "k", "need white wool and black concrete", "coming to the fox den", "playing soccer with mother",
        ).forEach { assertNull(f.blockedBy(it), it) }
        val long = "the quick brown fox jumps over the lazy dog and keeps running through the forest ".repeat(3).take(256)
        val start = System.nanoTime()
        repeat(200) { f.isBlocked(long) }
        val perMessage = (System.nanoTime() - start) / 200 / 1_000_000.0
        println("filter: %.2f ms per 256-character message".format(perMessage))
        assertTrue(perMessage < 50, "too slow: $perMessage ms")
    }
}
