package mc.xalitoria.chat.paper.concurrent

data class LookupResult(
    val found: Boolean,
    val exactName: String?,
    val serverName: String?
)