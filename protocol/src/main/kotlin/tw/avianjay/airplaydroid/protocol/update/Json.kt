package tw.avianjay.airplaydroid.protocol.update

/**
 * A very small, strict JSON reader, and the writer that goes with it.
 *
 * Written rather than pulled in because `:protocol` has no third-party runtime
 * dependency. Two things read JSON: the update manifest the release workflow
 * publishes, and the Cast channel's control messages
 * ([tw.avianjay.airplaydroid.protocol.cast.CastReceiver]), which is also the
 * only thing that writes it. A tolerant parser is the wrong tool for either --
 * silently accepting a malformed manifest would mean an updater that quietly
 * offers nothing, or worse, offers the wrong build.
 *
 * Not a general-purpose JSON library: no streaming, no big-decimal, no
 * `toDouble` precision guarantees beyond what [Number.raw] preserves.
 */
sealed interface JsonValue {

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
    }

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    /**
     * [raw] is the literal text as written. Integers are read from it rather
     * than from [value], because a `versionCode` routed through `Double` would
     * round: 16777217 is not representable as a double.
     */
    data class Num(val value: Double, val raw: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** The string value, or null when this is not a string. */
fun JsonValue?.asString(): String? = (this as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }

fun JsonValue?.asBool(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue?.asObject(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue?.asArray(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

/** Exact integer, or null if this is not a number or has a fractional part. */
fun JsonValue?.asLong(): Long? {
    val number = this as? JsonValue.Num ?: return null
    return number.raw.toLongOrNull()
}

fun JsonValue?.asInt(): Int? = asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** Any number, fractional or not; [asLong] is the one to use when only an exact integer will do. */
fun JsonValue?.asDouble(): Double? = (this as? JsonValue.Num)?.value

object Json {

    class FormatException(message: String, val offset: Int) : Exception("$message at offset $offset")

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.done) throw FormatException("trailing content", reader.position)
        return value
    }

    /** Compact JSON text for [value]. */
    fun write(value: JsonValue): String = StringBuilder().also { writeTo(it, value) }.toString()

    /**
     * A JSON number. Whole values are written without a fraction (`3`, not
     * `3.0`), because receivers of an id or a count compare it as an integer.
     * NaN and the infinities have no JSON spelling and become `null`.
     */
    fun number(value: Double): JsonValue {
        if (value.isNaN() || value.isInfinite()) return JsonValue.Null
        val whole = value == Math.rint(value) && kotlin.math.abs(value) < 1e15
        return JsonValue.Num(value, if (whole) value.toLong().toString() else value.toString())
    }

    fun number(value: Long): JsonValue = JsonValue.Num(value.toDouble(), value.toString())

    /**
     * Builds an object from Kotlin values: strings, numbers, booleans, lists,
     * maps and [JsonValue]s. A `null` value leaves the key out altogether,
     * which is what every Cast message wants for an absent optional field.
     */
    fun obj(vararg entries: Pair<String, Any?>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>()
        entries.forEach { (key, value) -> if (value != null) map[key] = of(value) }
        return JsonValue.Obj(map)
    }

    fun of(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Int -> number(value.toLong())
        is Long -> number(value)
        is Number -> number(value.toDouble())
        is List<*> -> JsonValue.Arr(value.map(::of))
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { (k, v) -> k.toString() to of(v) })
        else -> throw IllegalArgumentException("not a JSON value: ${value.javaClass.name}")
    }

    private fun writeTo(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.entries.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    writeString(out, key)
                    out.append(':')
                    writeTo(out, item)
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeTo(out, item)
                }
                out.append(']')
            }
            is JsonValue.Str -> writeString(out, value.value)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Bool -> out.append(value.value)
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        text.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // Every other control character, which the reader above refuses raw.
                c < ' ' -> out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {

        var position = 0
            private set

        val done: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (done) throw FormatException("unexpected end of input", position)
            return when (val c = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) readNumber() else {
                    throw FormatException("unexpected character '$c'", position)
                }
            }
        }

        private fun readObject(): JsonValue.Obj {
            position++ // '{'
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { position++; return JsonValue.Obj(entries) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw FormatException("expected a key", position)
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw FormatException("expected ':'", position)
                position++
                // A duplicate key is a malformed manifest, not something to
                // resolve by last-one-wins: which value wins would be an
                // accident of the publisher's JSON writer.
                if (entries.containsKey(key)) throw FormatException("duplicate key '$key'", position)
                entries[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> { position++; return JsonValue.Obj(entries) }
                    else -> throw FormatException("expected ',' or '}'", position)
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            position++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { position++; return JsonValue.Arr(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> { position++; return JsonValue.Arr(items) }
                    else -> throw FormatException("expected ',' or ']'", position)
                }
            }
        }

        private fun readString(): String {
            position++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (done) throw FormatException("unterminated string", position)
                when (val c = text[position]) {
                    '"' -> { position++; return out.toString() }
                    '\\' -> {
                        position++
                        if (done) throw FormatException("unterminated escape", position)
                        val escape = text[position]
                        if (escape == 'u') {
                            // Leaves [position] on the last hex digit; the single
                            // advance below then steps past it like any escape.
                            out.append(readUnicodeEscape())
                        } else {
                            out.append(
                                when (escape) {
                                    '"' -> '"'
                                    '\\' -> '\\'
                                    '/' -> '/'
                                    'b' -> '\b'
                                    'f' -> '\u000C'
                                    'n' -> '\n'
                                    'r' -> '\r'
                                    't' -> '\t'
                                    else -> throw FormatException("unknown escape '\\$escape'", position)
                                }
                            )
                        }
                        position++
                    }
                    else -> {
                        // Raw control characters are invalid inside a JSON
                        // string; accepting them would let a malformed manifest
                        // through the same door as a well-formed one.
                        if (c < ' ') throw FormatException("control character in string", position)
                        out.append(c)
                        position++
                    }
                }
            }
        }

        /** Reads the four hex digits after `\u`, leaving [position] on the last one. */
        private fun readUnicodeEscape(): Char {
            if (position + 4 >= text.length) throw FormatException("truncated \\u escape", position)
            val hex = text.substring(position + 1, position + 5)
            val code = hex.toIntOrNull(16) ?: throw FormatException("bad \\u escape '$hex'", position)
            position += 4
            return code.toChar()
        }

        private fun readNumber(): JsonValue.Num {
            val start = position
            if (peek() == '-') position++
            while (!done && text[position].isDigit()) position++
            if (peek() == '.') {
                position++
                while (!done && text[position].isDigit()) position++
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                while (!done && text[position].isDigit()) position++
            }
            val raw = text.substring(start, position)
            val value = raw.toDoubleOrNull() ?: throw FormatException("bad number '$raw'", start)
            return JsonValue.Num(value, raw)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, position)) {
                throw FormatException("expected '$literal'", position)
            }
            position += literal.length
            return value
        }

        private fun peek(): Char = if (done) '\u0000' else text[position]
    }
}
