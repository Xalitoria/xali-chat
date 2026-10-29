package mc.xalitoria.chat.paper.data.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.util.message.formatArgs
import mc.xalitoria.util.serial.message.State

@Serializable
data class MessageConfig(
    val commands: Commands = Commands()
) {
    @Serializable
    data class Commands(
        @SerialName("no-permission")
        val noPermission: String = "No permission.",
        @SerialName("player-only")
        val playerCommandOnly: String = "You must be a player to use this command.",
        @SerialName("unexpected-error")
        val unexpectedError: String = "An unexpected error occurred. Please try again.",
        private val help: String = """
            {#a0a>}&lXALIC{#fff<}&f&lHAT &7> &dHELP
            &d/{0} &rreload
            &d/{0} &r<command> &7- every x-command runs here without the x, e.g. &f/{0} broadcast
        """.trimIndent(),
        val message: Message = Message(),
        val reload: Reload = Reload(),
        val spam: Spam = Spam(),
        val ignore: Ignore = Ignore(),
        val mute: Mute = Mute(),
        @SerialName("command-spy")
        val commandSpy: CommandSpy = CommandSpy(),
        @SerialName("social-spy")
        val socialSpy: SocialSpy = SocialSpy(),
        @SerialName("chat-log")
        val chatLog: ChatLog = ChatLog(),
        val nickname: Nickname = Nickname(),
        val interactive: Interactive = Interactive(),
        val broadcast: Broadcast = Broadcast(),
        val announce: Announce = Announce(),
        val filter: Filter = Filter(),
    ) {
        fun help(label: String): String = help.formatArgs(label)
        @Serializable
        data class Message(
            private val usage: String = "Usage: &d/{0} &r<target> [message]",
            @SerialName("reply-usage")
            private val replyUsage: String = "Usage: &d/{0} &r<message>",
            @SerialName("no-reply")
            val noReply: String = "You have no one to reply to.",
            val player: Recipient = Recipient(),
            val console: Recipient = Recipient(),
            val self: String = "You can't message yourself!",
            @SerialName("target-invalid")
            private val targetInvalid: String = "Player '{0}' not found!",
            @SerialName("target-offline")
            private val targetOffline: String = "{0} is offline.",
            @SerialName("no-players")
            val noPlayers: String = "No other players are online!",
            @SerialName("declined")
            private val declined: String = "{0} isn't accepting messages from you.",
        ) {
            fun targetOffline(playerName: String): String = targetOffline.formatArgs(playerName)
            fun declined(playerName: String): String = declined.formatArgs(playerName)
            @Serializable
            data class Recipient(
                private val sender: String = "&7To {1}: {2}",
                private val target: String = "&7From {0}: {2}",
            ) {
                /** [resolve] expands placeholders in the raw template, before [message] is inserted. */
                fun sender(senderName: String, targetName: String, message: String, resolve: (String) -> String = { it }): String =
                    resolve(sender).formatArgs(senderName, targetName, message)
                fun target(senderName: String, targetName: String, message: String, resolve: (String) -> String = { it }): String =
                    resolve(target).formatArgs(senderName, targetName, message)
            }
            fun usage(label: String): String = usage.formatArgs(label)
            fun replyUsage(label: String): String = replyUsage.formatArgs(label)
            fun targetInvalid(playerName: String): String = targetInvalid.formatArgs(playerName)
        }

        @Serializable
        data class Reload(
            val pass: String = "{#a0a>}&lXALIC{#fff<}&f&lHAT &7> &aReloaded!"
        )

        @Serializable
        data class Spam(
            private val usage: String = """{#a0a>}&lXALIS{#fff<}PAM &dHELP
                &d/{0} &rsay <repeat> <message>
                &d/{0} &rstop
            """.trimIndent(),
            val stop: String = "{#a0a>}&lXALIS{#fff<}PAM &aStopped spamming!",
        ) {
            fun usage(label: String): String = usage.formatArgs(label)
        }

        @Serializable
        data class Ignore(
            private val usage: String = "Usage: &d/{0} &r<player>",
            val self: String = "You can't ignore yourself!",
            private val added: String = "You are now ignoring {0}.",
            private val removed: String = "You are no longer ignoring {0}.",
            @SerialName("target-invalid")
            private val targetInvalid: String = "Player '{0}' not found!",
            @SerialName("blocked-by-target")
            val blockedByTarget: String = "You cannot message this player.",
            private val exempt: String = "{0} can't be ignored.",
        ) {
            fun exempt(targetName: String): String = exempt.formatArgs(targetName)
            fun usage(label: String): String = usage.formatArgs(label)
            fun added(targetName: String): String = added.formatArgs(targetName)
            fun removed(targetName: String): String = removed.formatArgs(targetName)
            fun targetInvalid(playerName: String): String = targetInvalid.formatArgs(playerName)
        }

        @Serializable
        data class Mute(
            private val usage: String = "Usage: &d/{0} &r<players|broadcasts>",
            val players: State = State(
                "Global chat muted; you no longer see other players' messages.",
                "Global chat unmuted."
            ),
            val broadcasts: State = State(
                "Broadcasts muted.",
                "Broadcasts unmuted."
            ),
        ) {
            fun usage(label: String): String = usage.formatArgs(label)
        }

        @Serializable
        data class CommandSpy(
            val toggle: State = State(
                "Command spy enabled.",
                "Command spy disabled."
            ),
            private val format: String = "&7[CommandSpy] &f{0}&7: &f/{1}",
        ) {
            fun format(playerName: String, command: String, resolve: (String) -> String = { it }): String =
                resolve(format).formatArgs(playerName, command)
        }

        @Serializable
        data class SocialSpy(
            val toggle: State = State(
                "Social spy enabled.",
                "Social spy disabled."
            ),
            private val format: String = "&7[SocialSpy] &f{0} &7-> &f{1}&7: &f{2}",
        ) {
            fun format(senderName: String, targetName: String, message: String, resolve: (String) -> String = { it }): String =
                resolve(format).formatArgs(senderName, targetName, message)
        }

        @Serializable
        data class Nickname(
            private val usage: String = "Usage: &d/{0} &r[player|uuid] <nickname|reset>",
            private val set: String = "Your nickname is now &r{0}&a.",
            @SerialName("set-other")
            private val setOther: String = "{0}'s nickname is now &r{1}&a.",
            val reset: String = "Your nickname has been reset.",
            @SerialName("reset-other")
            private val resetOther: String = "{0}'s nickname has been reset.",
            @SerialName("target-invalid")
            private val targetInvalid: String = "Player '{0}' not found!",
            @SerialName("too-long")
            private val tooLong: String = "Nicknames can be at most {0} characters long.",
            val invalid: String = "A nickname can't be empty or contain spaces.",
            val taken: String = "That nickname is already taken.",
            val disabled: String = "Nicknames are disabled.",
        ) {
            fun usage(label: String): String = usage.formatArgs(label)
            fun set(nickname: String): String = set.formatArgs(nickname)
            fun setOther(targetName: String, nickname: String): String = setOther.formatArgs(targetName, nickname)
            fun resetOther(targetName: String): String = resetOther.formatArgs(targetName)
            fun targetInvalid(playerName: String): String = targetInvalid.formatArgs(playerName)
            fun tooLong(max: Int): String = tooLong.formatArgs(max)
        }

        @Serializable
        data class Broadcast(
            private val usage: String = "Usage: &d/{0} &r<message>",
            @YamlComment("{0} is the message.")
            val format: String = "&8[&6Broadcast&8] &f{0}",
            @SerialName("local-only")
            val localOnly: String = "Global messages need Velocity forwarding enabled and a player online to carry them; only this server saw it.",
        ) {
            fun usage(label: String): String = usage.formatArgs(label)
        }

        @Serializable
        data class Announce(
            private val usage: String = "Usage: &d/{0} &r<player> <announcement>",
            private val unknown: String = "No announcement named '{0}'.",
            private val disabled: String = "Announcement '{0}' is disabled.",
            private val played: String = "Played '{0}' for {1}.",
            @SerialName("target-invalid")
            private val targetInvalid: String = "Player '{0}' not found!",
            @SerialName("local-only")
            val localOnly: String = "Global messages need Velocity forwarding enabled and a player online to carry them; only this server ran it.",
        ) {
            fun usage(label: String): String = usage.formatArgs(label)
            fun unknown(key: String): String = unknown.formatArgs(key)
            fun disabled(key: String): String = disabled.formatArgs(key)
            fun played(key: String, playerName: String): String = played.formatArgs(key, playerName)
            fun targetInvalid(playerName: String): String = targetInvalid.formatArgs(playerName)
        }

        @Serializable
        data class Filter(
            val blocked: String = "Your message was blocked for containing a filtered word.",
        )

        @Serializable
        data class Interactive(
            val expired: String = "That preview has expired.",
            @SerialName("inventory-title")
            private val inventoryTitle: String = "{0}'s inventory",
            @SerialName("ender-title")
            private val enderTitle: String = "{0}'s ender chest",
            private val hover: String = "Click to view {0}'s contents",
        ) {
            fun inventoryTitle(playerName: String): String = inventoryTitle.formatArgs(playerName)
            fun enderTitle(playerName: String): String = enderTitle.formatArgs(playerName)
            fun hover(playerName: String): String = hover.formatArgs(playerName)
        }

        @Serializable
        data class ChatLog(
            private val usage: String = "Usage: &d/{0} &r[player:<name>] [since:<dur>] [text:<term>] [server:<name|*>] [page:<n>]",
            private val help: String = """
                {#a0a>}&lCHAT{#fff<}&f&lLOG &7> &dHELP
                &d/{0} &r[player:<name|uuid>] [since:<dur>] [text:<word>] [server:<name|*>] [page:<n>]
                &dplayer: &rOnly this player's messages (name or UUID).
                &dsince: &rOnly newer than this, e.g. 30m, 2h, 1d.
                &dtext: &rOnly messages containing this word.
                &dserver: &rA server name, or * for all servers. Defaults to this one.
                &dpage: &rShow this page; alone, it reuses your last search.
                &d/{0} export &r<filename> [filters] &7- save matches to exports/<filename>.txt
                &d/{0} import &r<filename> &7- view an exported file
                &7Example: &f/{0} player:Steve since:1h text:hello
            """.trimIndent(),
            @SerialName("no-results")
            val noResults: String = "No matching chat log entries found.",
            @SerialName("export-usage")
            private val exportUsage: String = "Usage: &d/{0} export &r<filename> [player:<name>] [since:<dur>] [text:<word>] [server:<name|*>]",
            @SerialName("import-usage")
            private val importUsage: String = "Usage: &d/{0} import &r<filename>",
            @SerialName("file-invalid")
            val fileInvalid: String = "Filenames may only contain letters, numbers, '.', '_' and '-'.",
            @SerialName("file-missing")
            private val fileMissing: String = "No export named '{0}' was found.",
            private val exported: String = "&7Exported &f{0} &7entries to &f{1}",
            private val header: String = "&7Showing page {0}/{1} ({2} results)",
            @YamlComment("The date and time line above each entry.")
            private val time: String = "&7{0}",
            @YamlComment("Prefix of the username and message line; {0} is the username.")
            private val entry: String = "&f{0}&7: &f",
            @SerialName("page-prev")
            val pagePrev: String = "&d« Prev",
            @SerialName("page-next")
            val pageNext: String = "&dNext »",
        ) {
            fun usage(label: String): String = usage.formatArgs(label)
            fun help(label: String): String = help.formatArgs(label)
            fun exportUsage(label: String): String = exportUsage.formatArgs(label)
            fun importUsage(label: String): String = importUsage.formatArgs(label)
            fun fileMissing(name: String): String = fileMissing.formatArgs(name)
            fun exported(count: Int, path: String): String = exported.formatArgs(count, path)
            fun header(page: Int, totalPages: Int, totalItems: Int): String = header.formatArgs(page, totalPages, totalItems)
            fun time(dateTime: String): String = time.formatArgs(dateTime)
            fun entry(name: String): String = entry.formatArgs(name)
        }
    }
}