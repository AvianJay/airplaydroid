package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The legacy 128-byte-header stream packetisation.
 *
 * The field offsets and sizes here come from the unofficial AirPlay spec's
 * screen-mirroring section, and the marker rule (`0x1e` for a heartbeat, `0x06`
 * otherwise) is asserted explicitly because a receiver that sees the marker and
 * the type disagree drops the stream.
 */
class LegacyStreamPacketsTest {

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun littleEndianLong(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    /** Kotlin's Byte is signed, so 0xc0 and friends need an explicit conversion. */
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `a video packet has a 128-byte header followed by the payload`() {
        val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 0x11)
        val packet = LegacyStreamPackets.video(0x1122334455667788L, avcc)

        assertEquals(128 + avcc.size, packet.size)
        assertEquals(avcc.size, littleEndianInt(packet, 0), "payload size at offset 0")
        assertEquals(LegacyStreamPackets.TYPE_VIDEO.toShort(), littleEndianShort(packet, 4), "type at offset 4")
        assertEquals(LegacyStreamPackets.MARKER_DEFAULT, littleEndianShort(packet, 6), "marker at offset 6")
        assertEquals(0x1122334455667788L, littleEndianLong(packet, 8), "NTP timestamp at offset 8")
        assertContentEquals(avcc, packet.copyOfRange(128, packet.size))
    }

    @Test
    fun `the marker is 0x1e only for a heartbeat`() {
        // The invariant a receiver enforces. Getting it wrong drops the stream.
        assertEquals(0x1e.toShort(), littleEndianShort(LegacyStreamPackets.heartbeat(0), 6))
        assertEquals(0x06.toShort(), littleEndianShort(LegacyStreamPackets.video(0, ByteArray(4)), 6))
        assertEquals(0x06.toShort(), littleEndianShort(LegacyStreamPackets.codecData(0, ByteArray(4)), 6))

        assertEquals(LegacyStreamPackets.MARKER_HEARTBEAT, LegacyStreamPackets.markerFor(2))
        assertEquals(LegacyStreamPackets.MARKER_DEFAULT, LegacyStreamPackets.markerFor(0))
        assertEquals(LegacyStreamPackets.MARKER_DEFAULT, LegacyStreamPackets.markerFor(1))
    }

    @Test
    fun `a heartbeat carries no payload and is exactly one header`() {
        val packet = LegacyStreamPackets.heartbeat(42)
        assertEquals(128, packet.size)
        assertEquals(0, littleEndianInt(packet, 0))
        assertEquals(LegacyStreamPackets.TYPE_HEARTBEAT.toShort(), littleEndianShort(packet, 4))
    }

    @Test
    fun `a heartbeat with a payload is refused`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyStreamPackets.packet(LegacyStreamPackets.TYPE_HEARTBEAT, 0, byteArrayOf(1))
        }
    }

    @Test
    fun `an unknown packet type is refused`() {
        assertFailsWith<IllegalArgumentException> { LegacyStreamPackets.packet(7, 0) }
    }

    @Test
    fun `the header's used half is 64 bytes and the rest is padding`() {
        // The format reserves 128 bytes; only the first 64 are used. A receiver
        // expects the full 128 regardless.
        val packet = LegacyStreamPackets.video(0, ByteArray(0))
        assertEquals(128, packet.size)
        val used = packet.copyOfRange(0, LegacyStreamPackets.HEADER_USED_BYTES)
        assertTrue(used.any { it != 0.toByte() }, "the used half should not be empty")
        assertTrue(
            packet.copyOfRange(LegacyStreamPackets.HEADER_USED_BYTES, 128).all { it == 0.toByte() },
            "bytes 64..127 are reserved and must be zero",
        )
    }

    @Test
    fun `packets round-trip through the decoder`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val timestamp = 0x0EADBEEF12345678L

        for (type in listOf(LegacyStreamPackets.TYPE_VIDEO, LegacyStreamPackets.TYPE_CODEC_DATA)) {
            val packet = LegacyStreamPackets.packet(type, timestamp, payload)
            val decoded = LegacyStreamPackets.decode(packet)!!
            assertEquals(type, decoded.type)
            assertEquals(timestamp, decoded.ntpTimestamp)
            assertContentEquals(payload, decoded.payload)
        }
    }

    @Test
    fun `the decoder returns null for an incomplete packet rather than guessing`() {
        val packet = LegacyStreamPackets.video(0, ByteArray(50))

        // Truncated header.
        assertNull(LegacyStreamPackets.decode(packet.copyOf(100)))
        // Complete header but a short payload.
        assertNull(LegacyStreamPackets.decode(packet.copyOf(140)))
        // The whole thing decodes.
        assertEquals(50, LegacyStreamPackets.decode(packet)!!.payload.size)
    }

    @Test
    fun `the decoder rejects a packet whose marker disagrees with its type`() {
        val packet = LegacyStreamPackets.video(0, ByteArray(8))
        // Force the marker to the heartbeat value while leaving the type as video.
        packet[6] = 0x1e.toByte()
        val e = assertFailsWith<IllegalArgumentException> { LegacyStreamPackets.decode(packet) }
        assertTrue(e.message!!.contains("marker"), "message should mention the marker: ${e.message}")
    }

    @Test
    fun `codec data carries the avcC record`() {
        // The real shape, from the spec's iPad capture: avcC begins 0x01, then
        // profile (0x64 = High), compatibility (0xc0) and level (0x28 = 4.0).
        val avcC = hex("0164c028ffe10010")
        val packet = LegacyStreamPackets.codecData(0, avcC)

        val decoded = LegacyStreamPackets.decode(packet)!!
        assertEquals(LegacyStreamPackets.TYPE_CODEC_DATA, decoded.type)
        assertEquals(0x01.toByte(), decoded.payload[0], "avcC configurationVersion")
        assertContentEquals(avcC, decoded.payload)
    }

    @Test
    fun `wireSize matches what packet actually produces`() {
        for (size in listOf(0, 1, 64, 1500, 65536)) {
            assertEquals(
                LegacyStreamPackets.wireSize(size),
                LegacyStreamPackets.packet(LegacyStreamPackets.TYPE_VIDEO, 0, ByteArray(size)).size,
            )
        }
    }
}
