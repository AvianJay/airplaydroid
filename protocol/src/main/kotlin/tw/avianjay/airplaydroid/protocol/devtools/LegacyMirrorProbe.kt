package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.mirror.LegacyMirrorSessionFactory
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession
import tw.avianjay.airplaydroid.protocol.mirror.VideoStreamSink
import java.io.File
import java.io.IOException

/**
 * Drives a legacy (non-HAP) mirroring session from a desktop JVM, streaming an
 * H.264 file:
 *
 *   LegacyMirrorProbe <host> <port> <annexb.h264> [password|-]
 *
 * The same code path the app uses for a legacy receiver, minus the capture. The
 * file must be Annex B with an AUD before every access unit; see [MirrorProbe].
 *
 * System properties:
 *  - `mirror.holdSeconds=N` keeps the session open N seconds after the last
 *    frame (default 3), sending only heartbeats and /feedback.
 *  - `mirror.loops=N` plays the file N times (default 1).
 *  - `mirror.key=auto|raw|mixed` picks the video key seed (default auto); see
 *    [LegacyRtspMirrorSession.KeySeed].
 *  - `mirror.path=7100` uses the iOS 6-8 port-7100 path
 *    ([LegacyMirrorSessionFactory.open]) instead of RTSP type 110;
 *    `mirror.streamPort=N` moves it off 7100.
 */
object LegacyMirrorProbe {

    private const val FPS = 30
    private val started = System.currentTimeMillis()
    private fun t() = "[%6.2fs]".format((System.currentTimeMillis() - started) / 1000.0)

    /** What the streaming loop needs from either path. */
    private class Probed(val sink: VideoStreamSink, val isOpen: () -> Boolean, val timingQueries: () -> Int, val close: () -> Unit)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 3..4) { "usage: LegacyMirrorProbe <host> <port> <annexb.h264> [password|-]" }
        val endpoint = Endpoint(args[0], args[1].toInt())
        val units = MirrorProbe.accessUnits(File(args[2]).readBytes())
        val password = args.getOrNull(3)?.takeUnless { it == "-" }
        val loops = System.getProperty("mirror.loops")?.toIntOrNull() ?: 1

        var endedBy: String? = null
        val probed = if (System.getProperty("mirror.path") == "7100") {
            val streamPort = System.getProperty("mirror.streamPort")?.toIntOrNull() ?: 7100
            println("${t()} opening a port-$streamPort legacy session to ${endpoint.host} (FairPlay on ${endpoint.port})")
            val opened = LegacyMirrorSessionFactory.open(endpoint.host, endpoint.port, password, streamPort)
            println("${t()} session open; display=${opened.display}")
            // No control channel to report an end: a failed write is the signal.
            var open = true
            val sink = object : VideoStreamSink {
                override fun setCodecConfig(avcC: ByteArray, width: Int, height: Int) =
                    guard { opened.video.setCodecConfig(avcC, width, height) }

                override fun sendFrame(avcc: ByteArray, keyframe: Boolean, captureNanos: Long) =
                    guard { opened.video.sendFrame(avcc, keyframe, captureNanos) }

                private fun guard(write: () -> Unit) {
                    try {
                        write()
                    } catch (e: IOException) {
                        if (open) endedBy = "write failed: ${e.message}"
                        open = false
                    }
                }
            }
            Probed(sink, { open }, { opened.timingQueries }, { opened.close() })
        } else {
            println("${t()} opening a legacy RTSP session to $endpoint")
            val session = LegacyRtspMirrorSession.open(
                endpoint, password, "AirPlayDroid probe",
                listener = { reason ->
                    endedBy = reason
                    println("${t()} !!! session ended by receiver: $reason")
                },
                trace = { println("${t()} $it") },
                keySeed = LegacyRtspMirrorSession.KeySeed.valueOf(System.getProperty("mirror.key", "auto").uppercase()),
                // Stable, so a receiver sees the same sender every run.
                deviceId = "02:AD:D5:0B:E0:01",
            )
            println("${t()} session open; display=${session.display} paired=${session.paired}")
            Probed(session, { session.isOpen }, { session.timingQueries }, { session.close() })
        }

        val begin = System.nanoTime()
        var configSent: ByteArray? = null
        var index = 0
        outer@ for (loop in 0 until loops) {
            for (unit in units) {
                if (!probed.isOpen()) break@outer
                val due = begin + index * 1_000_000_000L / FPS
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                unit.avcC?.let { avcC ->
                    if (!avcC.contentEquals(configSent)) {
                        probed.sink.setCodecConfig(avcC, 1280, 720)
                        configSent = avcC
                    }
                }
                probed.sink.sendFrame(unit.avcc, unit.idr, captureNanos = due)
                if (index % (FPS * 5) == 0) println("${t()} frame $index; timing queries so far: ${probed.timingQueries()}")
                index++
            }
        }

        val hold = System.getProperty("mirror.holdSeconds")?.toLongOrNull() ?: 3L
        println("${t()} done; holding $hold s")
        for (second in 1..hold) {
            if (!probed.isOpen()) break
            Thread.sleep(1_000)
        }
        val survived = probed.isOpen()
        val queries = probed.timingQueries()
        probed.close()
        println(if (survived) "RESULT: receiver accepted the whole stream" else "RESULT: receiver ended it: $endedBy")
        println("RESULT: timing queries answered = $queries")
    }
}
