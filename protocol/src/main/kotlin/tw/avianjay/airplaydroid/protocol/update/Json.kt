package tw.avianjay.airplaydroid.protocol.update

/**
 * A very small, strict JSON reader.
 *
 * Written rather than pulled in because `:protocol` has no third-party runtime
 * dependency and this is the only JSON the project ever reads: the update
 * manifest the release workflow publishes. A tolerant parser is the wrong tool
 * here -- silently accepting a malformed manifest would mean an updater that
 * quietly offers nothing, or worse, offers the wrong build.
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
