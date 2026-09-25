package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [LegacyTimingServer] answers both query formats a legacy receiver sends. */
class LegacyTimingServerTest {

    private fun ask(server: LegacyTimingServer, query: ByteArray): ByteArray =
        DatagramSocket().use { udp ->
            udp.soTimeout = 2_000
            udp.send(DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), server.port))
            val reply = DatagramPacket(ByteArray(128), 128)
            udp.receive(reply)
            reply.data.copyOf(reply.length)
        }

    private fun nearNow(ntp: Long) =
        abs((ntp ushr 32) - (LegacyTimingServer.nowNtp() ushr 32)) <= 2

    @Test
    fun `an AirTunes timing query gets an 80 d3 reply echoing its transmit time`() {
        LegacyTimingServer().use { server ->
            val query = ByteArray(32)
            query[0] = 0x80.toByte()
            query[1] = 0xd2.toByte()
            query[3] = 0x07
            ByteBuffer.wrap(query, 24, 8).putLong(0x0102030405060708L)

            val reply = ByteBuffer.wrap(ask(server, query))
            assertEquals(32, reply.capacity())
            assertEquals(0x80.toByte(), reply.get(0))
            assertEquals(0xd3.toByte(), reply.get(1))
            assertEquals(0x0102030405060708L, reply.getLong(8), "origin must be the query's transmit time")
            assertTrue(nearNow(reply.getLong(16)), "receive time is our NTP clock")
            assertTrue(nearNow(reply.getLong(24)), "transmit time is our NTP clock")
            assertEquals(1, server.answered.get())
        }
    }

    @Test
    fun `an NTP client query gets a mode 4 reply echoing its transmit time`() {
        LegacyTimingServer().use { server ->
            val query = ByteArray(48)
            query[0] = 0x23 // version 4, mode 3
            ByteBuffer.wrap(query, 40, 8).putLong(0x1112131415161718L)

            val reply = ByteBuffer.wrap(ask(server, query))
            assertEquals(48, reply.capacity())
            assertEquals(4, reply.get(0).toInt() and 0x07, "mode 4: server")
            assertEquals(0x1112131415161718L, reply.getLong(24), "origin must be the query's transmit time")
            assertTrue(nearNow(reply.getLong(40)))
        }
    }

    @Test
    fun `anything else is ignored`() {
        assertNull(LegacyTimingServer.reply(ByteArray(10), 0))
        assertNull(LegacyTimingServer.reply(ByteArray(32), 0), "32 bytes without 80 d2 is not a timing query")
    }

    @Test
    fun `NTP time is Unix time plus 70 years`() {
        val ntp = LegacyTimingServer.unixMillisToNtp(1_500)
        assertEquals(2_208_988_801L, ntp ushr 32)
        assertEquals(0x8000_0000L, ntp and 0xFFFF_FFFFL, "half a second")
    }
}
