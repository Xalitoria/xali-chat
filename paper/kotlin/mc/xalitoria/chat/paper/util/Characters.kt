package mc.xalitoria.chat.paper.util

import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.constants.Permissions
import net.kyori.adventure.text.Component
import mc.xalitoria.util.message.PLAIN
import mc.xalitoria.util.message.toComponent
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import java.util.regex.Pattern

/**
 * What chat may contain under `characters`: the ASCII letters, digits, symbols and the space (U+0020 to U+007E), the
 * characters of [allow], and private-use characters, which plugins such as Nexo and ItemsAdder turn their `:glyph:`
 * names into and a resource pack draws. Everything else (emoji, accents, other alphabets, tabs, line breaks, `§` and the
 * rest of the control characters) is out. Characters are whole code points, so an emoji is one character.
 */
class CharacterRule(allow: String) {
    private val extra: Set<Int> = allow.codePoints().toArray().toSet()

    fun allows(codePoint: Int): Boolean =
        codePoint in BASIC || codePoint in extra || Character.getType(codePoint) == Character.PRIVATE_USE.toInt()

    /** The characters of [text] that are not allowed, each once, in the order they first appear. */
    fun disallowed(text: String): List<Int> = LinkedHashSet<Int>().also { out -> text.codePoints().forEach { if (!allows(it)) out += it } }.toList()

    companion object {
        private val BASIC = 0x20..0x7E

        /** [text] without the code points in [remove]. */
        fun strip(text: String, remove: Collection<Int>): String {
            val gone = remove.toSet()
            return buildString { text.codePoints().forEach { if (it !in gone) appendCodePoint(it) } }
        }

        /**
         * [codePoints] for a message to the player: the character with its code (`é (U+00E9)`), or the code alone for
         * one that doesn't print (a tab, `§`, which could change the message's colors) or lies beyond the first plane
         * (an emoji, which the message formatting would split). Past [limit] it says how many more.
         */
        fun describe(codePoints: List<Int>, limit: Int = 5): String {
            val shown = codePoints.take(limit).joinToString(", ") { cp ->
                val printable = cp <= 0xFFFF && !Character.isISOControl(cp) && !Character.isWhitespace(cp) && cp != '§'.code &&
                    Character.getType(cp) != Character.FORMAT.toInt() && Character.isDefined(cp)
                if (printable) "${String(Character.toChars(cp))} (U+%04X)".format(cp) else "U+%04X".format(cp)
            }
            val more = codePoints.size - limit
            return if (more > 0) "$shown and $more more" else shown
        }
    }
}

/** [this] without the code points in [remove], keeping its colors, hover and click events and the rest of its structure. */
fun Component.withoutCharacters(remove: Collection<Int>): Component {
    if (remove.isEmpty()) return this
    val pattern = Pattern.compile(remove.toSet().joinToString("", "[", "]") { "\\x{%X}".format(it) })
    return replaceText(TextReplacementConfig.builder().match(pattern).replacement("").build())
}

/** What `characters` makes of a message. */
sealed interface CharacterCheck {
    /** Nothing to do: the limit is off, the sender is exempt, or what they typed is all allowed. */
    data object Allowed : CharacterCheck

    /** `mode: alert`: send it as it is; staff have been told. */
    class Alerted(val characters: String) : CharacterCheck

    /** `mode: strip`: send it without the code points in [remove]; [characters] says what they are. */
    class Strip(val remove: List<Int>, val characters: String) : CharacterCheck

    /** `mode: block`: refuse it, telling the sender about [characters]. */
    class Block(val characters: String) : CharacterCheck
}

/**
 * Applies `characters` to [typed], what [sender] typed (not what other plugins made of it, whose glyphs and symbols are
 * not the player's doing): public chat, or a private message when [privateMessage] is set and `characters.messages` is
 * on. Operators and holders of `xalichat.bypass.characters` are not limited. Under `alert` the staff are told here.
 */
fun XaliChat.checkCharacters(sender: Player, typed: String, privateMessage: Boolean): CharacterCheck {
    val settings = config.data.characters
    val action = settings.action
    if (action == MainConfig.Characters.Action.ALLOW || (privateMessage && !settings.messages)) return CharacterCheck.Allowed
    if (sender.isOp || sender.hasPermission(Permissions.BYP_CHARACTERS)) return CharacterCheck.Allowed
    val bad = characterRule.disallowed(typed)
    if (bad.isEmpty()) return CharacterCheck.Allowed
    val described = CharacterRule.describe(bad)
    return when (action) {
        MainConfig.Characters.Action.ALERT -> {
            alertStaff(sender, described, privateMessage)
            CharacterCheck.Alerted(described)
        }
        MainConfig.Characters.Action.STRIP -> CharacterCheck.Strip(bad, described)
        else -> CharacterCheck.Block(described)
    }
}

/** Tells the console and everyone online with `xalichat.admin.alert.characters`. The text itself is not repeated: it may be private. */
private fun XaliChat.alertStaff(sender: Player, characters: String, privateMessage: Boolean) {
    val messages = messageConfig.data.commands.characters
    val text = if (privateMessage) messages.alertMessage(sender.name, characters) else messages.alertChat(sender.name, characters)
    debug("Character alert for {0}: {1}", sender.name, characters)
    val component = text.toComponent()
    logger.info(PLAIN.serialize(component))
    server.onlinePlayers.filter { it.hasPermission(Permissions.CMD_ADMIN_ALERT_CHARACTERS) }
        .forEach { staff -> staff.runOwned(this) { staff.sendMessage(component) } }
}
