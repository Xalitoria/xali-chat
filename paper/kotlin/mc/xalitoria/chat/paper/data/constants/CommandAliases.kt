package mc.xalitoria.chat.paper.data.constants

/**
 * Every label a command is typed as, with and without the `x`; used by filters that recognize a command by name.
 * Each command is also reachable as a subcommand of [XALICHAT] under these names without the `x`.
 */
object CommandAliases {
    val XALICHAT    = ["xalichat", "xchat", "chat"]
    val MESSAGE     = ["xmessage", "message", "xmsg", "msg"]
    val SPAM        = ["xspam", "spam", "xalispam", "alispam"]
    val IGNORE      = ["xignore", "ignore"]
    val MUTE        = ["xmute", "mute"]
    val COMMAND_SPY = ["xcommandspy", "commandspy"]
    val SOCIAL_SPY  = ["xsocialspy", "socialspy"]
    val CHAT_LOG    = ["xchatlog", "chatlog"]
    val BROADCAST   = ["xbroadcast", "broadcast", "xbcast", "bcast", "xbc", "bc"]
    val GLOBAL_BROADCAST = ["xgbroadcast", "gbroadcast", "xgbcast", "gbcast", "xgbc", "gbc"]
    val ANNOUNCE    = ["xannounce", "announce", "xann", "ann"]
    val GLOBAL_ANNOUNCE = ["xgannounce", "gannounce", "xgann", "gann"]
    val NICKNAME    = ["xnickname", "nickname", "xnick", "nick"]
}
