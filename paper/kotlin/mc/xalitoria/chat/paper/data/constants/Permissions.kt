package mc.xalitoria.chat.paper.data.constants

import mc.xalitoria.color.*
import mc.xalitoria.util.bukkit.hasAnyPermission
import mc.xalitoria.util.bukkit.hasPermission
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import org.bukkit.permissions.Permissible

object Permissions {

    const val IGNORE          = "xalichat.ignore"

    const val FORMAT_GLOBAL   = "xalichat.format.global"
    const val FORMAT_NICKNAME = "xalichat.format.nickname"
    const val FORMAT_NICKNAME_OTHERS = "xalichat.format.nickname.others"
    const val FORMAT_SIGN      = "xalichat.format.sign"
    const val FORMAT_DISCORD   = "xalichat.format.discord"
    const val FORMAT_BROADCAST = "xalichat.format.broadcast"

    const val NICKNAME        = "xalichat.command.nickname"
    const val NICKNAME_OTHERS = "xalichat.command.nickname.others"

    const val MESSAGE = "xalichat.message"
    const val SPAM    = "xalichat.spam"
    const val RELOAD  = "xalichat.reload"

    const val SET_MUTE            = "xalichat.setting.mute"
    const val SET_MUTE_PLAYERS    = "xalichat.setting.mute.players"
    const val SET_MUTE_BROADCASTS = "xalichat.setting.mute.broadcasts"

    const val BYP_IGNORE          = "xalichat.bypass.ignore"
    const val BYP_MSG_IGNORE      = "xalichat.bypass.message.ignore"
    const val BYP_MUTE_PLAYERS    = "xalichat.bypass.mute.players"
    const val BYP_MUTE_BROADCASTS = "xalichat.bypass.mute.broadcasts"
    const val BYP_FILTER          = "xalichat.bypass.filter"

    const val CMD_ADMIN_SPY_COMMAND = "xalichat.admin.spy.command"
    const val CMD_ADMIN_SPY_SOCIAL  = "xalichat.admin.spy.social"

    const val CMD_ADMIN_CHATLOG        = "xalichat.admin.chatlog"
    const val CMD_ADMIN_CHATLOG_EXPORT = "xalichat.admin.chatlog.export"
    const val CMD_ADMIN_CHATLOG_IMPORT = "xalichat.admin.chatlog.import"
    const val CMD_ADMIN_BROADCAST      = "xalichat.admin.broadcast"
    const val CMD_ADMIN_BROADCAST_GLOBAL = "xalichat.admin.broadcast.global"
    const val CMD_ADMIN_ANNOUNCE        = "xalichat.admin.announce"
    const val CMD_ADMIN_ANNOUNCE_GLOBAL = "xalichat.admin.announce.global"

    const val PING = "xalichat.ping"

    const val INTERACTIVE_ITEM      = "xalichat.interactive.item"
    const val INTERACTIVE_INVENTORY = "xalichat.interactive.inventory"
    const val INTERACTIVE_ENDER     = "xalichat.interactive.ender"
    const val INTERACTIVE_PING      = "xalichat.interactive.ping"

    val DECOR_OBFUSCATED    = [ "obfuscated",    "magic",   "k" ]
    val DECOR_BOLD          = [ "bold",          "b",       "l" ]
    val DECOR_STRIKETHROUGH = [ "strikethrough", "st",      "m" ]
    val DECOR_UNDERLINE     = [ "underline",     "u",       "n" ]
    val DECOR_ITALIC        = [ "italic",        "i", "em", "o" ]
    val DECOR_RESET         = [ "reset",                    "r" ]

    val TAG_NBT = ["data", "nbt"]
    val TAG_SELECTOR = ["selector", "sel"]

    fun String.stripColors(
        target: Permissible,
        permissionPrefix: String = FORMAT_GLOBAL,
    ): String {
        // '<' catches raw MiniMessage tags (e.g. a typed <click:...>), which use neither '&' nor '{'.
        if ('&' !in this && '{' !in this && '<' !in this) return this
        val tagsToStrip = mutableSetOf<TagResolver>()

        var message = if (target.hasPermission(permissionPrefix, "gradient")) applyColors()
        else MiniMessage.builder().tags(StandardTags.gradient()).build().stripTags(stripCmiGradients())
        message = message.componentColors()

        if (!target.hasPermission(permissionPrefix, "color"))
            tagsToStrip += StandardTags.color()
        if (!target.hasPermission(permissionPrefix, "shadow"))
            tagsToStrip += StandardTags.shadowColor()


        val decorPermission = "$permissionPrefix.decor"
        if (!target.hasPermission(decorPermission, "*")) {
            if (!target.hasAnyPermission(decorPermission, DECOR_OBFUSCATED))
                tagsToStrip += StandardTags.decorations(TextDecoration.OBFUSCATED)
            if (!target.hasAnyPermission(decorPermission, DECOR_BOLD))
                tagsToStrip += StandardTags.decorations(TextDecoration.BOLD)
            if (!target.hasAnyPermission(decorPermission, DECOR_STRIKETHROUGH))
                tagsToStrip += StandardTags.decorations(TextDecoration.STRIKETHROUGH)
            if (!target.hasAnyPermission(decorPermission, DECOR_UNDERLINE))
                tagsToStrip += StandardTags.decorations(TextDecoration.UNDERLINED)
            if (!target.hasAnyPermission(decorPermission, DECOR_ITALIC))
                tagsToStrip += StandardTags.decorations(TextDecoration.ITALIC)
            if (!target.hasAnyPermission(decorPermission, DECOR_RESET))
                tagsToStrip += StandardTags.reset()
        }

        val tagPermission = "$permissionPrefix.tag"
        if (!target.hasPermission(tagPermission, "*")) {
            if (!target.hasPermission(tagPermission, "click"))
                tagsToStrip += StandardTags.clickEvent()
            if (!target.hasPermission(tagPermission, "head"))
                tagsToStrip += StandardTags.sequentialHead()
            if (!target.hasPermission(tagPermission, "hover"))
                tagsToStrip += StandardTags.hoverEvent()
            if (!target.hasPermission(tagPermission, "insert"))
                tagsToStrip += StandardTags.insertion()
            if (!target.hasPermission(tagPermission, "key"))
                tagsToStrip += StandardTags.keybind()
            if (!target.hasPermission(tagPermission, "score"))
                tagsToStrip += StandardTags.score()
            if (!target.hasPermission(tagPermission, "sprite"))
                tagsToStrip += StandardTags.sprite()
            if (!target.hasAnyPermission(tagPermission, TAG_NBT))
                tagsToStrip += StandardTags.nbt()
            if (!target.hasAnyPermission(tagPermission, TAG_SELECTOR))
                tagsToStrip += StandardTags.selector()
        }
        if (tagsToStrip.isEmpty()) return message
        return MiniMessage.builder().tags(TagResolver.resolver(tagsToStrip)).build().stripTags(message)
    }
}
