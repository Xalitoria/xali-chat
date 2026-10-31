import mc.xalitoria.chat.paper.util.Colorizer
import mc.xalitoria.util.message.PLAIN
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.permissions.Permissible
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Colorizer {
    private fun holding(vararg nodes: String): Permissible = Proxy.newProxyInstance(
        Permissible::class.java.classLoader, arrayOf(Permissible::class.java),
    ) { _, method, args -> method.name == "hasPermission" && args[0] in nodes } as Permissible

    /** Every component in the tree, the root first. */
    private fun Component.flatten(): List<Component> = listOf(this) + children().flatMap { it.flatten() }

    /** Every component with the style it ends up with, a child taking its parent's where it sets nothing itself. */
    private fun Component.styled(inherited: Style = Style.empty()): List<Pair<Component, Style>> {
        val style = style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        return listOf(this to style) + children().flatMap { it.styled(style) }
    }

    @Test
    fun trusted() {
        val component = Colorizer.parse("&cRed &lbold &#00ff00green <gradient:red:blue>fade</gradient>")
        assertEquals("Red bold green fade", PLAIN.serialize(component))
        val red = component.flatten().first { (it as? TextComponent)?.content() == "Red " }
        assertEquals(NamedTextColor.RED, red.color())
        val bold = component.flatten().first { (it as? TextComponent)?.content() == "bold " }
        assertEquals(TextDecoration.State.TRUE, bold.decoration(TextDecoration.BOLD))
        assertTrue(component.flatten().any { it.color()?.asHexString().equals("#00ff00", ignoreCase = true) })
    }

    @Test
    fun template() {
        val component = Colorizer.parse("&8[&6Broadcast&8] &f{0}", "&aHello")
        assertEquals("[Broadcast] Hello", PLAIN.serialize(component))
        assertTrue(component.flatten().any { (it as? TextComponent)?.content() == "Hello" && it.color() == NamedTextColor.GREEN })
    }

    @Test
    fun deniedFormattingIsStripped() {
        val sender = holding("xalichat.format.broadcast.color")
        val component = Colorizer.parse(
            "{0}", "&cred &lbold <click:run_command:/op me>click</click> <hover:show_text:hi>hover</hover>", sender,
        )
        assertEquals("red bold click hover", PLAIN.serialize(component))
        val parts = component.flatten()
        assertTrue(parts.any { it.color() == NamedTextColor.RED })
        assertTrue(parts.none { it.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE })
        assertTrue(parts.none { it.clickEvent() != null || it.hoverEvent() != null })
    }

    @Test
    fun allowedFormattingStays() {
        val sender = holding("xalichat.format.broadcast.decor.bold", "xalichat.format.broadcast.tag.click")
        val component = Colorizer.parse("{0}", "&lbold <click:open_url:https://example.com>link</click> &cnot red", sender)
        val parts = component.flatten()
        assertTrue(parts.any { it.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE })
        assertTrue(parts.any { it.clickEvent()?.action() == ClickEvent.Action.OPEN_URL })
        assertTrue(parts.none { it.color() == NamedTextColor.RED })
    }

    @Test
    fun strippedTagCannotBuildACode() {
        // "&<b>c" loses the tag and must stay the literal text "&c", not turn into a red color code afterwards.
        val component = Colorizer.parse("{0}", "&<b>c", holding())
        assertEquals("&c", PLAIN.serialize(component))
        assertNull(component.flatten().firstNotNullOfOrNull { it.color() })
        assertNotNull(component)
    }

    @Test
    fun wildcardAllowsEveryTag() {
        val component = Colorizer.parse("{0}", "<click:run_command:/say hi>x</click>", holding("xalichat.format.broadcast.tag.*"))
        assertTrue(component.flatten().any { it.clickEvent() != null })
    }

    @Test
    fun colorCodesKeepAnOpenClickEvent() {
        val component = Colorizer.parse("<click:open_url:'https://discord.gg/myserver'>&b&lClick Here to Join the &9&lDiscord</click>")
        assertEquals("Click Here to Join the Discord", PLAIN.serialize(component))
        val parts = component.styled().filter { (it.first as? TextComponent)?.content()?.isNotEmpty() == true }
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.second.clickEvent()?.action() == ClickEvent.Action.OPEN_URL })
        assertEquals(NamedTextColor.AQUA, parts[0].second.color())
        assertEquals(NamedTextColor.BLUE, parts[1].second.color())
        assertTrue(parts.all { it.second.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE })
    }

    @Test
    fun hexAndNamedColorsKeepAnOpenClickEvent() {
        for (colors in listOf("&#ff0000A &#0000ffB", "{#red}A {#blue}B", "&x&f&f&0&0&0&0A &x&0&0&f&f&0&0B")) {
            val component = Colorizer.parse("<click:open_url:'https://example.com'>$colors</click>")
            assertEquals("A B", PLAIN.serialize(component), colors)
            val parts = component.styled().filter { (it.first as? TextComponent)?.content()?.isNotEmpty() == true }
            assertTrue(parts.all { it.second.clickEvent()?.action() == ClickEvent.Action.OPEN_URL }, colors)
        }
    }

    @Test
    fun aColorCodeEndsEarlierDecorations() {
        for (code in listOf("&c", "&#ff0000", "{#red}")) {
            val component = Colorizer.parse("&lbold ${code}plain")
            val plain = component.styled().first { (it.first as? TextComponent)?.content() == "plain" }
            assertTrue(plain.second.decoration(TextDecoration.BOLD) != TextDecoration.State.TRUE, code)
        }
    }
}
