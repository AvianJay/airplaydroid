package tw.avianjay.airplaydroid.protocol.mirror

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The AirPlay **1 (legacy)** mirroring data stream: the 128-byte-header
 * packetisation used by the port-7100 `/stream` endpoint.
 *
 * This is the legacy counterpart of the AirPlay 2 path in [MirrorSession]. The
 * two are entirely different protocols, and which one a receiver wants is
 * decided by whether it advertises HAP pairing, not by its model name.
 *
 * ### The header
 *
 * Every packet is a 128-byte header followed by an optional payload. Only the
 * first 64 bytes carry anything; the rest is zero padding that the format
 * reserves. The used fields are little-endian:
 *
 * ```
 * offset  size  field
 * 0       4     payload size
 * 4       2     payload type
 * 6       2     0x1e if type == 2, else 0x06
 * 8       8     NTP timestamp
 * ```
 *
 * ### The three packet types
 *
 * | type | name | carries |
 * |---|---|---|
 * | 0 | video bitstream | AVCC-framed H.264 |
 * | 1 | codec data | the `avcC` record, resent whenever the video format changes |
 * | 2 | heartbeat | no payload |
 *
 * The `0x1e`/`0x06` field is not a constant to be chosen freely: it tracks the
 * type, and a receiver that sees them disagree drops the stream. It is computed
 * here rather than passed in.
 */
object LegacyStreamPackets {

    const val HEADER_BYTES = 128

    /**
     * Only the first 64 bytes of the header are meaningful. The remaining 64 are
     * reserved and must be present, because the header is a fixed 128 bytes on
     * the wire.
     */
    const val HEADER_USED_BYTES = 64

    const val TYPE_VIDEO = 0
    const val TYPE_CODEC_DATA = 1
    const val TYPE_HEARTBEAT = 2

    /** The discriminator at offset 6: `0x1e` for a heartbeat, `0x06` otherwise. */
    const val MARKER_HEARTBEAT: Short = 0x1e
    const val MARKER_DEFAULT: Short = 0x06

    /** The value the discriminator must take for [type]. */
    fun markerFor(type: Int): Short = if (type == TYPE_HEARTBEAT) MARKER_HEARTBEAT else MARKER_DEFAULT

    /**
     * Two generations of the same header.
     *
     * [NTO] is the iOS 5-6 packet the nto spec documents, used on the port-7100
     * path: the type is a 16-bit field and offset 6 is `0x06`, or `0x1e` for a
     * heartbeat.
     *
     * [IOS9] is what current senders put on the RTSP type-110 data channel, the
     * same header the AirPlay 2 path sends to an Apple TV: the type is byte 4
     * alone, byte 5 carries `0x10` on a keyframe, and offset 6 is `16 01` on a
     * codec packet (UxPlay tells H.264 from HEVC by that byte), `1e 00` on a
     * heartbeat and zero on video.
     */
    enum class HeaderStyle { NTO, IOS9 }

    /** Bytes 6-7, as a little-endian short, that [style] gives [type]. */
    fun markerFor(type: Int, style: HeaderStyle): Short = when (style) {
        HeaderStyle.NTO -> markerFor(type)
        HeaderStyle.IOS9 -> when (type) {
            TYPE_CODEC_DATA -> MARKER_IOS9_CODEC
            TYPE_HEARTBEAT -> MARKER_HEARTBEAT
            else -> 0
        }
    }

    /** `16 01` read little-endian: an H.264 codec packet in the [HeaderStyle.IOS9] header. */
    const val MARKER_IOS9_CODEC: Short = 0x0116

    /** Byte 5 of an [HeaderStyle.IOS9] video packet that starts with an IDR. */
    const val FLAG_KEYFRAME = 0x10

    /**
     * Builds one packet: a 128-byte header plus [payload].
     *
     * [ntpTimestamp] is the presentation time on the receiver's clock, in NTP
     * units (seconds since 1900 in the high 32 bits, fraction in the low 32).
     * The caller is responsible for mapping its clock onto the receiver's; see
     * [ReceiverClock].
     */
    fun packet(
        type: Int,
        ntpTimestamp: Long,
        payload: ByteArray = ByteArray(0),
        style: HeaderStyle = HeaderStyle.NTO,
        keyframe: Boolean = false,
    ): ByteArray {
        require(type == TYPE_VIDEO || type == TYPE_CODEC_DATA || type == TYPE_HEARTBEAT) {
            "unknown legacy stream packet type $type"
        }
        require(type != TYPE_HEARTBEAT || payload.isEmpty()) {
            "a heartbeat carries no payload, got ${payload.size} bytes"
        }

        val out = ByteArray(HEADER_BYTES + payload.size)
        val header = ByteBuffer.wrap(out, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(payload.size)
        header.put(type.toByte())
        header.put(if (style == HeaderStyle.IOS9 && keyframe) FLAG_KEYFRAME.toByte() else 0)
        header.putShort(markerFor(type, style))
        header.putLong(ntpTimestamp)
        // bytes 16..63 stay zero, and 64..127 are reserved padding.
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /** A video-bitstream packet carrying AVCC-framed H.264. */
    fun video(ntpTimestamp: Long, avcc: ByteArray): ByteArray =
        packet(TYPE_VIDEO, ntpTimestamp, avcc)

    /**
     * A codec-data packet carrying the `avcC` record.
     *
     * Sent at the start of the stream, and again whenever the video format
     * changes -- a rotation, a resolution change, or the screen going on or off.
     * A receiver that never sees one has no SPS/PPS and cannot decode anything.
     */
    fun codecData(ntpTimestamp: Long, avcC: ByteArray): ByteArray =
        packet(TYPE_CODEC_DATA, ntpTimestamp, avcC)

    /**
     * A codec-data packet that also states the picture size.
     *
     * The iPad capture in the nto spec carries it as little-endian floats: the
     * encoded size at 16, and the source and destination rectangles' sizes at 40
     * and 56. A receiver that sizes its window from the header rather than from
     * the SPS needs them; one that does not ignores bytes it never reads.
     */
    fun codecData(
        ntpTimestamp: Long,
        avcC: ByteArray,
        width: Int,
        height: Int,
        style: HeaderStyle = HeaderStyle.NTO,
    ): ByteArray {
        val out = packet(TYPE_CODEC_DATA, ntpTimestamp, avcC, style)
        val header = ByteBuffer.wrap(out, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in intArrayOf(16, 40, 56)) {
            header.putFloat(offset, width.toFloat())
            header.putFloat(offset + 4, height.toFloat())
        }
        return out
    }

    /** A heartbeat: header only. */
    fun heartbeat(ntpTimestamp: Long): ByteArray = packet(TYPE_HEARTBEAT, ntpTimestamp)

    /** One decoded packet, for tests and for a receiver-side reader. */
    data class Decoded(
        val type: Int,
        val ntpTimestamp: Long,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Decoded && type == other.type &&
                    ntpTimestamp == other.ntpTimestamp && payload.contentEquals(other.payload)
                )

        override fun hashCode(): Int =
            (31 * type + ntpTimestamp.hashCode()) * 31 + payload.contentHashCode()
    }

    /**
     * Reads one packet from [bytes] at [offset].
     *
     * Returns null when the packet is incomplete, so a caller can wait for more
     * data. This is the receiver half of the format, used by the test fixture.
     */
    fun decode(bytes: ByteArray, offset: Int = 0): Decoded? {
        if (bytes.size - offset < HEADER_BYTES) return null
        val header = ByteBuffer.wrap(bytes, offset, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val size = header.getInt()
        // Byte 5 is the keyframe flag in the iOS 9 header and zero in the nto one.
        val type = header.get().toInt()
        header.get()
        val marker = header.getShort()
        val timestamp = header.getLong()

        if (size < 0 || bytes.size - offset - HEADER_BYTES < size) return null
        if (marker != markerFor(type) && marker != markerFor(type, HeaderStyle.IOS9)) {
            throw IllegalArgumentException(
                "packet marker 0x%02x does not match type %d (expected 0x%02x)"
                    .format(marker, type, markerFor(type))
            )
        }
        return Decoded(type, timestamp, bytes.copyOfRange(offset + HEADER_BYTES, offset + HEADER_BYTES + size))
    }

    /** Total on-wire size of a packet carrying a payload of [payloadSize]. */
    fun wireSize(payloadSize: Int): Int = HEADER_BYTES + payloadSize
}

/**
 * Converts between the two time representations a legacy mirroring session needs.
 *
 * The legacy path is NTP-based: the receiver sends NTP requests to the sender on
 * port 7010 and the sender answers, which fixes the offset between the two
 * clocks. Video is then stamped with that corrected NTP time.
 *
 * Kept separate from [ReceiverClock] (which serves the AirPlay 2 PTP path)
 * because the two protocols exchange different timestamps.
 */
class NtpClock {

    /** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
    private val ntpEpochOffsetSeconds = 2_208_988_800L

    /**
     * Packs a wall-clock instant into the 64-bit NTP timestamp the stream header
     * carries: seconds since 1900 in the high word, binary fraction in the low.
     */
    fun toNtp(epochMillis: Long): Long {
        val seconds = epochMillis / 1000L + ntpEpochOffsetSeconds
        val millis = epochMillis % 1000L
        val fraction = (millis * 0x1_0000_0000L) / 1000L
        return (seconds shl 32) or (fraction and 0xFFFF_FFFFL)
    }

    /** The inverse of [toNtp], for tests and diagnostics. */
    fun fromNtp(ntp: Long): Long {
        val seconds = (ntp ushr 32) - ntpEpochOffsetSeconds
        val fraction = ntp and 0xFFFF_FFFFL
        return seconds * 1000L + (fraction * 1000L) / 0x1_0000_0000L
    }

    /**
     * The offset to add to local time to obtain the receiver's clock, from one
     * NTP request/response pair.
     *
     * [t1] is the sender's transmit time, [t2] the receiver's receive time, [t3]
     * the receiver's transmit time, [t4] the sender's receive time, all in
     * milliseconds. The standard NTP estimator is
     * `((t2 - t1) + (t3 - t4)) / 2`, which cancels the network delay.
     */
    fun offsetMillis(t1: Long, t2: Long, t3: Long, t4: Long): Long =
        ((t2 - t1) + (t3 - t4)) / 2

    /** Round-trip delay for the same exchange, `(t4 - t1) - (t3 - t2)`. */
    fun roundTripMillis(t1: Long, t2: Long, t3: Long, t4: Long): Long =
        (t4 - t1) - (t3 - t2)
}
