package tw.avianjay.airplaydroid.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals

/** The writer the Cast channel uses, checked by reading its output back. */
class JsonWriteTest {

    @Test
    fun writesCompactObjectsInInsertionOrder() {
        val text = Json.write(Json.obj("type" to "PONG", "requestId" to 7, "ok" to true, "none" to null))
        assertEquals("""{"type":"PONG","requestId":7,"ok":true}""", text)
    }

    @Test
    fun wholeNumbersHaveNoFraction() {
        assertEquals("3", Json.write(Json.number(3.0)))
        assertEquals("3.5", Json.write(Json.number(3.5)))
        assertEquals("-2", Json.write(Json.number(-2.0)))
        assertEquals("null", Json.write(Json.number(Double.NaN)))
    }

    @Test
    fun escapesWhatTheReaderRefusesRaw() {
        val original = "a\"b\\c\nd\te\u0001"
        val written = Json.write(JsonValue.Str(original))
        assertEquals(original, Json.parse(written).asString())
        assertEquals(""""a\"b\\c\nd\te\u0001"""", written)
    }

    @Test
    fun nestedValuesRoundTrip() {
        val value = Json.obj(
            "list" to listOf(1, "two", Json.obj("three" to 3.25)),
            "map" to mapOf("k" to false),
        )
        assertEquals(value, Json.parse(Json.write(value)))
    }
}
