package tw.avianjay.airplaydroid.protocol.plist

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryPlistTest {

    @Test
    fun `encodes the bplist00 magic`() {
        val bytes = BinaryPlist.encode(PlistValue.dict("a" to PlistValue.PBool(true)))

        assertEquals("bplist00", String(bytes, 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `a minimal dict encodes to exactly the expected object bytes`() {
        // {"a": true} -> dict(1 entry) keyref=1 valref=2, "a", true
        val bytes = BinaryPlist.encode(PlistValue.dict("a" to PlistValue.PBool(true)))

        assertContentEquals(
            byteArrayOf(0xD1.toByte(), 0x01, 0x02, 0x51, 0x61, 0x09),
            bytes.copyOfRange(8, 14),
        )
        // 8 magic + 6 objects + 3 offsets + 32 trailer
        assertEquals(49, bytes.size)
    }

    @Test
    fun `round-trips the play request body`() {
        val original = PlistValue.dict(
            "Content-Location" to PlistValue.PString("http://example.com/a.mp4"),
            "Start-Position" to PlistValue.PReal(0.0),
            "uuid" to PlistValue.PString("E1C4A2B0-0000-4000-8000-000000000001"),
        )

        val decoded = BinaryPlist.decode(BinaryPlist.encode(original)) as PlistValue.PDict

        assertEquals("http://example.com/a.mp4", decoded.string("Content-Location"))
        assertEquals(0.0, decoded.number("Start-Position"))
        assertEquals("E1C4A2B0-0000-4000-8000-000000000001", decoded.string("uuid"))
    }

    @Test
    fun `round-trips every supported value type`() {
        val original = PlistValue.dict(
            "s" to PlistValue.PString("hello"),
            "i" to PlistValue.PInt(42),
            "big" to PlistValue.PInt(4_000_000_000L),
            "r" to PlistValue.PReal(1.5),
            "t" to PlistValue.PBool(true),
            "f" to PlistValue.PBool(false),
            "d" to PlistValue.PData(byteArrayOf(1, 2, 3)),
            "a" to PlistValue.PArray(
                listOf(PlistValue.PString("x"), PlistValue.PInt(7))
            ),
        )

        val decoded = BinaryPlist.decode(BinaryPlist.encode(original)) as PlistValue.PDict

        assertEquals("hello", decoded.string("s"))
        assertEquals(42.0, decoded.number("i"))
        assertEquals(4.0E9, decoded.number("big"))
        assertEquals(1.5, decoded.number("r"))
        assertEquals(true, decoded.bool("t"))
        assertEquals(false, decoded.bool("f"))
        assertContentEquals(byteArrayOf(1, 2, 3), (decoded["d"] as PlistValue.PData).value)
        assertEquals(2, (decoded["a"] as PlistValue.PArray).values.size)
    }

    @Test
    fun `strings longer than 14 bytes use the extended length form`() {
        // Exercises the 0x?F + int-marker length path in both directions.
        val long = "x".repeat(300)
        val decoded = BinaryPlist.decode(
            BinaryPlist.encode(PlistValue.dict("k" to PlistValue.PString(long)))
        ) as PlistValue.PDict

        assertEquals(long, decoded.string("k"))
    }

    @Test
    fun `non-ASCII strings survive as UTF-16`() {
        val decoded = BinaryPlist.decode(
            BinaryPlist.encode(PlistValue.dict("name" to PlistValue.PString("客廳 Apple TV")))
        ) as PlistValue.PDict

        assertEquals("客廳 Apple TV", decoded.string("name"))
    }

    @Test
    fun `nested containers round-trip`() {
        val original = PlistValue.dict(
            "outer" to PlistValue.PDict(
                linkedMapOf(
                    "inner" to PlistValue.PArray(listOf(PlistValue.PReal(0.25)))
                )
            )
        )

        val decoded = BinaryPlist.decode(BinaryPlist.encode(original)) as PlistValue.PDict
        val outer = decoded["outer"] as PlistValue.PDict
        val inner = outer["inner"] as PlistValue.PArray

        assertEquals(0.25, (inner.values[0] as PlistValue.PReal).value)
    }

    @Test
    fun `a realistic playback-info response decodes`() {
        val encoded = BinaryPlist.encode(
            PlistValue.dict(
                "duration" to PlistValue.PReal(596.473),
                "position" to PlistValue.PReal(12.5),
                "rate" to PlistValue.PReal(1.0),
                "readyToPlay" to PlistValue.PBool(true),
                "playbackBufferEmpty" to PlistValue.PBool(false),
            )
        )

        val info = BinaryPlist.decode(encoded) as PlistValue.PDict

        assertEquals(596.473, info.number("duration"))
        assertEquals(12.5, info.number("position"))
        assertEquals(1.0, info.number("rate"))
        assertEquals(true, info.bool("readyToPlay"))
    }

    @Test
    fun `integers are accepted where a real is expected`() {
        // Receivers spell numbers inconsistently: rate often arrives as an int.
        val encoded = BinaryPlist.encode(PlistValue.dict("rate" to PlistValue.PInt(1)))
        val decoded = BinaryPlist.decode(encoded) as PlistValue.PDict

        assertEquals(1.0, decoded.number("rate"))
    }

    @Test
    fun `malformed input is rejected rather than misread`() {
        assertFailsWith<BinaryPlist.FormatException> { BinaryPlist.decode(ByteArray(4)) }
        assertFailsWith<BinaryPlist.FormatException> {
            BinaryPlist.decode("not a plist at all, but long enough to pass the size check....".toByteArray())
        }
    }

    @Test
    fun `a huge declared object count does not allocate`() {
        // A 40-byte input claiming 2 billion objects must be rejected on the
        // trailer, not by OutOfMemoryError from IntArray(count).
        val good = BinaryPlist.encode(PlistValue.dict("a" to PlistValue.PString("b")))
        val evil = good.copyOf()
        // numObjects is the 8 bytes at trailer+8.
        val trailer = evil.size - 32
        for (i in 0 until 8) evil[trailer + 8 + i] = 0
        evil[trailer + 8 + 4] = 0x7F
        evil[trailer + 8 + 5] = 0xFF.toByte()
        evil[trailer + 8 + 6] = 0xFF.toByte()
        evil[trailer + 8 + 7] = 0xFF.toByte()

        assertFailsWith<BinaryPlist.FormatException> { BinaryPlist.decode(evil) }
    }

    @Test
    fun `an extended length marker on the last byte does not throw AIOOBE`() {
        val good = BinaryPlist.encode(PlistValue.dict("a" to PlistValue.PString("b")))
        // Point the root object at a 0x5F (extended-length ASCII string) marker
        // sitting at the very end of the object area.
        val evil = good.copyOf()
        evil[8] = 0x5F
        val error = runCatching { BinaryPlist.decode(evil) }.exceptionOrNull()
        assertTrue(error == null || error is BinaryPlist.FormatException, "got " + error)
    }

    @Test
    fun `a truncated but well-headed plist fails cleanly`() {
        val good = BinaryPlist.encode(PlistValue.dict("a" to PlistValue.PString("b")))
        val truncated = good.copyOfRange(0, good.size - 8) + ByteArray(8)

        // Must throw our own type, never an ArrayIndexOutOfBounds.
        val error = runCatching { BinaryPlist.decode(truncated) }.exceptionOrNull()
        assertTrue(error is BinaryPlist.FormatException, "got " + error)
    }
}
