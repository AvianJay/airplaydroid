package tw.avianjay.airplaydroid.protocol.media

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsStreamTest {

    private val sps = byteArrayOf(0x67, 0x42, 0, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val frame = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)

    /** 30 fps video with a keyframe every second. */
    private fun HlsStream.feed(seconds: Int, fromFrame: Int = 0) {
        for (i in fromFrame until fromFrame + seconds * 30) {
            writeVideo(frame, ptsUs = i * 1_000_000L / 30, keyframe = i % 30 == 0)
        }
    }

    @Test
    fun cutsAtKeyframesAndMeasuresDurations() {
        val stream = HlsStream(hasVideo = true, hasAudio = false, targetSeconds = 2.0, maxAhead = 100)
        stream.setVideoConfig(sps, pps)
        stream.feed(seconds = 7)
        stream.finish()

        val playlist = assertNotNull(stream.playlist(1000))
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue("#EXT-X-START:TIME-OFFSET=0" in playlist)
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
        // Keyframes every second, target 2 s: segments of 2, 2, 2, then the tail.
        val durations = Regex("#EXTINF:([0-9.]+),").findAll(playlist).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(listOf(2.0, 2.0, 2.0), durations.take(3))
        assertEquals(4, durations.size)

        // Every segment starts with a PAT, so it decodes on its own.
        val first = assertNotNull(stream.segment(0, 100))
        assertEquals(0x47, first.bytes[0].toInt())
        assertEquals(0, ((first.bytes[1].toInt() and 0x1F) shl 8) or (first.bytes[2].toInt() and 0xFF))
    }

    @Test
    fun framesBeforeTheFirstKeyframeAreDropped() {
        val stream = HlsStream(hasVideo = true, hasAudio = true, targetSeconds = 1.0, maxAhead = 100)
        stream.writeAudio(ByteArray(10), 0, AacConfig(48000, 2))
        stream.writeVideo(frame, 0, keyframe = false)
        stream.finish()
        assertNull(stream.playlist(50))
    }

    @Test
    fun theProducerWaitsForThePlayer() {
        val stream = HlsStream(hasVideo = true, hasAudio = false, targetSeconds = 1.0, maxAhead = 2, keepBehind = 1)
        var produced = 0
        val producer = thread {
            for (i in 0 until 30 * 20) {
                if (!stream.writeVideo(frame, i * 1_000_000L / 30, keyframe = i % 30 == 0)) break
                produced = i
            }
        }
        Thread.sleep(300)
        // Nothing requested: at most maxAhead + 1 segments' worth has been made.
        assertTrue(produced < 30 * 4, "produced $produced frames without a reader")
        assertTrue(producer.isAlive)

        // Fetching moves the window: segment 5 becomes reachable, and old ones go.
        assertNotNull(stream.segment(1, 2000))
        assertNotNull(stream.segment(3, 2000))
        assertNotNull(stream.segment(5, 2000))
        assertNull(stream.segment(0, 10), "evicted")
        val playlist = assertNotNull(stream.playlist(100))
        assertFalse("#EXT-X-START" in playlist, "the window has slid past the start")

        stream.close()
        producer.join(2000)
        assertFalse(producer.isAlive, "close() must release the producer")
    }

    @Test
    fun aPlaylistRequestWaitsForTheFirstSegment() {
        val stream = HlsStream(hasVideo = false, hasAudio = true, targetSeconds = 1.0)
        val config = AacConfig(44100, 2)
        thread {
            Thread.sleep(100)
            // 1024 samples per AAC frame.
            for (i in 0 until 100) stream.writeAudio(ByteArray(50), i * 1024L * 1_000_000 / 44100, config)
        }
        assertNotNull(stream.playlist(5000))
        stream.close()
    }
}
