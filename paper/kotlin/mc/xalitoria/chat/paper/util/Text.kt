package mc.xalitoria.chat.paper.util

import mc.xalitoria.color.applyColors
import mc.xalitoria.color.componentColors
import mc.xalitoria.util.message.MINI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

private val escapedGradientEnd = Regex("""\\<(?=>?})""")

/** Restores the `<` of `{#name<}` markers that `MiniMessage.serialize` escaped, so gradients survive a round trip. */
fun String.unescapeGradients(): String = replace(escapedGradientEnd, "<")

/** Colors, decorations, gradients and rainbows only; nothing a viewer could click or hover. */
val CHAT_MINI: MiniMessage = MiniMessage.builder()
    .tags(TagResolver.resolver(
        StandardTags.color(),
        StandardTags.shadowColor(),
        StandardTags.decorations(),
        StandardTags.gradient(),
        StandardTags.rainbow(),
        StandardTags.reset(),
        //StandardTags.newline(),
    ))
    .build()

/**
 * Drops click and hover interactivity from this component and its children, recursively. `MiniMessage.stripTags`
 * can't do this selectively: passing it any tag resolver strips every tag it knows, default set included, which
 * would wipe colors too. Parsing with the full tag set and then clearing events off the resulting tree keeps
 * colors, decorations and gradients intact while leaving nothing a viewer could click or hover.
 */
private fun Component.withoutInteractivity(): Component =
    clickEvent(null).hoverEvent(null as HoverEvent<*>?).children(children().map { it.withoutInteractivity() })

/**
 * Parses untrusted text such as logged chat so it reads like the original message did: colors, decorations, and
 * gradients show, but click, hover and other active tags are removed rather than left as literal `<tag>` text.
 */
fun String.toSafeComponent(): Component =
    unescapeGradients().applyColors().componentColors().let(MINI::deserialize).withoutInteractivity()
