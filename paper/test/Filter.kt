import kotlinx.coroutines.*
import mc.xalitoria.chat.paper.data.config.FilterConfig
import mc.xalitoria.util.config.YamlFile
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class Filter {
    val directory = Path("test-resources")
    val wordsFile = directory.resolve("words.txt")
    val filterFile = directory.resolve("filter.yml")
    val matchesFile = directory.resolve("matches.txt")

    val filterConfig = YamlFile(
        filterFile,
        FilterConfig.serializer(),
        ::FilterConfig
    )

    @Test
    fun test() {
        filterConfig.save()
        filterConfig.load()
        filterConfig.data.compile()
        filterConfig.save()

        println("Regexes: " + filterConfig.data.censored.size)

        matchesFile.deleteIfExists()
        matchesFile.createFile()
        matchesFile.writer().use { writer ->
            var matches = 0
            withLoader(filterConfig.data.censored.size) { loader ->
                filterConfig.data.censored.forEach { (input = key, regex = value) ->
                    loader.current++
                    var matched = true
                    wordsFile.readLines().forEach { word ->
                        if (regex.matches(word)) {
                            if (matched) {
                                matches++
                                writer.write(input + '\n')
                                writer.write(regex.pattern + '\n')
                                println(regex.pattern)
                            }
                            matched = false
                            writer.write('\t' + word + '\n')
                        }
                    }
                }
            }
            println("Matches: $matches")
        }
    }
}

class Loader(
    private val total: Int,
    private val bars: Int = 50
) {
    private var spinNext = '-'

    @Volatile
    var current: Int = 0

    private val job: Job = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            render()
            delay(250.milliseconds)
        }
    }

    private fun render() {
        val total = total.coerceAtLeast(1)
        val percentage = (current.toDouble() / total).coerceIn(0.0, 1.0)
        spinNext = spinner[(spinner.indexOf(spinNext) + 1) % spinner.size]
        print('\r')
        print("[${"=".repeat((percentage * bars).toInt())}] ")
        print("%.2f%% ".format(percentage * 100))
        print(spinNext)
        System.out.flush()
    }

    fun stop() {
        job.cancel()
        render() // Final draw so it finishes at 100% / final state
        println() // Move to next line so subsequent prints don't overwrite the loader
    }

    companion object {
        private val spinner: CharArray = ['-', '\\', '|', '/']
    }
}

inline fun <T> withLoader(
    total: Int,
    bars: Int = 50,
    block: (Loader) -> T
): T {
    val loader = Loader(total, bars)
    return try {
        block(loader)
    } finally {
        loader.stop()
    }
}