package tw.avianjay.airplaydroid.protocol.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HapFramesTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `messages larger than one frame round-trip`() {
        val wire = ByteArrayOutputStream()
        val message = ByteArray(2500) { (it % 251).toByte() }
        HapFrameOutputStream(wire, key).apply { write(message); flush() }

        // 1024 + 1024 + 452, each with a 2-byte header and a 16-byte tag
        assertEquals(message.size + 3 * (2 + 16), wire.size())
        assertEquals(0x00, wire.toByteArray()[0].toInt())
        assertEquals(0x04, wire.toByteArray()[1].toInt())

        val read = HapFrameInputStream(ByteArrayInputStream(wire.toByteArray()), key).readBytes()
        assertContentEquals(message, read)
    }

    @Test
    fun `counters advance across flushes`() {
        val wire = ByteArrayOutputStream()
        HapFrameOutputStream(wire, key).apply {
            write("first".toByteArray()); flush()
            write("second".toByteArray()); flush()
        }

        val read = HapFrameInputStream(ByteArrayInputStream(wire.toByteArray()), key).readBytes()
        assertEquals("firstsecond", String(read))
    }

    @Test
    fun `a tampered frame is rejected`() {
        val wire = ByteArrayOutputStream()
        HapFrameOutputStream(wire, key).apply { write("hello".toByteArray()); flush() }
        val bytes = wire.toByteArray().also { it[3] = (it[3].toInt() xor 1).toByte() }

        assertFailsWith<IOException> { HapFrameInputStream(ByteArrayInputStream(bytes), key).read() }
    }

    @Test
    fun `nonce is four zero bytes then a little-endian counter`() {
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0x02, 0x01, 0, 0, 0, 0, 0, 0),
            HapFrames.nonce(0x0102),
        )
    }
}
