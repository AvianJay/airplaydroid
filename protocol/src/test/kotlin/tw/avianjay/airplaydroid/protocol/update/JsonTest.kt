package tw.avianjay.airplaydroid.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON reader, tested directly rather than only through the manifest.
 *
 * The manifest only ever sees the shape this project's workflow writes; a reader
 * bug that only shows up on a hand-edited or third-party manifest would then
 * never be caught, and would present as "no update available".
 */
class JsonTest {

    @Test
    fun readsScalars() {
        assertEquals("hi", Json.parse(""""hi"""").asString())
        assertEquals(7, Json.parse("7").asInt())
        assertEquals(-7L, Json.parse("-7").asLong())
        assertEquals(true, Json.parse("true").asBool())
        assertEquals(false, Json.parse("false").asBool())
        assertEquals(JsonValue.Null, Json.parse("null"))
    }

    @Test
    fun readsNestedStructures() {
        val root = Json.parse("""{"a":{"b":[1,2,{"c":"d"}]}}""").asObject()!!
        val list = root["a"]!!.asObject()!!["b"]!!.asArray()!!
        assertEquals(3, list.size)
        assertEquals("d", list[2].asObject()!!["c"].asString())
    }

    @Test
    fun handlesWhitespaceAndEmptyContainers() {
        assertEquals(0, Json.parse("  {  }  ").asObject()!!.entries.size)
        assertEquals(0, Json.parse("[ ]").asArray()!!.size)
        assertEquals(0, Json.parse("{\n\t\"a\"\n:\n[\n]\n}").asObject()!!["a"]!!.asArray()!!.size)
    }

    @Test
    fun readsEscapes() {
        val text = Json.parse(""""a\"b\\c\/d\ne\tf\u0041"""")
        assertEquals("a\"b\\c/d\ne\tfA", text.asString())
    }

    @Test
    fun readsExponentsAndFractions() {
        assertEquals(1.5, (Json.parse("1.5") as JsonValue.Num).value)
        assertEquals(1000.0, (Json.parse("1e3") as JsonValue.Num).value)
        assertEquals(0.001, (Json.parse("1e-3") as JsonValue.Num).value)
        // A fractional value is not an exact integer, so asLong refuses it.
        assertNull(Json.parse("1.5").asLong())
    }

    @Test
    fun rejectsTrailingContent() {
        assertFailsWith<Json.FormatException> { Json.parse("""{"a":1} junk""") }
    }

    @Test
    fun rejectsDuplicateKeys() {
        // Last-one-wins would make the result depend on the publisher's JSON
        // writer, so a duplicate is treated as malformed.
        assertFailsWith<Json.FormatException> { Json.parse("""{"a":1,"a":2}""") }
    }

    @Test
    fun rejectsMalformedInput() {
        assertFailsWith<Json.FormatException> { Json.parse("") }
        assertFailsWith<Json.FormatException> { Json.parse("{") }
        assertFailsWith<Json.FormatException> { Json.parse("""{"a"}""") }
        assertFailsWith<Json.FormatException> { Json.parse("""{"a":}""") }
        assertFailsWith<Json.FormatException> { Json.parse("[1,]") }
        assertFailsWith<Json.FormatException> { Json.parse("nope") }
        assertFailsWith<Json.FormatException> { Json.parse("\"unterminated") }
    }

    @Test
    fun rejectsRawControlCharactersInStrings() {
        assertFailsWith<Json.FormatException> { Json.parse("\"a\nb\"") }
    }

    @Test
    fun typeAccessorsReturnNullRatherThanThrowing() {
        val number = Json.parse("5")
        assertNull(number.asString())
        assertNull(number.asObject())
        assertNull(number.asArray())
        assertNull(number.asBool())
        assertNull(JsonValue.Null.asString())
        assertTrue(Json.parse(""""  """").asString() == null, "blank strings are treated as absent")
    }
}
