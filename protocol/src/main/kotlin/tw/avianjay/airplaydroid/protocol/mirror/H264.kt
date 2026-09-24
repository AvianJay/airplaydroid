package tw.avianjay.airplaydroid.protocol.mirror

import java.io.ByteArrayOutputStream

/**
 * The byte-format conversions between what an encoder emits and what the
 * mirroring data channel carries.
 *
 * Encoders (MediaCodec, ffmpeg `-f h264`) produce **Annex B**: NAL units
 * separated by `00 00 01` / `00 00 00 01` start codes, with SPS and PPS inline.
 * The data channel wants **AVCC** frames (each NAL prefixed by a 4-byte
 * big-endian length) and the SPS/PPS separately, as an ISO 14496-15 `avcC`
 * record in a codec packet.
 */
object H264 {

    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun type(nal: ByteArray): Int = nal[0].toInt() and 0x1f

    /** Splits Annex B into NAL units, without start codes or trailing zero padding. */
    fun splitAnnexB(stream: ByteArray, offset: Int = 0, length: Int = stream.size - offset): List<ByteArray> {
        val end = offset + length
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = offset
        while (i + 3 <= end) {
            if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() && stream[i + 2] == 1.toByte()) {
                if (start >= 0) addTrimmed(nals, stream, start, i)
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0) addTrimmed(nals, stream, start, end)
        return nals
    }

    private fun addTrimmed(into: MutableList<ByteArray>, stream: ByteArray, start: Int, endExclusive: Int) {
        var end = endExclusive
        // The zero that belongs to a following 4-byte start code, or cabac_zero_words.
        while (end > start && stream[end - 1] == 0.toByte()) end--
        if (end > start) into += stream.copyOfRange(start, end)
    }

    /** Length-prefixes each NAL unit with a 4-byte big-endian size. */
    fun toAvcc(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        nals.forEach { nal ->
            out.write(nal.size ushr 24)
            out.write(nal.size ushr 16)
            out.write(nal.size ushr 8)
            out.write(nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** An `avcC` record carrying one SPS and one PPS, with 4-byte NAL lengths. */
    fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4 && type(sps) == NAL_SPS) { "not an SPS" }
        require(type(pps) == NAL_PPS) { "not a PPS" }
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(sps[1].toInt()) // profile_idc
        out.write(sps[2].toInt()) // constraint flags
        out.write(sps[3].toInt()) // level_idc
        out.write(0xFF) // 6 reserved bits + lengthSizeMinusOne = 3
        out.write(0xE1) // 3 reserved bits + one SPS
        out.write(sps.size ushr 8)
        out.write(sps.size)
        out.write(sps)
        out.write(1) // one PPS
        out.write(pps.size ushr 8)
        out.write(pps.size)
        out.write(pps)
        return out.toByteArray()
    }
}
