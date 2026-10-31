package mc.xalitoria.chat.paper.util

import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.color.componentColors
import mc.xalitoria.util.message.MINI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import org.bukkit.permissions.Permissible

/**
 * Turns `&` codes, hex codes, CMI gradients and MiniMessage tags into a [Component]; the color module's
 * `componentColors()` turns the codes into tags. Only broadcasts and announcements use it; everything else still goes
 * through the shared color parser, which is being reworked.
 */
object Colorizer {
    /** Each tag with the `<prefix>.` permission suffixes that allow it; holding any one of them is enough. */
    private val gated: List<Pair<List<String>, TagResolver>> = listOf(
        listOf("color") to StandardTags.color(),
        listOf("shadow") to StandardTags.shadowColor(),
        listOf("gradient") to StandardTags.gradient(),
        listOf("gradient") to StandardTags.rainbow(),
        Permissions.DECOR_OBFUSCATED.map { "decor.$it" } + "decor.*" to StandardTags.decorations(TextDecoration.OBFUSCATED),
        Permissions.DECOR_BOLD.map { "decor.$it" } + "decor.*" to StandardTags.decorations(TextDecoration.BOLD),
        Permissions.DECOR_STRIKETHROUGH.map { "decor.$it" } + "decor.*" to StandardTags.decorations(TextDecoration.STRIKETHROUGH),
        Permissions.DECOR_UNDERLINE.map { "decor.$it" } + "decor.*" to StandardTags.decorations(TextDecoration.UNDERLINED),
        Permissions.DECOR_ITALIC.map { "decor.$it" } + "decor.*" to StandardTags.decorations(TextDecoration.ITALIC),
        Permissions.DECOR_RESET.map { "decor.$it" } + "decor.*" to StandardTags.reset(),
        listOf("tag.click", "tag.*") to StandardTags.clickEvent(),
        listOf("tag.head", "tag.*") to StandardTags.sequentialHead(),
        listOf("tag.hover", "tag.*") to StandardTags.hoverEvent(),
        listOf("tag.insert", "tag.*") to StandardTags.insertion(),
        listOf("tag.key", "tag.*") to StandardTags.keybind(),
        listOf("tag.score", "tag.*") to StandardTags.score(),
        listOf("tag.sprite", "tag.*") to StandardTags.sprite(),
        Permissions.TAG_NBT.map { "tag.$it" } + "tag.*" to StandardTags.nbt(),
        Permissions.TAG_SELECTOR.map { "tag.$it" } + "tag.*" to StandardTags.selector(),
    )

    /**
     * Parses [template], a trusted string, with [text] in place of its `{0}`. When [sender] is given, [text] keeps only
     * the formatting `<prefix>.*` permissions allow them (e.g. `xalichat.format.broadcast.color`) and the rest is
     * stripped; without one, [text] is as trusted as the template. Codes are converted before the two are joined, so
     * text can't build a code out of the gap a stripped tag leaves.
     */
    fun parse(
        template: String,
        text: String = "",
        sender: Permissible? = null,
        prefix: String = Permissions.FORMAT_BROADCAST,
    ): Component {
        var body = text.componentColors()
        if (sender != null) {
            val denied = gated.filter { entry -> entry.first.none { sender.hasPermission("$prefix.$it") } }.map { it.second }
            if (denied.isNotEmpty()) body = MiniMessage.builder().tags(TagResolver.resolver(denied)).build().stripTags(body)
        }
        return MINI.deserialize(template.componentColors().replace("{0}", body))
    }
}
