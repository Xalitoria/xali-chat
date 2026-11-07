import mc.xalitoria.chat.paper.external.protocol.protocolor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class Protocolor {
    private fun Component.flatten(): List<Component> = listOf(this) + children().flatMap { it.flatten() }

    /** The text of a click that carries one (a command, a link, text to copy). */
    private fun ClickEvent<*>.text(): String? = (payload() as? ClickEvent.Payload.Text)?.value()

    @Test
    fun clickValueStaysUnmodified() {
        val command = "/lp group comet meta removeprefix 10 &l{#ffdd22>}ᴄᴏᴍᴇᴛ{#ffff99<}"
        val component = Component.text("[click] &cred").clickEvent(ClickEvent.copyToClipboard(command))
        val result = component.protocolor()
        val clicks = result.flatten().mapNotNull { it.clickEvent() }
        assertEquals(true, clicks.isNotEmpty())
        clicks.forEach { assertEquals(command, it.text()) }
    }

    @Test
    fun otherActionsAndNestedClicksStayUnmodified() {
        val value = "&c/say {#ffdd22>}hi{#ffff99<}"
        val inner = Component.text("inner").clickEvent(ClickEvent.suggestCommand(value))
        val component = Component.text("&aouter ").clickEvent(ClickEvent.runCommand(value))
            .append(inner)
            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("tip").clickEvent(ClickEvent.openUrl("https://example.com/&c"))))
        val result = component.protocolor()
        val clicks = result.flatten().mapNotNull { it.clickEvent() }.map { it.action() to it.text() }.toSet()
        assertEquals(setOf<Pair<ClickEvent.Action<*>, String?>>(ClickEvent.Action.RUN_COMMAND to value, ClickEvent.Action.SUGGEST_COMMAND to value), clicks)
        val text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(result)
        assertEquals("outer inner", text)
    }

    @Test
    fun pageClickSurvives() {
        val result = Component.text("next").clickEvent(ClickEvent.changePage(3)).protocolor()
        val click = result.flatten().firstNotNullOf { it.clickEvent() }
        assertEquals(ClickEvent.Action.CHANGE_PAGE, click.action())
        assertEquals(3, (click.payload() as ClickEvent.Payload.Int).integer())
    }
}
