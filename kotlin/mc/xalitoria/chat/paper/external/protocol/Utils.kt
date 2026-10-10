package mc.xalitoria.chat.paper.external.protocol

import com.comphenix.protocol.reflect.StructureModifier
import com.comphenix.protocol.wrappers.WrappedChatComponent
import com.comphenix.protocol.wrappers.WrappedDataWatcher
//import kotlinx.serialization.json.*
//import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.util.message.*
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import java.util.Optional

/*
private val debug = mutableSetOf<String>()
private var reset = 0L

private fun JsonElement.stripFalseValues(): JsonElement = when (this) {
    is JsonObject -> this
        .filterValues { !(it is JsonPrimitive && it.booleanOrNull == false) }
        .mapValues { it.value.stripFalseValues() }
        .let(::JsonObject)
    is JsonArray -> JsonArray(map { it.stripFalseValues() })
    else -> this
}

internal fun Component.protocolor(): Component {
    val now = System.currentTimeMillis()
    if (now > reset) {
        debug.clear()
        reset = now + 5000L
    }

    val before = MINI.serialize(this)
    val result = before.toComponent()

    if (debug.add(before)) {
        val after = GCOMP.serialize(result)
            .let(Json::parseToJsonElement)
            .stripFalseValues()
            .let(Json::encodeToString)

        debug("protocolor: {0} -> {1}", before, after)
    }

    return result
}
*/

internal fun Component.protocolor(): Component = MINI.serialize(this).toComponent()

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