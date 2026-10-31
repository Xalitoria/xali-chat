package mc.xalitoria.chat.announce

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One step of an announcement's `actions`, run in order. In the file each is a map with exactly one of `delay`,
 * `command`, `message` or `condition`; a `condition` also takes the lists `'true'` and `'false'`.
 */
@Serializable(with = ActionSerializer::class)
sealed interface Action {
    /** `delay`: pauses the rest of the list for [time], a number of seconds or tokens such as `2s 10t`, see [parseDelay]. */
    data class Delay(val time: String) : Action {
        val duration: Duration get() = parseDelay(time) ?: Duration.ZERO
    }

    /** `command`: a console command, with placeholders expanded. */
    data class Command(val command: String) : Action

    /** `message`: chat [lines] for everyone who hasn't muted broadcasts and the console, with placeholders expanded for each. */
    data class Message(val lines: List<String>) : Action

    /** `condition`: runs [yes] when [test] holds, [no] when it doesn't, see [matches]. */
    data class Condition(val test: String, val yes: List<Action> = [], val no: List<Action> = []) : Action {
        private val parsed: Comparison by lazy(LazyThreadSafetyMode.PUBLICATION) { Comparison.parse(test) }

        /** Whether the test holds; [expand] fills in the placeholders of the text it is given. */
        fun matches(expand: (String) -> String): Boolean = parsed.matches(expand)
    }
}

/**
 * A condition: a value that is true or false by itself (`%discordsrv_linked%`), or two values joined by `==`, `!=`,
 * `>=`, `<=`, `>` or `<`. The operator is found in the text as written, outside quotes, and placeholders are expanded
 * for each side afterwards, so a value that holds an operator can't change what is compared. Quotes around a side are
 * dropped. Two numbers compare as numbers; otherwise `==` and `!=` compare the text exactly, and the others are false.
 */
internal class Comparison private constructor(private val left: String, private val operator: String?, private val right: String) {
    fun matches(expand: (String) -> String): Boolean {
        val a = unquote(expand(left))
        if (operator == null) return a.lowercase() in TRUE
        val b = unquote(expand(right))
        val x = a.toDoubleOrNull()
        val y = b.toDoubleOrNull()
        if (x != null && y != null) return when (operator) {
            "==" -> x == y
            "!=" -> x != y
            ">=" -> x >= y
            "<=" -> x <= y
            ">" -> x > y
            else -> x < y
        }
        return when (operator) {
            "==" -> a == b
            "!=" -> a != b
            else -> false
        }
    }

    companion object {
        private val OPERATORS = ["==", "!=", ">=", "<=", ">", "<"]
        private val TRUE = setOf("true", "yes", "on", "1")

        fun parse(text: String): Comparison {
            var quote: Char? = null
            for (i in text.indices) {
                val c = text[i]
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    else -> OPERATORS.firstOrNull { text.startsWith(it, i) }?.let {
                        return Comparison(text.substring(0, i), it, text.substring(i + it.length))
                    }
                }
            }
            return Comparison(text, null, "")
        }

        private fun unquote(text: String): String {
            val trimmed = text.trim()
            return if (trimmed.length >= 2 && trimmed.first() in "\"'" && trimmed.last() == trimmed.first()) trimmed.substring(1, trimmed.length - 1) else trimmed
        }
    }
}

private val TOKEN = Regex("(\\d+(?:\\.\\d+)?)(ms|[tsmhd])?", RegexOption.IGNORE_CASE)

/**
 * The length of [text]: tokens that are each a number and a unit, added together. `t` is ticks, then `ms`, `s`, `m`,
 * `h` and `d`; a bare number is seconds. `2s 10t` is 2.5 seconds. Null if anything else is in it.
 */
fun parseDelay(text: String): Duration? {
    if (text.isBlank() || text.replace(TOKEN, "").isNotBlank()) return null
    return TOKEN.findAll(text).fold(Duration.ZERO) { total, match ->
        val amount = match.groupValues[1].toDouble()
        total + when (match.groupValues[2].lowercase()) {
            "t" -> (amount * 50).milliseconds
            "ms" -> amount.milliseconds
            "m" -> amount.minutes
            "h" -> amount.hours
            "d" -> amount.days
            else -> amount.seconds
        }
    }
}

/** What a step looks like in the file: every key optional, [ActionSerializer] checks that exactly one kind is there. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class Step(
    @EncodeDefault(NEVER) val delay: String? = null,
    @EncodeDefault(NEVER) val command: String? = null,
    @Serializable(with = Lines::class) @EncodeDefault(NEVER) val message: List<String>? = null,
    @EncodeDefault(NEVER) val condition: String? = null,
    @SerialName("true") @EncodeDefault(NEVER) val yes: List<Action>? = null,
    @SerialName("false") @EncodeDefault(NEVER) val no: List<Action>? = null,
)

object ActionSerializer : KSerializer<Action> {
    override val descriptor: SerialDescriptor = Step.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Action) = Step.serializer().serialize(
        encoder,
        when (value) {
            is Action.Delay -> Step(delay = value.time)
            is Action.Command -> Step(command = value.command)
            is Action.Message -> Step(message = value.lines)
            is Action.Condition -> Step(condition = value.test, yes = value.yes.ifEmpty { null }, no = value.no.ifEmpty { null })
        },
    )

    override fun deserialize(decoder: Decoder): Action {
        val step = Step.serializer().deserialize(decoder)
        val kinds = listOfNotNull(
            "delay".takeIf { step.delay != null }, "command".takeIf { step.command != null },
            "message".takeIf { step.message != null }, "condition".takeIf { step.condition != null },
        )
        if (kinds.isEmpty()) throw SerializationException("An action needs one of delay, command, message or condition")
        if (kinds.size > 1) throw SerializationException("An action has ${kinds.joinToString(" and ")}; each goes in its own action")
        if (kinds[0] != "condition" && (step.yes != null || step.no != null)) {
            throw SerializationException("'true' and 'false' only go with a condition, not a ${kinds[0]}")
        }
        return when (kinds[0]) {
            "delay" -> {
                parseDelay(step.delay!!) ?: throw SerializationException("'${step.delay}' is not a delay: use seconds, or a number and unit (t, ms, s, m, h, d) such as 2s 10t")
                Action.Delay(step.delay)
            }
            "command" -> Action.Command(step.command!!)
            "message" -> Action.Message(step.message!!)
            else -> Action.Condition(step.condition!!, step.yes.orEmpty(), step.no.orEmpty())
        }
    }
}

/**
 * A list of lines that may be written as one string: `message: "hi"` is `message: ["hi"]`. An empty item is a blank
 * line. The descriptor says "contextual" because kaml picks how to read a node from it, and a list kind would turn
 * the string away.
 */
@OptIn(InternalSerializationApi::class)
internal object Lines : KSerializer<List<String>> {
    private val list = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Lines", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: List<String>) =
        if (value.size == 1) encoder.encodeString(value[0]) else list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<String> = when (decoder) {
        is YamlInput -> when (val node = decoder.node) {
            is YamlScalar -> [node.content]
            is YamlList -> node.items.map { if (it is YamlNull) "" else (it as? YamlScalar)?.content ?: throw SerializationException("A message line must be text") }
            else -> throw SerializationException("A message is a line of text or a list of them")
        }
        is JsonDecoder -> when (val element = decoder.decodeJsonElement()) {
            is JsonArray -> element.map { if (it is JsonNull) "" else (it as? JsonPrimitive)?.content ?: throw SerializationException("A message line must be text") }
            is JsonPrimitive -> [element.content]
            else -> throw SerializationException("A message is a line of text or a list of them")
        }
        else -> list.deserialize(decoder)
    }
}
