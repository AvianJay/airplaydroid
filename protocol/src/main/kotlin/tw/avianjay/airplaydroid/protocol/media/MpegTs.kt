package tw.avianjay.airplaydroid.protocol.media

import java.io.ByteArrayOutputStream

/**
 * An MPEG-2 transport stream writer for one H.264 video and one AAC audio
 * stream: the container of an HLS segment, and the one every AirPlay receiver
 * plays.
 *
 * It writes what Apple's HLS authoring rules ask of a segment and nothing more:
 * a PAT and PMT at the start of every segment ([writeTables]), an access-unit
 * delimiter before every picture, SPS and PPS before every keyframe, a PCR on
 * every video PES, and AAC in ADTS. PTS only, no DTS: the encoder is configured
 * without B-frames, so decode order is presentation order.
 *
 * Timestamps are microseconds on the caller's clock; they are offset by
 * [PTS_OFFSET_90K] so the PCR, which is written without it, always runs a little
 * ahead of presentation, as a decoder's buffer model expects.
 */
class MpegTsWriter(
    private val hasVideo: Boolean,
    private val hasAudio: Boolean,
) {
    init {
        require(hasVideo || hasAudio) { "a stream needs video, audio or both" }
    }

    private val continuity = IntArray(0x2000)
    private val out = ByteArrayOutputStream()

    /** Everything written since the last [take]. */
    fun take(): ByteArray = out.toByteArray().also { out.reset() }

    val pending: Int get() = out.size()

    /** PAT and PMT. Written at the start of each segment, so every segment decodes on its own. */
    fun writeTables() {
        writeSection(PID_PAT, patSection())
        writeSection(PID_PMT, pmtSection())
    }

    /**
     * One H.264 access unit in Annex B. [sps] and [pps] (without start codes)
     * are written ahead of it when [keyframe] -- MediaCodec hands them over
     * once, as codec config, but a segment must carry its own.
     */
    fun writeVideo(annexB: ByteArray, ptsUs: Long, keyframe: Boolean, sps: ByteArray?, pps: ByteArray?) {
        val payload = ByteArrayOutputStream(annexB.size + 64)
        payload.write(AUD)
        if (keyframe) {
            sps?.let { payload.write(START_CODE); payload.write(it) }
            pps?.let { payload.write(START_CODE); payload.write(it) }
        }
        payload.write(annexB)
        val pts = to90k(ptsUs)
        writePes(PID_VIDEO, STREAM_ID_VIDEO, payload.toByteArray(), pts + PTS_OFFSET_90K, pcr = pts, randomAccess = keyframe)
    }

    /** One raw AAC frame, as MediaCodec's AAC encoder emits it; the ADTS header is added here. */
    fun writeAudio(rawAac: ByteArray, ptsUs: Long, config: AacConfig) {
        val frame = config.adtsHeader(rawAac.size) + rawAac
        val pts = to90k(ptsUs)
        // With no video, audio carries the PCR.
        writePes(PID_AUDIO, STREAM_ID_AUDIO, frame, pts + PTS_OFFSET_90K, pcr = if (hasVideo) null else pts, randomAccess = true)
    }

    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, pts90k: Long, pcr: Long?, randomAccess: Boolean) {
        val header = ByteArray(14)
        header[0] = 0; header[1] = 0; header[2] = 1
        header[3] = streamId.toByte()
        // Zero means "unbounded", which is allowed for video only; audio frames always fit.
        val pesLength = if (streamId == STREAM_ID_VIDEO || payload.size + 8 > 0xFFFF) 0 else payload.size + 8
        header[4] = (pesLength ushr 8).toByte()
        header[5] = pesLength.toByte()
        header[6] = 0x80.toByte() // marker bits '10', no scrambling, not aligned
        header[7] = 0x80.toByte() // PTS only
        header[8] = 5
        writeTimestamp(header, 9, 0x2, pts90k)

        val pes = header + payload
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
            packet[0] = SYNC
            packet[1] = ((if (first) 0x40 else 0) or (pid ushr 8)).toByte()
            packet[2] = pid.toByte()

            // Adaptation field: the PCR and random-access flag on the first packet,
            // stuffing on the last when the payload runs short.
            val adaptation = ByteArrayOutputStream()
            if (first && (pcr != null || randomAccess)) {
                var flags = 0
                if (randomAccess) flags = flags or 0x40
                if (pcr != null) flags = flags or 0x10
                adaptation.write(flags)
                if (pcr != null) adaptation.write(pcrBytes(pcr))
            }
            val remaining = pes.size - offset
            var fieldLength = if (adaptation.size() > 0) adaptation.size() else -1 // -1: no adaptation field
            val room = PACKET_SIZE - 4 - (if (fieldLength >= 0) 1 + fieldLength else 0)
            if (remaining < room) {
                // Stuff the adaptation field so the payload ends exactly at the packet's end.
                val stuffing = room - remaining
                if (fieldLength < 0) {
                    // An adaptation field of length 0 is one byte; a longer one needs the flags byte first.
                    fieldLength = if (stuffing == 1) 0 else { adaptation.write(0); 1 }
                    val extra = stuffing - (if (fieldLength == 0) 1 else 2)
                    repeat(extra) { adaptation.write(0xFF) }
                    fieldLength += extra
                } else {
                    repeat(stuffing) { adaptation.write(0xFF) }
                    fieldLength += stuffing
                }
            }
            val hasAdaptation = fieldLength >= 0
            packet[3] = ((if (hasAdaptation) 0x30 else 0x10) or nextContinuity(pid)).toByte()
            var position = 4
            if (hasAdaptation) {
                packet[position++] = fieldLength.toByte()
                val bytes = adaptation.toByteArray()
                System.arraycopy(bytes, 0, packet, position, bytes.size)
                position += bytes.size
            }
            val chunk = minOf(PACKET_SIZE - position, remaining)
            System.arraycopy(pes, offset, packet, position, chunk)
            offset += chunk
            out.write(packet)
            first = false
        }
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = SYNC
        packet[1] = (0x40 or (pid ushr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or nextContinuity(pid)).toByte()
        packet[4] = 0 // pointer field
        System.arraycopy(section, 0, packet, 5, section.size)
        out.write(packet)
    }

    private fun patSection(): ByteArray = section(
        tableId = 0x00,
        tableIdExtension = 1, // transport_stream_id
        body = byteArrayOf(0x00, 0x01, (0xE0 or (PID_PMT ushr 8)).toByte(), PID_PMT.toByte()),
    )

    private fun pmtSection(): ByteArray {
        val pcrPid = if (hasVideo) PID_VIDEO else PID_AUDIO
        val body = ByteArrayOutputStream()
        body.write(0xE0 or (pcrPid ushr 8)); body.write(pcrPid)
        body.write(0xF0); body.write(0x00) // no program descriptors
        if (hasVideo) {
            body.write(STREAM_TYPE_H264)
            body.write(0xE0 or (PID_VIDEO ushr 8)); body.write(PID_VIDEO)
            body.write(0xF0); body.write(0x00)
        }
        if (hasAudio) {
            body.write(STREAM_TYPE_AAC_ADTS)
            body.write(0xE0 or (PID_AUDIO ushr 8)); body.write(PID_AUDIO)
            body.write(0xF0); body.write(0x00)
        }
        return section(tableId = 0x02, tableIdExtension = 1, body = body.toByteArray()) // program_number 1
    }

    /** A long-form PSI section with its CRC. */
    private fun section(tableId: Int, tableIdExtension: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4 // after section_length: 5 header bytes, body, CRC
        val section = ByteArrayOutputStream()
        section.write(tableId)
        section.write(0xB0 or (length ushr 8)) // syntax indicator, '0', reserved
        section.write(length)
        section.write(tableIdExtension ushr 8); section.write(tableIdExtension)
        section.write(0xC1) // reserved, version 0, current
        section.write(0x00); section.write(0x00) // section 0 of 0
        section.write(body)
        val crc = crc32Mpeg(section.toByteArray())
        section.write(crc ushr 24); section.write(crc ushr 16); section.write(crc ushr 8); section.write(crc)
        return section.toByteArray()
    }

    private fun nextContinuity(pid: Int): Int {
        val value = continuity[pid]
        continuity[pid] = (value + 1) and 0xF
        return value
    }

    companion object {
        const val PACKET_SIZE = 188
        private const val SYNC: Byte = 0x47

        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101

        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private const val STREAM_ID_VIDEO = 0xE0
        private const val STREAM_ID_AUDIO = 0xC0

        /** 0.7 s: the gap between PCR and PTS, i.e. the decoder's buffer. */
        const val PTS_OFFSET_90K = 63_000L

        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        /** An access-unit delimiter: primary_pic_type 7 (any slice type). */
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        fun to90k(us: Long): Long = us * 9 / 100

        private fun writeTimestamp(into: ByteArray, at: Int, prefix: Int, value90k: Long) {
            val v = value90k and 0x1FFFFFFFFL
            into[at] = ((prefix shl 4) or ((v ushr 29).toInt() and 0x0E) or 1).toByte()
            into[at + 1] = (v ushr 22).toByte()
            into[at + 2] = (((v ushr 14).toInt() and 0xFE) or 1).toByte()
            into[at + 3] = (v ushr 7).toByte()
            into[at + 4] = (((v shl 1).toInt() and 0xFE) or 1).toByte()
        }

        /** 33-bit base, 6 reserved bits, 9-bit extension (always 0 here). */
        private fun pcrBytes(pcr90k: Long): ByteArray {
            val base = pcr90k and 0x1FFFFFFFFL
            return byteArrayOf(
                (base ushr 25).toByte(),
                (base ushr 17).toByte(),
                (base ushr 9).toByte(),
                (base ushr 1).toByte(),
                (((base and 1).toInt() shl 7) or 0x7E).toByte(),
                0x00,
            )
        }

        /** CRC-32/MPEG-2: polynomial 0x04C11DB7, no reflection, no final XOR. */
        fun crc32Mpeg(data: ByteArray): Int {
            var crc = -1
            data.forEach { byte ->
                crc = crc xor ((byte.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}

/** What an ADTS header needs to know about an AAC stream. */
data class AacConfig(val sampleRate: Int, val channels: Int, val objectType: Int = AAC_LC) {
    init {
        require(sampleRate in SAMPLE_RATES) { "AAC has no index for $sampleRate Hz" }
        require(channels in 1..7) { "unsupported channel count $channels" }
    }

    private val rateIndex: Int get() = SAMPLE_RATES.indexOf(sampleRate)

    fun adtsHeader(rawLength: Int): ByteArray {
        val length = rawLength + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            (((objectType - 1) shl 6) or (rateIndex shl 2) or (channels ushr 2)).toByte(),
            (((channels and 3) shl 6) or (length ushr 11)).toByte(),
            (length ushr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(), // buffer fullness 0x7FF (VBR), one raw data block
        )
    }

    companion object {
        const val AAC_LC = 2

        /** The ADTS sampling_frequency_index table, in index order. */
        val SAMPLE_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
