package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.pairing.HapCrypto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenAudioTest {

    /** Reads bits MSB-first -- written independently of the encoder's accumulator. */
    private class BitReader(val bytes: ByteArray) {
        var pos = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val bit = (bytes[pos / 8].toInt() ushr (7 - pos % 8)) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }
    }

    private fun ramp(samples: Int = 352) = ShortArray(samples * 2) { i ->
        // Covers sign bits, extremes and both channels differing.
        when (i) {
            0 -> Short.MIN_VALUE
            1 -> Short.MAX_VALUE
            else -> ((i * 7919) % 65536 - 32768).toShort()
        }
    }

    @Test
    fun `alac verbatim frame has the documented header and size`() {
        val out = ByteArray(AlacVerbatim.frameSize())
        val n = AlacVerbatim.encode(ramp(), out)

        assertEquals(1416, n)
        // doubletake's alac_test pins this prefix: tag 1, partial, escape, count 352.
        assertContentEquals(byteArrayOf(0x20, 0x00, 0x12, 0x00, 0x00, 0x02), out.copyOf(6))
    }

    @Test
    fun `alac verbatim frame decodes back to the same samples`() {
        val pcm = ramp()
        val out = ByteArray(AlacVerbatim.frameSize())
        AlacVerbatim.encode(pcm, out)

        val r = BitReader(out)
        assertEquals(1, r.read(3)) // channel pair
        assertEquals(0, r.read(4))
        assertEquals(0, r.read(12))
        assertEquals(1, r.read(1)) // partial frame
        assertEquals(0, r.read(2))
        assertEquals(1, r.read(1)) // escape
        assertEquals(352, r.read(32))
        for (i in pcm.indices) assertEquals(pcm[i], r.read(16).toShort(), "sample $i")
        assertEquals(7, r.read(3)) // end
        assertEquals(0, r.read(8 - r.pos % 8)) // zero padding
        assertEquals(out.size * 8, r.pos)
    }

    @Test
    fun `packets decrypt the way airplay2-receiver decrypts them, and sync precedes them`() {
        val receiverData = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val receiverControl = DatagramSocket(0, InetAddress.getLoopbackAddress())
        receiverData.soTimeout = 2_000
        receiverControl.soTimeout = 2_000
        val key = ScreenAudioStream.newKey()
        val clock = ReceiverClock(receiverMs = 5_000, localNanos = 1_000_000_000)
        val stream = ScreenAudioStream(
            InetAddress.getLoopbackAddress(), receiverData.localPort, receiverControl.localPort,
            DatagramSocket(0, InetAddress.getLoopbackAddress()), key, clock, { 0x1122334455667788 }, 11_025,
        )
        try {
            val pcm = ramp()
            stream.send(pcm, captureNanos = 1_500_000_000) // 0.5 s after the anchor
            stream.send(pcm, captureNanos = 1_500_000_000 + 7_981_859)

            // --- the first TimeAnnounce ---
            val sync = ByteArray(64).let { buf ->
                DatagramPacket(buf, buf.size).also { receiverControl.receive(it) }.let { buf.copyOf(it.length) }
            }
            assertEquals(28, sync.size)
            assertEquals(0x90, sync[0].toInt() and 0xFF)
            assertEquals(0xD7, sync[1].toInt() and 0xFF)
            val s = ByteBuffer.wrap(sync)
            assertEquals(6, s.getShort(2).toInt())
            val applyRtp = s.getInt(16).toLong() and 0xFFFFFFFFL
            assertEquals((applyRtp - 11_025) and 0xFFFFFFFFL, s.getInt(4).toLong() and 0xFFFFFFFFL)
            assertEquals(5_500_000_000L, s.getLong(8)) // receiver ns at capture
            assertEquals(0x1122334455667788, s.getLong(20))

            // --- data packets: nonce = data[-8:], tag = data[-24:-8], aad = data[4:12] ---
            val rtps = (0 until 2).map { index ->
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size).also { receiverData.receive(it) }
                val data = buf.copyOf(p.length)
                assertEquals(12 + 1416 + 16 + 8, data.size)
                assertEquals(0x80, data[0].toInt() and 0xFF)
                assertEquals(0x60, data[1].toInt() and 0xFF)
                val nonce = ByteArray(4) + data.copyOfRange(data.size - 8, data.size)
                val plain = HapCrypto.open(key, nonce, data.copyOfRange(12, data.size - 8), data.copyOfRange(4, 12))
                val expected = ByteArray(AlacVerbatim.frameSize()).also { AlacVerbatim.encode(pcm, it) }
                assertContentEquals(expected, plain)
                assertEquals(index.toLong(), ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).getLong(4))
                assertEquals(0, ByteBuffer.wrap(data).getInt(8)) // SSRC
                ByteBuffer.wrap(data).getInt(4).toLong() and 0xFFFFFFFFL
            }
            assertEquals(applyRtp, rtps[0])
            assertEquals((rtps[0] + 352) and 0xFFFFFFFFL, rtps[1])
        } finally {
            stream.close()
            receiverData.close()
            receiverControl.close()
        }
    }

    @Test
    fun `a withheld packet is re-sent byte-identical on request`() {
        val receiverData = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val receiverControl = DatagramSocket(0, InetAddress.getLoopbackAddress())
        receiverControl.soTimeout = 2_000
        val senderControl = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val stream = ScreenAudioStream(
            InetAddress.getLoopbackAddress(), receiverData.localPort, receiverControl.localPort,
            senderControl, ScreenAudioStream.newKey(), ReceiverClock(0, 0), { 1L }, 11_025,
        )
        try {
            stream.send(ramp(), 1_000, transmit = false) // seq 1, never sent
            receiverControl.receive(DatagramPacket(ByteArray(64), 64)) // its sync

            val request = ByteArray(8)
            request[0] = 0x80.toByte(); request[1] = 0xD5.toByte()
            ByteBuffer.wrap(request).putShort(2, 42).putShort(4, 1).putShort(6, 1)
            receiverControl.send(DatagramPacket(request, 8, InetSocketAddress(InetAddress.getLoopbackAddress(), senderControl.localPort)))

            val buf = ByteArray(2048)
            val reply = DatagramPacket(buf, buf.size).also { receiverControl.receive(it) }
            assertEquals(0x80, buf[0].toInt() and 0xFF)
            assertEquals(0xD6, buf[1].toInt() and 0xFF)
            assertEquals(42, ByteBuffer.wrap(buf).getShort(2).toInt())
            assertEquals(4 + 12 + 1416 + 16 + 8, reply.length)
            assertEquals(1, ByteBuffer.wrap(buf).getShort(4 + 2).toInt()) // the original seq
            assertEquals(1, stream.retransmitRequests.get())
        } finally {
            stream.close()
            receiverData.close()
            receiverControl.close()
        }
    }

    @Test
    fun `receiver clock slews toward the least-delayed sample, boundedly`() {
        val clock = ReceiverClock(receiverMs = 1_000, localNanos = 0)
        assertEquals(1_000_000_000L, clock.receiverNanos(0))

        // A reply that says the receiver is 10 ms further ahead than thought.
        clock.observe(receiverMs = 2_010, localNanos = 1_000_000_000)
        assertEquals(1_000_000_000L + 1_000_000_000L + ReceiverClock.MAX_SLEW_NANOS, clock.receiverNanos(1_000_000_000))

        // A late (delayed) reply does not pull it back while a better sample is in the window.
        clock.observe(receiverMs = 3_000, localNanos = 2_000_000_000)
        assertTrue(clock.receiverNanos(2_000_000_000) > 3_000_000_000L)
    }

    @Test
    fun `fixed point conversion`() {
        assertEquals((1L shl 32) or (1L shl 31), ReceiverClock.toFixed32(1_500_000_000))
    }
}
