package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.pairing.HapCrypto
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The screen-audio stream (type 96) of a mirroring session: ALAC 44100/16/2
 * over RTP/UDP, each packet sealed with ChaCha20-Poly1305.
 *
 * Wire format, cross-checked between doubletake (tested against Apple TVs),
 * airplay2-receiver, pyatv and owntone:
 *
 *  - data packet, from [data] to the receiver's dataPort:
 *    `80 60 seq(u16) rtp(u32) ssrc=0(u32)` + ciphertext + 16-byte tag +
 *    8-byte little-endian nonce. Key = the 32 random bytes sent as `shk`,
 *    used raw; AAD = header bytes 4..12; nonce = 4 zero bytes + LE64 counter.
 *  - TimeAnnounce, from [control] (whose port the SETUP advertised) to the
 *    receiver's controlPort, unencrypted, 28 bytes:
 *    `90|80 D7 00 06` + (rtp - latency)(u32) + receiver time ns(u64) +
 *    rtp(u32) + ClockID(u64), all big-endian. It says: sample `rtp - latency`
 *    is due at that receiver time, i.e. sample `rtp` plays `latency` later.
 *    The first must carry 0x90 (the extension bit), or receivers treat it as
 *    a leftover from a previous session. Sent before the first packet, then
 *    every second.
 *  - retransmit: the receiver may send `80 D5 seq first(u16) count(u16)` to
 *    [control]; each held packet is re-sent byte-identical, prefixed with
 *    `80 D6 seq`.
 *
 * Timing uses [clock], the same mapping video timestamps use, so a sample
 * captured at local time t and a frame captured at t both present at
 * t + latency on the receiver.
 */
internal class ScreenAudioStream(
    private val host: InetAddress,
    private val dataPort: Int,
    private val controlPort: Int,
    private val control: DatagramSocket,
    private val key: ByteArray,
    private val clock: ReceiverClock,
    private val clockId: () -> Long,
    private val latencySamples: Int,
) : Closeable {

    private val data = DatagramSocket()
    private val history = arrayOfNulls<ByteArray>(HISTORY)
    private val lock = Any()

    private var seq = 1
    private var rtp: Long = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
    private var nonce = 0L
    private var lastSyncNanos = Long.MIN_VALUE
    private var expectedCaptureNanos = Long.MIN_VALUE
    private val frame = ByteArray(AlacVerbatim.frameSize())

    @Volatile private var closed = false
    val retransmitRequests = AtomicInteger()

    init {
        thread(isDaemon = true, name = "mirror-audio-control") { serveControl() }
    }

    /**
     * Sends one frame: exactly 352 stereo samples, interleaved, whose first
     * sample was captured at local monotonic time [captureNanos]. With
     * [transmit] false the packet is built and kept for retransmission but not
     * sent -- a test hook to provoke a retransmit request.
     */
    fun send(pcm: ShortArray, captureNanos: Long, transmit: Boolean = true) {
        synchronized(lock) {
            if (closed) return
            // A stall in capture (the phone was busy) would otherwise leave the
            // RTP timeline behind real time and push every later frame late.
            if (expectedCaptureNanos != Long.MIN_VALUE) {
                val gap = captureNanos - expectedCaptureNanos
                if (gap > MAX_GAP_NANOS) {
                    rtp = (rtp + gap * SAMPLE_RATE / 1_000_000_000L) and 0xFFFFFFFFL
                    lastSyncNanos = Long.MIN_VALUE // re-announce the mapping now
                } else if (gap < -MAX_GAP_NANOS) {
                    // Capture time stepped back (the source re-anchored). The RTP
                    // timeline cannot go back, but the announced mapping must
                    // follow, or the interval check below would stall for as long.
                    lastSyncNanos = Long.MIN_VALUE
                }
            }
            expectedCaptureNanos = captureNanos + FRAME_NANOS

            if (lastSyncNanos == Long.MIN_VALUE || captureNanos - lastSyncNanos >= SYNC_INTERVAL_NANOS) {
                sendSync(first = nonce == 0L, captureNanos)
                lastSyncNanos = captureNanos
            }

            val header = ByteArray(12)
            val be = ByteBuffer.wrap(header)
            header[0] = 0x80.toByte()
            header[1] = 0x60
            be.putShort(2, seq.toShort())
            be.putInt(4, rtp.toInt())
            // SSRC stays 0, as Apple senders send it.

            val length = AlacVerbatim.encode(pcm, frame)
            val nonceBytes = ByteArray(12)
            ByteBuffer.wrap(nonceBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(4, nonce++)
            val sealed = HapCrypto.seal(key, nonceBytes, frame.copyOf(length), header.copyOfRange(4, 12))

            val packet = ByteArray(12 + sealed.size + 8)
            System.arraycopy(header, 0, packet, 0, 12)
            System.arraycopy(sealed, 0, packet, 12, sealed.size)
            System.arraycopy(nonceBytes, 4, packet, 12 + sealed.size, 8)
            history[seq and (HISTORY - 1)] = packet

            if (transmit) runCatching { data.send(DatagramPacket(packet, packet.size, InetSocketAddress(host, dataPort))) }
            seq = (seq + 1) and 0xFFFF
            rtp = (rtp + AlacVerbatim.SAMPLES_PER_FRAME) and 0xFFFFFFFFL
        }
    }

    private fun sendSync(first: Boolean, captureNanos: Long) {
        val packet = ByteArray(28)
        val be = ByteBuffer.wrap(packet)
        packet[0] = if (first) 0x90.toByte() else 0x80.toByte()
        packet[1] = 0xD7.toByte()
        be.putShort(2, 6) // 28 / 4 - 1, as Apple senders send it
        be.putInt(4, (rtp - latencySamples).toInt())
        be.putLong(8, clock.receiverNanos(captureNanos))
        be.putInt(16, rtp.toInt())
        be.putLong(20, clockId())
        runCatching { control.send(DatagramPacket(packet, packet.size, InetSocketAddress(host, controlPort))) }
    }

    private fun serveControl() {
        val buffer = ByteArray(2048)
        while (!closed) {
            val incoming = DatagramPacket(buffer, buffer.size)
            try {
                control.receive(incoming)
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                continue
            }
            if (incoming.address != host || incoming.length < 8) continue
            if (buffer[1].toInt() and 0x7F != 0x55) continue // only retransmit requests
            retransmitRequests.incrementAndGet()
            val be = ByteBuffer.wrap(buffer)
            val requestSeq = be.getShort(2)
            val firstSeq = be.getShort(4).toInt() and 0xFFFF
            val count = be.getShort(6).toInt() and 0xFFFF
            for (i in 0 until minOf(count, HISTORY)) {
                val wanted = (firstSeq + i) and 0xFFFF
                val original = synchronized(lock) {
                    history[wanted and (HISTORY - 1)]?.takeIf { seqOf(it) == wanted }
                } ?: continue
                val reply = ByteArray(4 + original.size)
                reply[0] = 0x80.toByte()
                reply[1] = 0xD6.toByte()
                ByteBuffer.wrap(reply).putShort(2, requestSeq)
                System.arraycopy(original, 0, reply, 4, original.size)
                runCatching {
                    control.send(DatagramPacket(reply, reply.size, InetSocketAddress(host, incoming.port)))
                }
            }
        }
    }

    private fun seqOf(packet: ByteArray) =
        ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

    override fun close() {
        closed = true
        runCatching { control.close() }
        runCatching { data.close() }
    }

    companion object {
        const val SAMPLE_RATE = 44_100L
        private const val HISTORY = 512
        private const val FRAME_NANOS = AlacVerbatim.SAMPLES_PER_FRAME * 1_000_000_000L / SAMPLE_RATE
        private const val SYNC_INTERVAL_NANOS = 1_000_000_000L
        private const val MAX_GAP_NANOS = 100_000_000L

        /** A fresh 32-byte stream key, sent as `shk`. */
        fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}
