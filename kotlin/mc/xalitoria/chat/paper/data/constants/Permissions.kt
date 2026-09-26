package mc.xalitoria.chat.paper.data.constants

import mc.xalitoria.color.*
import mc.xalitoria.util.bukkit.hasAnyPermission
import mc.xalitoria.util.bukkit.hasPermission
import mc.xalitoria.util.message.MINI
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import org.bukkit.permissions.Permissible

object Permissions {

    const val IGNORE          = "xalichat.ignore"

    const val FORMAT_GLOBAL   = "xalichat.format.global"
    const val FORMAT_DISCORD  = "xalichat.format.discord"
    const val FORMAT_NICKNAME = "xalichat.format.nickname"
    const val FORMAT_NICKNAME_OTHERS = "xalichat.format.nickname.others"

    const val NICKNAME        = "xalichat.command.nickname"
    const val NICKNAME_OTHERS = "xalichat.command.nickname.others"

    const val SET_MUTE            = "xalichat.setting.mute"
    const val SET_MUTE_PLAYERS    = "xalichat.setting.mute.players"
    const val SET_MUTE_BROADCASTS = "xalichat.setting.mute.broadcasts"

    const val BYP_IGNORE          = "xalichat.bypass.ignore"
    const val BYP_MSG_IGNORE      = "xalichat.bypass.message.ignore"
    const val BYP_MUTE_PLAYERS    = "xalichat.bypass.mute.players"
    const val BYP_MUTE_BROADCASTS = "xalichat.bypass.mute.broadcasts"

    const val SPY_COMMAND = "xalichat.admin.spy.command"
    const val SPY_SOCIAL  = "xalichat.admin.spy.social"

    const val CHATLOG   = "xalichat.admin.chatlog"

    const val PING = "xalichat.ping"

    val DECOR_OBFUSCATED    = [ "obfuscated",    "magic",   "k" ]
    val DECOR_BOLD          = [ "bold",          "b",       "l" ]
    val DECOR_STRIKETHROUGH = [ "strikethrough", "st",      "m" ]
    val DECOR_UNDERLINE     = [ "underline",     "u",       "n" ]
    val DECOR_ITALIC        = [ "italic",        "i", "em", "o" ]
    val DECOR_RESET         = [ "reset",                    "r" ]

    val TAG_NBT = ["data", "nbt"]
    val TAG_SELECTOR = ["selector", "sel"]

    val TEXT_ALNUM  = [ "alphanumeric", "alnum" ]
    val TEXT_ANY    = [ "any" ]

    fun String.stripColors(
        target: Permissible,
        permissionPrefix: String = FORMAT_GLOBAL,
    ): String {
        if ('&' !in this && '{' !in this) return this
        var message =
            if (!target.hasPermission(permissionPrefix, "gradient"))
                MINI.stripTags(stripCmiGradients(), StandardTags.gradient()).componentColors()
            else
                applyCmiGradients().componentColors()
        if (!target.hasPermission(permissionPrefix, "color"))
            message = MINI.stripTags(message, StandardTags.color())
        if (!target.hasPermission(permissionPrefix, "shadow"))
            message = MINI.stripTags(message, StandardTags.shadowColor())


        val decorPermission = "$permissionPrefix.decor"
        if (!target.hasPermission(decorPermission, "*")) {
            if (!target.hasAnyPermission(decorPermission, DECOR_OBFUSCATED))
                message = MINI.stripTags(message, StandardTags.decorations(TextDecoration.OBFUSCATED))
            if (!target.hasAnyPermission(decorPermission, DECOR_BOLD))
                message = MINI.stripTags(message, StandardTags.decorations(TextDecoration.BOLD))
            if (!target.hasAnyPermission(decorPermission, DECOR_STRIKETHROUGH))
                message = MINI.stripTags(message, StandardTags.decorations(TextDecoration.STRIKETHROUGH))
            if (!target.hasAnyPermission(decorPermission, DECOR_UNDERLINE))
                message = MINI.stripTags(message, StandardTags.decorations(TextDecoration.UNDERLINED))
            if (!target.hasAnyPermission(decorPermission, DECOR_ITALIC))
                message = MINI.stripTags(message, StandardTags.decorations(TextDecoration.ITALIC))
            if (!target.hasAnyPermission(decorPermission, DECOR_RESET))
                message = MINI.stripTags(message, StandardTags.reset())
        }

        val tagPermission = "$permissionPrefix.tag"
        if (!target.hasPermission(tagPermission, "*")) {
            if (!target.hasPermission(tagPermission, "click"))
                message = MINI.stripTags(message, StandardTags.clickEvent())
            if (!target.hasPermission(tagPermission, "head"))
                message = MINI.stripTags(message, StandardTags.sequentialHead())
            if (!target.hasPermission(tagPermission, "hover"))
                message = MINI.stripTags(message, StandardTags.hoverEvent())
            if (!target.hasPermission(tagPermission, "insert"))
                message = MINI.stripTags(message, StandardTags.insertion())
            if (!target.hasPermission(tagPermission, "key"))
                message = MINI.stripTags(message, StandardTags.keybind())
            if (!target.hasPermission(tagPermission, "score"))
                message = MINI.stripTags(message, StandardTags.score())
            if (!target.hasPermission(tagPermission, "sprite"))
                message = MINI.stripTags(message, StandardTags.sprite())
            if (!target.hasAnyPermission(tagPermission, TAG_NBT))
                message = MINI.stripTags(message, StandardTags.nbt())
            if (!target.hasAnyPermission(tagPermission, TAG_SELECTOR))
                message = MINI.stripTags(message, StandardTags.selector())
        }
        return message
    }
}
