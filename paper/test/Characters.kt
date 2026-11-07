import mc.xalitoria.chat.paper.util.CharacterRule
import mc.xalitoria.chat.paper.util.withoutCharacters
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Characters {
    private val basic = CharacterRule("")

    @Test
    fun theBasicKeyboardIsAllowed() {
        val everything = (0x20..0x7E).map { it.toChar() }.joinToString("")
        assertTrue(basic.disallowed(everything).isEmpty())
    }

    @Test
    fun accentsEmojiAndControlCharactersAreNot() {
        val text = "héllo 😀 wörld\t§c"
        // é, the emoji (one code point), ö, tab and § - each once, in order
        val bad = basic.disallowed(text)
        assertEquals(listOf(0xE9, 0x1F600, 0xF6, 0x09, 0xA7), bad)
        assertEquals("hllo  wrld" + "c", CharacterRule.strip(text, bad))
    }

    @Test
    fun privateUseGlyphsOfOtherPluginsAreNeverTouched() {
        // Nexo and ItemsAdder draw their glyphs from the private-use areas, in the basic plane and beyond it.
        assertTrue(basic.disallowed("a  b  c 󰀀 d").isEmpty())
    }

    @Test
    fun extraCharactersCanBeAllowed() {
        val rule = CharacterRule("éñ😀")
        assertTrue(rule.disallowed("señor é 😀").isEmpty())
        assertEquals(listOf(0xFC), rule.disallowed("über"))
    }

    @Test
    fun aMessageToThePlayerNamesTheCharactersSafely() {
        assertEquals("é (U+00E9), U+0009, U+00A7", CharacterRule.describe(listOf(0xE9, 0x09, 0xA7)))
        assertEquals("U+1F600", CharacterRule.describe(listOf(0x1F600)))
        assertEquals("a (U+0061), b (U+0062) and 1 more", CharacterRule.describe(listOf(0x61, 0x62, 0x63), limit = 2))
    }

    @Test
    fun strippingAComponentKeepsWhatOtherPluginsPutInIt() {
        val message = Component.text("héllo ").append(Component.text("wörld ", NamedTextColor.RED))
        val stripped = message.withoutCharacters(listOf(0xE9, 0xF6))
        assertEquals("hllo wrld ", PlainTextComponentSerializer.plainText().serialize(stripped))
        // the red part is still red, and still holds its glyph
        // (a split-off piece inherits the color from its parent, so the color is followed down the tree)
        fun red(c: Component, inherited: net.kyori.adventure.text.format.TextColor?): String {
            val color = c.color() ?: inherited
            return (if (c is TextComponent && color == NamedTextColor.RED) c.content() else "") + c.children().joinToString("") { red(it, color) }
        }
        assertEquals("wrld \uE001", red(stripped, null))
    }
}
