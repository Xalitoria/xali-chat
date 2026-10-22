package mc.xalitoria.chat.paper.data.constants

/**
 * Every label a command is typed as, with and without the `x`; used by filters that recognize a command by name.
 * Each command is also reachable as a subcommand of [XALICHAT] under these names without the `x`.
 */
object CommandAliases {
    val XALICHAT    = ["xalichat", "xchat", "chat"]
    val MESSAGE     = ["xmessage", "message", "xmsg", "msg", "xpm", "pm", "xtell", "tell", "xt", "t", "xwhisper", "whisper", "xw", "w"]
    val REPLY       = ["xreply", "reply", "xr", "r"]
    val SPAM        = ["xspam", "spam"]
    val IGNORE      = ["xignore", "ignore"]
    val SILENCE     = ["xsilence", "silence"]
    val COMMAND_SPY = ["xcommandspy", "commandspy"]
    val SOCIAL_SPY  = ["xsocialspy", "socialspy"]
    val CHAT_LOG    = ["xchatlog", "chatlog"]
    val BROADCAST   = ["xbroadcast", "broadcast", "xbcast", "bcast", "xbc", "bc"]
    val GLOBAL_BROADCAST = ["xgbroadcast", "gbroadcast", "xgbcast", "gbcast", "xgbc", "gbc"]
    val ANNOUNCE    = ["xannounce", "announce", "xann", "ann"]
    val GLOBAL_ANNOUNCE = ["xgannounce", "gannounce", "xgann", "gann"]
    val NICKNAME    = ["xnickname", "nickname", "xnick", "nick"]

    /** Private messages: [MESSAGE] and [REPLY], which are never logged or shown to command spies. */
    val PRIVATE     = MESSAGE + REPLY
}
