package mc.xalitoria.chat.paper.external.plan

import com.djrapitops.plan.delivery.web.resolver.Resolver
import com.djrapitops.plan.delivery.web.resolver.Response
import com.djrapitops.plan.delivery.web.resolver.request.Request
import kotlinx.coroutines.runBlocking
import mc.xalitoria.chat.logs.LogChanges
import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.data.db.repository.LogFilter
import mc.xalitoria.chat.paper.data.db.repository.LogKind
import mc.xalitoria.chat.paper.data.db.repository.LogRepository
import mc.xalitoria.chat.paper.data.db.repository.LogRow
import java.nio.charset.StandardCharsets
import java.util.Optional

/**
 * The log page on Plan's web server: chat, commands and private messages as one searchable list at `/xalichat`, and the
 * JSON it reads at `/xalichat/log`. `/xalichat/plan.js` is added to Plan's own page and puts the log, framed with
 * `?embed=1`, into XaliChat's card on the pages of a player's plugins and of a server's plugins. Plan's own login decides who gets in: a user needs [VIEW], and [VIEW_MESSAGES] on
 * top of that for private messages. Without Plan's login nobody is known, so nobody gets in.
 *
 * The page and its data come from the database this server is connected to, so every server sharing it is in the list.
 * Plan calls this off the server thread, which is why the database is read directly.
 */
class LogPage(
    private val enabled: () -> Boolean,
    /** Whether `logging.messages.enable` is on; private messages are left out of the page when it isn't. */
    private val messagesLogged: () -> Boolean,
    /** What tells whether the logs changed, so an unchanged page needn't read them; null reads them every time. */
    private val changes: LogChanges? = null,
) : Resolver {
    override fun usedWebPermissions(): Set<String> = setOf(VIEW, VIEW_MESSAGES)

    override fun canAccess(request: Request): Boolean = isScript(request) || viewable(request).isNotEmpty()

    /** The script is the same for everyone and holds nothing: the log it frames is what checks the user. */
    override fun requiresAuth(request: Request): Boolean = !isScript(request)

    private fun isScript(request: Request) = request.path.asString().trimEnd('/').endsWith("/$SCRIPT")

    override fun resolve(request: Request): Optional<Response> {
        if (!enabled() || request.method != "GET") return Optional.empty()
        if (isScript(request)) return Optional.of(script())
        // Plan skips canAccess without its login, so this is the check that holds then.
        val kinds = viewable(request)
        if (kinds.isEmpty()) return Optional.of(text(403, "Forbidden: sign in to Plan with a user that has $VIEW."))
        return when (request.path.asString().trimEnd('/').substringAfterLast('/')) {
            PAGE -> Optional.of(page(kinds))
            DATA -> Optional.of(data(request, kinds))
            else -> Optional.empty()
        }
    }

    /** The logs the user may see, none when they may see no page. */
    private fun viewable(request: Request): Set<LogKind> {
        val user = request.user.orElse(null) ?: return emptySet()
        if (!user.hasPermission(VIEW)) return emptySet()
        return buildSet {
            add(LogKind.CHAT)
            add(LogKind.COMMAND)
            if (messagesLogged() && user.hasPermission(VIEW_MESSAGES)) add(LogKind.MESSAGE)
        }
    }

    private fun page(kinds: Set<LogKind>): Response = Response.builder()
        .setMimeType("text/html")
        .setHeader("Cache-Control", "no-store")
        .setHeader("X-Content-Type-Options", "nosniff")
        .setHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'self'")
        .setContent(TEMPLATE.replace("__KINDS__", kinds.joinToString(",", "[", "]") { "\"${it.code}\"" }), StandardCharsets.UTF_8)
        .build()

    private fun script(): Response = Response.builder()
        .setMimeType("application/javascript")
        .setHeader("Cache-Control", "no-cache")
        .setHeader("X-Content-Type-Options", "nosniff")
        .setContent(SCRIPT_SOURCE, StandardCharsets.UTF_8)
        .build()

    /**
     * The newest entries before `before` (all of them when it is missing), the entries after `after` for the live tail,
     * or with `around` (a row's key) the window around it. `kinds` and `q` narrow all three, see [SearchSyntax].
     * `scopePlayer` and `scopeServer` are what a framed page is limited to, which `q` can override; a server that
     * hasn't written to the logs is no limit, as for the proxy. `version` is what [LogChanges] said when the answer was
     * read: asked for what is `after` a time and told the `v` it still is, nothing is read when pings can vouch for it.
     */
    private fun data(request: Request, allowed: Set<LogKind>): Response {
        val query = request.query
        fun number(name: String): Long? = query.get(name).orElse(null)?.toLongOrNull()
        val limit = (number("limit") ?: 150).coerceIn(1, 500).toInt()
        val terms = SearchSyntax.parse(query.get("q").orElse("")!!.take(200))
        val version = changes?.version
        if (changes != null && changes.trusted && number("after") != null && number("v") == version) {
            return Response.builder().setHeader("Cache-Control", "no-store")
                .setJSONContent(mapOf("entries" to emptyList<Any>(), "more" to false, "version" to version)).build()
        }
        val kinds = query.get("kinds").orElse("")!!.mapNotNullTo(HashSet()) { LogKind.of(it) }.filterTo(HashSet()) { it in allowed }
        return try {
            val servers = runBlocking { LogRepository.servers() }
            val scopeServer = query.get("scopeServer").orElse(null)?.take(64)?.let { name -> servers.firstOrNull { it.equals(name, ignoreCase = true) } }
            val filter = LogFilter(kinds, terms.player ?: query.get("scopePlayer").orElse(null)?.take(64), terms.server ?: scopeServer, terms.text)
            val around = query.get("around").orElse(null)
            val result = when {
                kinds.isEmpty() -> Result(emptyList(), false)
                around != null -> {
                    val focus = LogKind.of(around.firstOrNull() ?: ' ')?.takeIf { it in allowed }
                    val id = around.drop(1).toLongOrNull()
                    val window = if (focus != null && id != null) runBlocking { LogRepository.around(focus, id, filter, (number("radius") ?: 25).coerceIn(1, 200).toInt()) } else null
                    Result(window ?: emptyList(), false)
                }
                number("after") != null -> Result(runBlocking { LogRepository.search(filter.copy(from = number("after")), limit, newestFirst = false) }, false)
                else -> {
                    // One more than asked shows whether there is anything older.
                    val found = runBlocking { LogRepository.search(filter.copy(until = number("before")), limit + 1) }
                    Result(found.take(limit).sortedWith(LogRepository.chronological), found.size > limit)
                }
            }
            Response.builder()
                .setHeader("Cache-Control", "no-store")
                .setJSONContent(mapOf("entries" to result.rows.map { it.toJson() }, "more" to result.more, "version" to version))
                .build()
        } catch (e: Exception) {
            debug("The log page could not read the database: {0}", e)
            text(500, "The log could not be read.")
        }
    }

    private fun text(status: Int, message: String): Response = Response.builder()
        .setStatus(status)
        .setMimeType("text/plain")
        .setContent(message, StandardCharsets.UTF_8)
        .build()

    private fun LogRow.toJson() = mapOf(
        "key" to key,
        "kind" to kind.code.toString(),
        "ts" to timestampMillis,
        "uuid" to uniqueId?.toString(),
        "user" to username,
        "text" to text,
        "target" to target,
        "server" to server,
    )

    private class Result(val rows: List<LogRow>, val more: Boolean)

    companion object {
        /**
         * Plan web permissions, handed out to Plan users in Plan; they are not Minecraft permissions. Plan counts a
         * permission as given by any shorter one it starts with, so private messages are not named under [VIEW].
         */
        const val VIEW = "xalichat.log"
        const val VIEW_MESSAGES = "xalichat.messages"

        /** Where the page is on Plan's web server. */
        const val PATH = "/xalichat"
        private const val PAGE = "xalichat"
        private const val DATA = "log"
        private const val SCRIPT = "plan.js"

        private fun resource(name: String) =
            checkNotNull(LogPage::class.java.getResourceAsStream("/plan/$name")) { "plan/$name is missing from the jar" }
                .use { it.readBytes().decodeToString() }

        private val TEMPLATE by lazy { resource("log.html") }

        /** What Plan's own page loads; see `plan.js`. */
        val SCRIPT_SOURCE by lazy { resource(SCRIPT) }

        /** Where that script is, for [PlanHook] to add to Plan's page. */
        const val SCRIPT_PATH = "$PATH/$SCRIPT"
    }
}

/**
 * The search bar: `player:<name or uuid>` and `server:<name>` narrow the list, everything else is text that has to be
 * in a message, command or name. A keyword without a value yet, as when it is still being typed, is left out.
 */
internal object SearchSyntax {
    class Terms(val player: String?, val server: String?, val text: String?)

    fun parse(input: String): Terms {
        var player: String? = null
        var server: String? = null
        val words = ArrayList<String>()
        for (token in input.trim().split(Regex("\\s+"))) {
            val colon = token.indexOf(':')
            val keyword = if (colon > 0) token.substring(0, colon).lowercase() else ""
            val value = if (colon > 0) token.substring(colon + 1) else ""
            when {
                keyword == "player" -> if (value.isNotEmpty()) player = value
                keyword == "server" -> if (value.isNotEmpty()) server = value
                token.isNotEmpty() -> words += token
            }
        }
        return Terms(player, server, words.joinToString(" ").ifEmpty { null })
    }
}
