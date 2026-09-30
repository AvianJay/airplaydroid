package tw.avianjay.airplaydroid.protocol.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transport stream's structure, packet by packet.
 *
 * A player is far less forgiving of a malformed segment than of a malformed
 * playlist -- it drops the segment silently -- so these check the fields a
 * demuxer reads first: sync bytes, continuity counters, the PSI CRC, the PES
 * start and its PTS. The whole output was also checked with ffprobe; see
 * `HlsMuxProbe`.
 */
class MpegTsTest {

    private fun packets(bytes: ByteArray): List<ByteArray> {
        assertEquals(0, bytes.size % MpegTsWriter.PACKET_SIZE)
        return bytes.toList().chunked(MpegTsWriter.PACKET_SIZE).map { it.toByteArray() }
    }

    private fun pid(packet: ByteArray) = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)

    private fun payloadStart(packet: ByteArray): Int {
        val control = (packet[3].toInt() ushr 4) and 3
        return if (control == 3) 5 + (packet[4].toInt() and 0xFF) else 4
    }

    @Test
    fun crcMatchesTheMpeg2Check() {
        // The CRC-32/MPEG-2 check value for "123456789".
        assertEquals(0x0376E6E7, MpegTsWriter.crc32Mpeg("123456789".toByteArray()))
    }

    @Test
    fun tablesAreTwoPacketsWithValidCrcs() {
        val writer = MpegTsWriter(hasVideo = true, hasAudio = true)
        writer.writeTables()
        val (pat, pmt) = packets(writer.take())
        assertEquals(MpegTsWriter.PID_PAT, pid(pat))
        assertEquals(MpegTsWriter.PID_PMT, pid(pmt))
        listOf(pat, pmt).forEach { packet ->
            assertEquals(0x47, packet[0].toInt())
            val sectionLength = ((packet[6].toInt() and 0x0F) shl 8) or (packet[7].toInt() and 0xFF)
            // A section whose own CRC is included checks to zero.
            val section = packet.copyOfRange(5, 5 + 3 + sectionLength)
            assertEquals(0, MpegTsWriter.crc32Mpeg(section))
        }
        // The PMT lists H.264 (0x1B) on 0x100 and AAC (0x0F) on 0x101.
        assertEquals(0x1B, pmt[17].toInt() and 0xFF)
        assertEquals(0x0F, pmt[22].toInt() and 0xFF)
    }

    @Test
    fun aLargeAccessUnitSpansPacketsWithContinuousCounters() {
        val writer = MpegTsWriter(hasVideo = true, hasAudio = false)
        val frame = ByteArray(5000) { (it % 251).toByte() }
        writer.writeVideo(byteArrayOf(0, 0, 0, 1, 0x65) + frame, ptsUs = 1_000_000, keyframe = true,
            sps = byteArrayOf(0x67, 1, 2, 3), pps = byteArrayOf(0x68, 4))
        writer.writeVideo(byteArrayOf(0, 0, 0, 1, 0x41) + frame, ptsUs = 1_033_333, keyframe = false, sps = null, pps = null)
        val all = packets(writer.take())
        assertTrue(all.all { it[0].toInt() == 0x47 && pid(it) == MpegTsWriter.PID_VIDEO })
        all.forEachIndexed { index, packet -> assertEquals(index and 0xF, packet[3].toInt() and 0xF) }

        // Exactly two packets start a PES, and the first carries a PCR and the random-access flag.
        val starts = all.filter { it[1].toInt() and 0x40 != 0 }
        assertEquals(2, starts.size)
        val first = starts[0]
        assertEquals(3, (first[3].toInt() ushr 4) and 3)
        assertEquals(0x50, first[5].toInt() and 0xFF) // random access + PCR

        // The PES start code, and PTS = 1 s + the offset, in 90 kHz.
        val pes = payloadStart(first)
        assertContentEquals(byteArrayOf(0, 0, 1, 0xE0.toByte()), first.copyOfRange(pes, pes + 4))
        val p = first.copyOfRange(pes + 9, pes + 14).map { it.toLong() and 0xFF }
        val pts = ((p[0] shr 1) and 7 shl 30) or (p[1] shl 22) or ((p[2] shr 1) shl 15) or (p[3] shl 7) or (p[4] shr 1)
        assertEquals(90_000L + MpegTsWriter.PTS_OFFSET_90K, pts)

        // AUD, then SPS, PPS, then the slice.
        val es = first.copyOfRange(pes + 14, pes + 30)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte(), 0, 0, 0, 1, 0x67, 1, 2, 3, 0, 0), es)
    }

    @Test
    fun anAudioFrameGetsAnAdtsHeaderAndABoundedPesLength() {
        val writer = MpegTsWriter(hasVideo = false, hasAudio = true)
        val config = AacConfig(sampleRate = 44100, channels = 2)
        writer.writeAudio(ByteArray(300) { 7 }, ptsUs = 0, config = config)
        val all = packets(writer.take())
        assertEquals(2, all.size)
        val first = all[0]
        val pes = payloadStart(first)
        val pesLength = ((first[pes + 4].toInt() and 0xFF) shl 8) or (first[pes + 5].toInt() and 0xFF)
        assertEquals(300 + 7 + 8, pesLength)
        // ADTS: sync, then AAC-LC (profile 1), 44.1 kHz (index 4), two channels.
        val adts = first.copyOfRange(pes + 14, pes + 21)
        assertEquals(0xFF, adts[0].toInt() and 0xFF)
        assertEquals(0xF1, adts[1].toInt() and 0xFF)
        assertEquals((1 shl 6) or (4 shl 2), adts[2].toInt() and 0xFF)
        val frameLength = ((adts[3].toInt() and 3) shl 11) or ((adts[4].toInt() and 0xFF) shl 3) or ((adts[5].toInt() and 0xFF) ushr 5)
        assertEquals(307, frameLength)
        // With no video, audio carries the PCR.
        assertEquals(0x50, first[5].toInt() and 0xFF)
    }

    @Test
    fun theLastPacketIsStuffedToExactlyFit() {
        // Every payload length from one byte to three packets' worth must come out whole.
        for (size in listOf(1, 150, 169, 170, 171, 182, 183, 184, 185, 400)) {
            val writer = MpegTsWriter(hasVideo = true, hasAudio = false)
            writer.writeVideo(ByteArray(size), ptsUs = 0, keyframe = false, sps = null, pps = null)
            val bytes = writer.take()
            assertEquals(0, bytes.size % MpegTsWriter.PACKET_SIZE, "size $size")
            // Reassembled, the payload is the PES header (14) + AUD (6) + the data.
            val payload = packets(bytes).sumOf { MpegTsWriter.PACKET_SIZE - payloadStart(it) }
            assertEquals(14 + 6 + size, payload, "size $size")
        }
    }
}
