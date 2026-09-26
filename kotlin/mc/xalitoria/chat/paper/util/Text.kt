package mc.xalitoria.chat.paper.util

import mc.xalitoria.color.applyCmiGradients
import mc.xalitoria.color.componentColors
import mc.xalitoria.util.message.MINI
import net.kyori.adventure.text.Component
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

/** Parses untrusted text such as logged chat: formatting shows, but no click, hover or other active tags. */
fun String.toSafeComponent(): Component = unescapeGradients().applyCmiGradients().componentColors().let(CHAT_MINI::deserialize)

fun String.toComponent(): Component =
    applyCmiGradients()
    .componentColors()
    .let(MINI::deserialize)

//fun String.toEscapedComponent(): String = unescapeGradients().applyCmiGradients().let(MINI::escapeTags).componentColors()
