package mc.xalitoria.chat.paper.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Click-through pagination for command output; works for players and console. */
object ChatMenu {
    data class Page<T>(val items: List<T>, val page: Int, val totalPages: Int, val totalItems: Int)

    fun <T> paginate(items: List<T>, page: Int, pageSize: Int): Page<T> {
        val totalPages = max(1, ceil(items.size / pageSize.toDouble()).toInt())
        val clamped = page.coerceIn(1, totalPages)
        val from = (clamped - 1) * pageSize
        val slice = if (from >= items.size) emptyList() else items.subList(from, min(items.size, from + pageSize))
        return Page(slice, clamped, totalPages, items.size)
    }

    /**
     * Builds a message with [entries] followed by a `«Prev` / `Next»` footer. Clicking a footer
     * link re-runs [commandForPage] with the target page number; hovering shows a short preview.
     */
    fun render(
        entries: List<Component>,
        page: Int,
        totalPages: Int,
        commandForPage: (Int) -> String,
        prevLabel: Component,
        nextLabel: Component,
    ): Component {
        val body = Component.text()
        entries.forEach { body.append(it).appendNewline() }

        val footer = Component.text()
        if (page > 1) {
            footer.append(
                prevLabel
                    .clickEvent(ClickEvent.runCommand(commandForPage(page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Page ${page - 1}")))
            )
        }
        footer.append(Component.text(" $page/$totalPages "))
        if (page < totalPages) {
            footer.append(
                nextLabel
                    .clickEvent(ClickEvent.runCommand(commandForPage(page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Page ${page + 1}")))
            )
        }

        return body.append(footer.build()).build()
    }
}
