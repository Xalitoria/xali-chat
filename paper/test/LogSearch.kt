import com.djrapitops.plan.delivery.web.resolver.request.Request
import com.djrapitops.plan.delivery.web.resolver.request.WebUser
import com.google.gson.JsonParser
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import com.xalitoria.chat.logs.LogChanges
import mc.xalitoria.chat.paper.data.config.MainConfig
import mc.xalitoria.chat.paper.data.db.DatabaseManager
import mc.xalitoria.chat.paper.data.db.DatabaseManager.dbTransaction
import com.xalitoria.chat.logs.LogFilter
import com.xalitoria.chat.logs.LogHistogram
import com.xalitoria.chat.logs.LogKind
import com.xalitoria.chat.logs.LogRepository
import mc.xalitoria.chat.paper.data.db.repository.ServerRepository
import com.xalitoria.chat.logs.ChatLogTable
import com.xalitoria.chat.logs.CommandLogTable
import com.xalitoria.chat.logs.MessageLogTable
import com.xalitoria.chat.logs.LogPage
import com.xalitoria.chat.logs.SearchSyntax
import org.jetbrains.exposed.v1.jdbc.insert
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toKotlinUuid

/** The merged log search, the page's search syntax, and who the page lets in. */
class LogSearch {
    private val file = File.createTempFile("xalichat", ".db")
    private val steve = UUID.randomUUID()
    private val alex = UUID.randomUUID()
    private var other = 0

    @BeforeTest
    fun connect() {
        val config = HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${file.absolutePath}"; maximumPoolSize = 1 }
        DatabaseManager.attach(HikariDataSource(config), MainConfig.Database(serverName = "survival"))
        other = runBlocking { dbTransaction { ServerRepository.register("creative") } }
    }

    @AfterTest
    fun disconnect() {
        DatabaseManager.close()
        file.delete()
    }

    private fun chat(at: Long, who: UUID, name: String, text: String, server: Int = DatabaseManager.serverId) = runBlocking {
        dbTransaction {
            ChatLogTable.insert {
                it[ChatLogTable.server] = server; it[timestamp] = at; it[uniqueId] = who.toKotlinUuid(); it[username] = name; it[message] = text
            }[ChatLogTable.id]
        }
    }

    private fun command(at: Long, who: UUID, name: String, text: String) = runBlocking {
        dbTransaction {
            CommandLogTable.insert {
                it[server] = DatabaseManager.serverId; it[timestamp] = at; it[uniqueId] = who.toKotlinUuid(); it[username] = name; it[command] = text
            }[CommandLogTable.id]
        }
    }

    private fun whisper(at: Long, from: UUID?, sender: String, to: UUID?, receiver: String, text: String) = runBlocking {
        dbTransaction {
            MessageLogTable.insert {
                it[server] = DatabaseManager.serverId; it[timestamp] = at; it[senderId] = from?.toKotlinUuid(); it[MessageLogTable.sender] = sender
                it[receiverId] = to?.toKotlinUuid(); it[MessageLogTable.receiver] = receiver; it[message] = text
            }[MessageLogTable.id]
        }
    }

    private fun search(filter: LogFilter = LogFilter(), newestFirst: Boolean = true) =
        runBlocking { LogRepository.search(filter, 100, newestFirst) }

    @Test
    fun theThreeLogsAreOneListOrderedByTime() {
        chat(1000, steve, "Steve", "hello")
        command(2000, steve, "Steve", "home")
        whisper(3000, steve, "Steve", alex, "Alex", "psst")
        assertEquals(listOf("psst", "home", "hello"), search().map { it.text })
        assertEquals(listOf("hello", "home", "psst"), search(newestFirst = false).map { it.text })
        assertEquals(listOf(LogKind.MESSAGE, LogKind.COMMAND, LogKind.CHAT), search().map { it.kind })
        assertEquals("Alex", search().first().target)
        assertEquals("survival", search().first().server)
        assertEquals(listOf("hello"), search(LogFilter(setOf(LogKind.CHAT))).map { it.text })
    }

    @Test
    fun aPlayerIsFoundByNameOrIdAndAMessageOnEitherEnd() {
        chat(1, steve, "Steve", "a")
        chat(2, alex, "Alex", "b")
        whisper(3, steve, "Steve", alex, "Alex", "c")
        whisper(4, null, "CONSOLE", null, "Steve", "d")
        assertEquals(listOf("d", "c", "a"), search(LogFilter(player = "steve")).map { it.text })
        assertEquals(listOf("c", "b"), search(LogFilter(player = alex.toString())).map { it.text })
        assertEquals(listOf("d", "c"), search(LogFilter(kinds = setOf(LogKind.MESSAGE), player = "STEVE")).map { it.text })
    }

    @Test
    fun serverTextAndTimeNarrowTheSearch() {
        chat(1, steve, "Steve", "100% sure", server = other)
        chat(2, steve, "Steve", "1000 sure")
        chat(3, alex, "Alex", "bye")
        assertEquals(listOf("bye", "1000 sure"), search(LogFilter(server = "Survival")).map { it.text })
        assertEquals(listOf("100% sure"), search(LogFilter(text = "00%")).map { it.text })
        assertEquals(listOf("bye"), search(LogFilter(text = "alex")).map { it.text })
        assertEquals(listOf("bye", "1000 sure"), search(LogFilter(from = 2)).map { it.text })
        assertEquals(listOf("1000 sure", "100% sure"), search(LogFilter(until = 2)).map { it.text })
        assertEquals(emptyList(), search(LogFilter(server = "nowhere")))
    }

    @Test
    fun aFocusedRowComesWithItsNeighboursOnItsServer() {
        val ids = (1L..9L).map { chat(it * 10, steve, "Steve", "m$it") }
        chat(50, alex, "Alex", "elsewhere", server = other)
        val window = runBlocking { LogRepository.around(LogKind.CHAT, ids[4], LogFilter(), 2) }
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), window?.map { it.text })
        val narrowed = runBlocking { LogRepository.around(LogKind.CHAT, ids[4], LogFilter(text = "m8"), 2) }
        assertEquals(listOf("m5", "m8"), narrowed?.map { it.text })
        assertNull(runBlocking { LogRepository.around(LogKind.COMMAND, 99, LogFilter(), 2) })
    }

    @Test
    fun theSearchBarSplitsKeywordsFromText() {
        val terms = SearchSyntax.parse("  player:Steve  hello server:creative  world ")
        assertEquals("Steve", terms.player)
        assertEquals("creative", terms.server)
        assertEquals("hello world", terms.text)
        val typing = SearchSyntax.parse("player: http://x")
        assertNull(typing.player)
        assertEquals("http://x", typing.text)
        assertNull(SearchSyntax.parse("").text)
    }

    private fun page(messages: Boolean = true) = LogPage({ true }, { messages })

    private fun get(path: String, vararg permissions: String?): Request =
        Request("GET", path, permissions.firstOrNull()?.let { WebUser("web", *permissions.filterNotNull().toTypedArray()) }, emptyMap())

    @Test
    fun onlyAPlanUserWithThePermissionGetsIn() {
        val page = page()
        assertFalse(page.canAccess(get("/xalichat")))
        assertFalse(page.canAccess(get("/xalichat", "page.server")))
        assertTrue(page.canAccess(get("/xalichat", LogPage.VIEW)))
        // Without Plan's login Plan never asks canAccess, so resolve itself refuses.
        assertEquals(403, page.resolve(get("/xalichat")).get().code)
        assertEquals(403, page.resolve(get("/xalichat/log", "page.server")).get().code)
        assertTrue(page.resolve(get("/xalichat/other", LogPage.VIEW)).isEmpty)
    }

    @Test
    fun theDataIsFilteredByThePermissionsOfTheUser() {
        chat(1000, steve, "Steve", "<b>hi</b>")
        whisper(2000, steve, "Steve", alex, "Alex", "secret")
        fun texts(permission: Array<String>, query: String): List<String> {
            val response = page().resolve(get("/xalichat/log?kinds=ckm$query", *permission)).get()
            assertEquals(200, response.code)
            return JsonParser.parseString(response.asString).asJsonObject["entries"].asJsonArray.map { it.asJsonObject["text"].asString }
        }
        assertEquals(listOf("<b>hi</b>"), texts([LogPage.VIEW], ""))
        assertEquals(listOf("<b>hi</b>", "secret"), texts([LogPage.VIEW, LogPage.VIEW_MESSAGES], ""))
        assertEquals(listOf("secret"), texts([LogPage.VIEW, LogPage.VIEW_MESSAGES], "&q=player:alex"))
        assertFalse(LogPage({ true }, { false }).resolve(get("/xalichat/log?kinds=m", LogPage.VIEW, LogPage.VIEW_MESSAGES)).get().asString.contains("secret"))
        val html = page().resolve(get("/xalichat", LogPage.VIEW)).get().asString
        assertTrue(html.contains("const ALLOWED = [\"c\",\"k\"];"))
        assertNotNull(page().resolve(get("/xalichat/", LogPage.VIEW)).orElse(null))
    }

    @Test
    fun olderEntriesAreSignalledAndTheTailIsAfterATime() {
        repeat(5) { chat(1000L + it, steve, "Steve", "m$it") }
        fun body(query: String) = JsonParser.parseString(page().resolve(get("/xalichat/log?kinds=c$query", LogPage.VIEW)).get().asString).asJsonObject
        val newest = body("&limit=2")
        assertTrue(newest["more"].asBoolean)
        assertEquals(listOf("m3", "m4"), newest["entries"].asJsonArray.map { it.asJsonObject["text"].asString })
        assertFalse(body("&limit=5")["more"].asBoolean)
        assertEquals(listOf("m3", "m4"), body("&after=1003")["entries"].asJsonArray.map { it.asJsonObject["text"].asString })
    }

    private fun json(response: com.djrapitops.plan.delivery.web.resolver.Response) = JsonParser.parseString(response.asString).asJsonObject

    private fun texts(body: com.google.gson.JsonObject) = body["entries"].asJsonArray.map { it.asJsonObject["text"].asString }

    @Test
    fun aFramedPageIsLimitedToItsPlayerAndServerUnlessSearchedOtherwise() {
        chat(1000, steve, "Steve", "on survival")
        chat(2000, steve, "Steve", "on creative", server = other)
        chat(3000, alex, "Alex", "alex on survival")
        fun get(query: String) = json(page().resolve(get("/xalichat/log?kinds=c$query", LogPage.VIEW)).get())
        assertEquals(listOf("on survival", "on creative"), texts(get("&scopePlayer=$steve")))
        assertEquals(listOf("on survival", "alex on survival"), texts(get("&scopeServer=Survival")))
        assertEquals(listOf("on survival"), texts(get("&scopePlayer=Steve&scopeServer=survival")))
        // A server that never wrote, such as a proxy, limits nothing.
        assertEquals(listOf("on survival", "on creative", "alex on survival"), texts(get("&scopeServer=proxy")))
        // What is typed wins over the frame's own limit.
        assertEquals(listOf("alex on survival"), texts(get("&scopePlayer=$steve&q=player:alex")))
    }

    @Test
    fun anUnchangedTailIsAnsweredWithoutReadingTheLogsOnlyWhenPingsVouchForIt() {
        chat(1000, steve, "Steve", "first")
        val changes = LogChanges()
        val page = LogPage({ true }, { true }, changes)
        fun tail(v: Long?) = json(page.resolve(get("/xalichat/log?kinds=c&after=0${if (v != null) "&v=$v" else ""}", LogPage.VIEW)).get())
        val first = tail(null)
        assertEquals(listOf("first"), texts(first))
        assertEquals(0, first["version"].asLong)
        // Without pings an unchanged version proves nothing, so the logs are read again.
        assertEquals(listOf("first"), texts(tail(0)))
        changes.trusted = true
        assertEquals(emptyList(), texts(tail(0)))
        assertEquals(0, tail(0)["version"].asLong)
        // A write somewhere makes the version differ, and the answer is read and carries the new one.
        chat(2000, steve, "Steve", "second")
        changes.bump()
        val next = tail(0)
        assertEquals(listOf("first", "second"), texts(next))
        assertEquals(1, next["version"].asLong)
        assertEquals(emptyList(), texts(tail(1)))
        // The first read of a page (no `after`) always reads.
        assertEquals(2, texts(json(page.resolve(get("/xalichat/log?kinds=c&v=1", LogPage.VIEW)).get())).size)
    }

    @Test
    fun theLogsAreCountedPerKindServerAndTime() {
        chat(1_000, steve, "Steve", "a"); chat(2_000, steve, "Steve", "b"); chat(3_000, alex, "Alex", "c", other)
        command(2_500, steve, "Steve", "home")
        whisper(4_000, steve, "Steve", alex, "Alex", "hi")
        fun count(vararg kinds: LogKind, server: String? = null, from: Long? = null) =
            runBlocking { LogRepository.count(LogFilter(kinds.toSet(), server = server, from = from)) }
        assertEquals(3, count(LogKind.CHAT))
        assertEquals(2, count(LogKind.CHAT, server = "survival"))
        assertEquals(1, count(LogKind.CHAT, server = "creative"))
        assertEquals(2, count(LogKind.CHAT, from = 2_000))
        assertEquals(1, count(LogKind.COMMAND))
        assertEquals(1, count(LogKind.MESSAGE, server = "survival"))
        assertEquals(0, count(LogKind.MESSAGE, server = "creative"))
        assertEquals(5, count(*LogKind.entries.toTypedArray()))
    }

    @Test
    fun theVersionsOfTheServersInUseAreAnswered() {
        runBlocking { dbTransaction { ServerRepository.report("survival", "0.10.1") } }
        val body = json(LogPage({ true }, { true }, host = LogPage.Host(proxy = true, version = "0.10.2")).resolve(get("/xalichat/log?versions=1", LogPage.VIEW)).get())
        assertEquals(true, body["host"].asJsonObject["proxy"].asBoolean)
        assertEquals("0.10.2", body["host"].asJsonObject["version"].asString)
        val servers = body["servers"].asJsonArray.associate { it.asJsonObject["name"].asString to it.asJsonObject }
        assertEquals("0.10.1", servers["survival"]!!["version"].asString)
        assertEquals(true, servers["survival"]!!["active"].asBoolean)
        // one that has never reported is listed, with no version (the field is left out of the answer) and not in use
        assertNull(servers["creative"]!!.get("version")?.takeUnless { it.isJsonNull })
        assertEquals(false, servers["creative"]!!["active"].asBoolean)
    }

    @Test
    fun thePageListsTheServersThatHaveWritten() {
        val body = json(page().resolve(get("/xalichat/log?servers=1", LogPage.VIEW)).get())
        assertEquals(listOf("creative", "survival"), body["servers"].asJsonArray.map { it.asString })
    }

    @Test
    fun aStretchOfTimePicksTheListAndPagesStayInsideIt() {
        (1L..9L).forEach { chat(it * 1000, steve, "Steve", "m$it") }
        fun get(query: String) = json(page().resolve(get("/xalichat/log?kinds=c$query", LogPage.VIEW)).get())
        assertEquals(listOf("m3", "m4", "m5"), texts(get("&since=3000&until=5000")))
        // Older than the first page of the stretch, but not older than its start.
        assertEquals(listOf("m3", "m4"), texts(get("&since=3000&until=5000&before=4000")))
        assertTrue(get("&since=3000&limit=2")["more"].asBoolean)
        assertFalse(get("&since=8000&until=9000&limit=2")["more"].asBoolean)
        // The tail never goes back before the start, an earlier `after` is lifted to it.
        assertEquals(listOf("m8", "m9"), texts(get("&since=8000&after=1000")))
    }

    @Test
    fun theTimelineCountsEachLogPerStretchWhateverTimeIsPicked() {
        val hour = 3_600_000L
        chat(0, steve, "Steve", "a")
        chat(10 * 60_000, steve, "Steve", "b")
        command(hour + 5, steve, "Steve", "c")
        chat(5 * hour, alex, "Alex", "d", server = other)
        val all = runBlocking { LogRepository.histogram(LogFilter(), 120) }!!
        assertEquals(0, all.from)
        assertEquals(5 * hour, all.to)
        // 5 hours over at most 120 stretches: five minutes each.
        assertEquals(300_000L, all.bucketMillis)
        assertEquals(mapOf(LogKind.CHAT to 1L), all.counts[10 * 60_000])
        assertEquals(mapOf(LogKind.COMMAND to 1L), all.counts[hour])
        assertEquals(4L, all.counts.values.sumOf { it.values.sum() })
        // The picked time doesn't thin out the timeline; the logs and the search do.
        assertEquals(4L, runBlocking { LogRepository.histogram(LogFilter(from = hour, until = hour + 10), 120) }!!.counts.values.sumOf { it.values.sum() })
        val chatOnly = runBlocking { LogRepository.histogram(LogFilter(setOf(LogKind.CHAT), server = "survival"), 120) }!!
        assertEquals(2L, chatOnly.counts.values.sumOf { it.values.sum() })
        assertEquals(10 * 60_000, chatOnly.to)
        assertNull(runBlocking { LogRepository.histogram(LogFilter(text = "nothing like it"), 120) })
        // Long logs get bigger stretches, no more than the asked number of them.
        assertEquals(60_000L, LogHistogram.bucketFor(60_000L * 119, 120))
        assertEquals(300_000L, LogHistogram.bucketFor(60_000L * 120, 120))
        assertEquals(7 * 86_400_000L, LogHistogram.bucketFor(Long.MAX_VALUE / 2, 120))
    }

    @Test
    fun theTimelineIsAnAnswerOfThePageAndFollowsItsKindsAndSearch() {
        chat(0, steve, "Steve", "hello")
        command(120_000, steve, "Steve", "home")
        fun hist(query: String) = json(page().resolve(get("/xalichat/log?hist=1$query", LogPage.VIEW)).get())
        val both = hist("&kinds=ck")
        assertEquals(0, both["from"].asLong)
        assertEquals(120_000, both["to"].asLong)
        assertEquals(60_000, both["size"].asLong)
        assertEquals(listOf(listOf(0L, 1L, 0L, 0L), listOf(120_000L, 0L, 1L, 0L)), both["buckets"].asJsonArray.map { row -> row.asJsonArray.map { it.asLong } })
        assertEquals(1, hist("&kinds=k")["buckets"].asJsonArray.size())
        assertEquals(0, hist("&kinds=ck&q=nothing")["buckets"].asJsonArray.size())
        // Private messages are for those who may see them.
        assertEquals(0, hist("&kinds=m")["buckets"].asJsonArray.size())
    }

    @Test
    fun theScriptIsGivenToAnyoneAndTheFrameIsOnlyForTheSameSite() {
        val page = page()
        val script = get("/xalichat/plan.js")
        assertTrue(page.canAccess(script))
        assertFalse(page.requiresAuth(script))
        val response = page.resolve(script).get()
        assertEquals(200, response.code)
        assertTrue(response.asString.contains("XaliChat"))
        assertTrue(page.requiresAuth(get("/xalichat/log")))
        val html = page.resolve(get("/xalichat/", LogPage.VIEW)).get()
        assertTrue(html.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'self'"))
    }
}
