package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.reflect.StructureModifier
import com.comphenix.protocol.wrappers.WrappedChatComponent
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import com.xalitoria.util.message.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.TranslationArgument
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.inventory.ItemStack
import java.util.Optional

private const val CLICK_TOKEN = "xalicolorclick"

/**
 * Colors the component by a round trip through MiniMessage. Click values are not text to color: a copied or suggested
 * command must reach the player as it was, so they leave the component for the round trip and come back after it.
 */
internal fun Component.protocolor(): Component {
    val clicks = ArrayList<ClickEvent<*>>()
    val shielded = mapClicks { event ->
        // Only a text value (a command, a link, text to copy) is shielded; a page number or a callback has nothing to color.
        if (event.payload() !is ClickEvent.Payload.Text) return@mapClicks event
        clicks += event
        @Suppress("UNCHECKED_CAST")
        ClickEvent.clickEvent(event.action() as ClickEvent.Action<ClickEvent.Payload.Text>, ClickEvent.Payload.string("$CLICK_TOKEN${clicks.lastIndex}"))
    }
    if (clicks.isEmpty()) return MINI.serialize(this).toComponent()
    return MINI.serialize(shielded).toComponent().mapClicks { event ->
        val value = (event.payload() as? ClickEvent.Payload.Text)?.value()
        val index = value?.removePrefix(CLICK_TOKEN)?.takeIf { value != it }?.toIntOrNull()
        clicks.getOrNull(index ?: return@mapClicks event) ?: event
    }
}

/** Every click event in the component, its children, its hover text and its translation arguments, mapped. */
private fun Component.mapClicks(transform: (ClickEvent<*>) -> ClickEvent<*>): Component {
    var result = this
    clickEvent()?.let { result = result.clickEvent(transform(it)) }
    hoverEvent()?.let { hover ->
        val value = hover.value()
        if (hover.action() == HoverEvent.Action.SHOW_TEXT && value is Component)
            result = result.hoverEvent(HoverEvent.showText(value.mapClicks(transform)))
    }
    if (children().isNotEmpty()) result = result.children(children().map { it.mapClicks(transform) })
    if (result is TranslatableComponent && result.arguments().isNotEmpty())
        result = result.arguments(result.arguments().map { argument ->
            val value = argument.value()
            if (value is Component) TranslationArgument.component(value.mapClicks(transform)) else argument
        })
    return result
}

internal fun WrappedChatComponent.protocolor(): WrappedChatComponent {
    val rawJson = json
    if (rawJson.isNullOrBlank()) return this
    return GCOMP.deserialize(rawJson)
        .protocolor()
        .let(GCOMP::serialize)
        .let(WrappedChatComponent::fromJson)
}

internal fun StructureModifier<WrappedChatComponent>.protocolor(index: Int = 0) {
    if (size() <= index) return
    readSafely(index)?.let { write(index, it.protocolor()) }
}

internal fun ItemStack.protocolor(): ItemStack {
    if (type.isAir || !hasItemMeta()) return this
    return clone().apply {
        val meta = itemMeta ?: return@apply
        if (meta.hasCustomName()) meta.customName(meta.customName()?.protocolor())
        if (meta.hasLore()) meta.lore(meta.lore()?.map { it.protocolor() })
        itemMeta = meta
    }
}

internal fun WrappedDataWatcher.protocolor(): WrappedDataWatcher {
    for (item in watchableObjects) {
        item ?: continue
        val newValue = item.value?.protocolor()
        if (newValue != item.value) item.value = newValue
    }
    return this
}

internal fun Any.protocolor(): Any = when (this) {
    is Optional<*> -> {
        val present = orElse(null) ?: return this
        val processed = present.protocolor()
        if (processed !== present) Optional.ofNullable(processed) else this
    }

    is WrappedChatComponent -> {
        // Colorize it as a component, then hand back the raw NMS handle, which is what the packet field holds.
        val component: WrappedChatComponent = this
        val colorized = component.protocolor()
        if (colorized.json != component.json) colorized.handle else handle
    }

    is String ->
        if (isBlank()) this
        else replace("\\n", "\n").toComponent().let(MINI::serialize)

    else -> this
}
