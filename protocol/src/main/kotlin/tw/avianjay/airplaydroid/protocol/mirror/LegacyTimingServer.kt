package tw.avianjay.airplaydroid.protocol.mirror

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Answers a legacy receiver's clock queries, so it can map our timestamps onto
 * its own clock.
 *
 * In legacy mirroring the **receiver** is the timing client: once it has our
 * `timingPort` (sent in SETUP, or port 7010 for the port-7100 path) it sends a
 * query every few seconds and expects our current time back. Two wire formats
 * are in use, and this answers both on one socket:
 *
 * | request | size | answer |
 * |---|---|---|
 * | AirTunes timing, `80 d2` | 32 | `80 d3`, origin / receive / transmit at 8, 16, 24 |
 * | NTP v4 client, mode 3 | 48 | NTP server, mode 4 |
 *
 * RPiPlay and UxPlay send the AirTunes form; the nto spec's port-7100 trace shows
 * plain NTP.
 *
 * All times are NTP-era (seconds since 1900) of the local wall clock -- the same
 * clock the video timestamps are read from, which is the only thing the receiver
 * needs: it computes the offset, not the absolute time.
 *
 * ### Why an unanswered query matters
 *
 * iPhoneMirror (airplay2dll) accepts the whole session, then renders nothing
 * until a query has been answered: from behind the Android emulator's NAT, which
 * drops inbound UDP, it sat in "handshaking" indefinitely on a stream it decoded
 * fine from the PC. LonelyScreen never asks.
 */
class LegacyTimingServer(preferredPort: Int = PREFERRED_PORT) : Closeable {

    private val socket = bind(preferredPort)

    /** The UDP port to advertise as `timingPort`. */
    val port: Int get() = socket.localPort

    /** How many queries have been answered: zero means the receiver never asked. */
    val answered = AtomicInteger()

    /** Packets that were neither format, and the start of the last one, for diagnosis. */
    val ignored = AtomicInteger()
    @Volatile var lastIgnored: String? = null
        private set

    init {
        thread(isDaemon = true, name = "legacy-timing") {
            val buffer = ByteArray(128)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    break
                }
                val received = nowNtp()
                val reply = reply(buffer.copyOf(packet.length), received)
                if (reply == null) {
                    ignored.incrementAndGet()
                    lastIgnored = "${packet.length} bytes from ${packet.socketAddress}: " +
                        buffer.copyOf(minOf(packet.length, 16)).joinToString("") { "%02x".format(it) }
                    continue
                }
                runCatching {
                    socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    answered.incrementAndGet()
                }
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        /**
         * The port-7100 path has no SETUP to advertise a port in: the nto spec
         * says the receiver sends its NTP queries to the sender's port 7010,
         * "which seems hard-coded". So this is tried first on both paths.
         */
        const val PREFERRED_PORT = 7010

        /** [preferred], or any free port if that one is taken (two sessions, say). */
        private fun bind(preferred: Int): DatagramSocket =
            runCatching { DatagramSocket(preferred) }.getOrElse { DatagramSocket(0) }

        /** Seconds between 1900 (NTP) and 1970 (Unix). */
        private const val NTP_UNIX_OFFSET = 2_208_988_800L

        /** Now, as a 64-bit NTP timestamp. */
        fun nowNtp(): Long = unixMillisToNtp(System.currentTimeMillis())

        fun unixMillisToNtp(millis: Long): Long {
            val seconds = millis / 1000 + NTP_UNIX_OFFSET
            val fraction = (millis % 1000) * 0x1_0000_0000L / 1000
            return (seconds shl 32) or fraction
        }

        /**
         * The answer to one query, or null if [request] is neither format.
         * [received] is when it arrived; the transmit time is taken on the way out.
         */
        internal fun reply(request: ByteArray, received: Long): ByteArray? {
            if (request.size == 32 && request[0] == 0x80.toByte() && (request[1].toInt() and 0x7f) == 0x52) {
                val out = ByteBuffer.allocate(32)
                out.put(0x80.toByte()).put(0xd3.toByte()).put(request[2]).put(request[3])
                out.putInt(0)
                out.putLong(ByteBuffer.wrap(request, 24, 8).long) // origin = their transmit time
                out.putLong(received)
                out.putLong(nowNtp())
                return out.array()
            }
            if (request.size >= 48 && (request[0].toInt() and 0x07) == 3) {
                val out = ByteBuffer.allocate(48)
                out.put(0x24) // LI 0, version 4, mode 4 (server)
                out.put(1) // stratum 1
                out.put(request[2]) // poll
                out.put(0xe8.toByte()) // precision
                out.putInt(0) // root delay
                out.putInt(0) // root dispersion
                out.put("AIRP".toByteArray())
                out.putLong(0) // reference timestamp
                out.putLong(ByteBuffer.wrap(request, 40, 8).long) // origin = their transmit time
                out.putLong(received)
                out.putLong(nowNtp())
                return out.array()
            }
            return null
        }
    }
}
