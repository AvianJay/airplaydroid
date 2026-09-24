package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.mirror.AlacVerbatim
import tw.avianjay.airplaydroid.protocol.mirror.H264
import tw.avianjay.airplaydroid.protocol.mirror.MirrorSession
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives [MirrorSession] from a desktop JVM, streaming an H.264 file and a
 * 440 Hz test tone:
 *
 *   MirrorProbe <host> <port> <credentials-file> <password> <annexb.h264>
 *
 * The same code path the app uses, minus the capture, so a protocol change can
 * be checked against real hardware without a phone. The file must be Annex B
 * with an AUD before every access unit, e.g.
 *
 *   ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 20 -c:v libx264
 *     -profile:v baseline -g 30 -bf 0 -x264-params aud=1:repeat-headers=1 -f h264 out.h264
 *
 * System properties:
 *  - `mirror.audio=false` streams video only.
 *  - `mirror.features=0x...` the receiver's feature bits (default: the test Apple TV's).
 *  - `mirror.dropSeq=N` withholds the N-th audio packet, to see whether the
 *    receiver asks for it again -- evidence it is parsing the audio stream.
 *  - `mirror.holdSeconds=N` keeps the session open N seconds after the last
 *    frame (default 3), sending only heartbeats and /feedback -- what a phone
 *    with a static screen does when its encoder emits nothing.
 *
 * A receiver that cannot authenticate a video frame drops the data channel
 * within milliseconds, so "ended by receiver" before the file is done means failure.
 */
object MirrorProbe {

    private const val FPS = 30
    private val started = System.currentTimeMillis()
    private fun t() = "[%6.2fs]".format((System.currentTimeMillis() - started) / 1000.0)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "usage: MirrorProbe <host> <port> <credentials-file> <password> <annexb.h264>" }
        val endpoint = Endpoint(args[0], args[1].toInt())
        val credentials = PairProbe.readCredentials(File(args[2]))
        val units = accessUnits(File(args[4]).readBytes())
        val withAudio = System.getProperty("mirror.audio") != "false"
        val features = System.getProperty("mirror.features", "0x3C177FDE4A7FDFD5").removePrefix("0x").toULong(16)
        val dropSeq = System.getProperty("mirror.dropSeq")?.toIntOrNull()

        var endedBy: String? = null
        val session = MirrorSession.open(
            endpoint, credentials, args[3], "AirPlayDroid probe", withAudio = withAudio, features = features,
        ) { reason ->
            endedBy = reason
            println("${t()} !!! session ended by receiver: $reason")
        }
        println("${t()} session open; display=${session.receiverDisplay} audio=${session.hasAudio}" +
            (session.audioFailure?.let { " (audio failed: $it)" } ?: ""))

        val begin = System.nanoTime()
        val tone = if (session.hasAudio) thread(name = "probe-tone") { tone(session, begin, units.size * 1_000_000_000L / FPS, dropSeq) } else null

        var configSent: ByteArray? = null
        for ((index, unit) in units.withIndex()) {
            if (!session.isOpen) break
            val due = begin + index * 1_000_000_000L / FPS
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            unit.avcC?.let { avcC ->
                if (!avcC.contentEquals(configSent)) {
                    session.setCodecConfig(avcC, 1280, 720)
                    configSent = avcC
                }
            }
            session.sendFrame(unit.avcc, unit.idr, captureNanos = due)
            if (index % (FPS * 5) == 0) println("${t()} frame $index")
        }
        tone?.join()

        val hold = System.getProperty("mirror.holdSeconds")?.toLongOrNull() ?: 3L
        println("${t()} done; holding $hold s; audio retransmit requests so far: ${session.audioRetransmitRequests}")
        for (second in 1..hold) {
            if (!session.isOpen) break
            Thread.sleep(1_000)
        }
        val survived = session.isOpen
        val retransmits = session.audioRetransmitRequests
        session.close()
        println(if (survived) "RESULT: receiver accepted the whole stream" else "RESULT: receiver ended it: $endedBy")
        if (session.hasAudio) println("RESULT: audio retransmit requests = $retransmits")
    }

    /** A 440 Hz sine, stereo, paced in real time, stamped as if captured on schedule. */
    private fun tone(session: MirrorSession, begin: Long, durationNanos: Long, dropSeq: Int?) {
        val spf = AlacVerbatim.SAMPLES_PER_FRAME
        val pcm = ShortArray(spf * 2)
        var sample = 0L
        var packet = 0
        while (session.isOpen) {
            val due = begin + sample * 1_000_000_000L / 44_100
            if (due - begin > durationNanos) break
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            for (i in 0 until spf) {
                val v = (sin(2 * PI * 440 * (sample + i) / 44_100) * 8_000).toInt().toShort()
                pcm[2 * i] = v
                pcm[2 * i + 1] = v
            }
            packet++
            if (packet == dropSeq) {
                println("${t()} withholding audio packet $packet")
                session.withholdAudio(pcm, due)
            } else {
                session.sendAudio(pcm, due)
            }
            sample += spf
        }
    }

    private class AccessUnit(val avcc: ByteArray, val idr: Boolean, val avcC: ByteArray?)

    /** Groups NAL units into access units at each AUD, lifting SPS/PPS into avcC. */
    private fun accessUnits(stream: ByteArray): List<AccessUnit> {
        val units = mutableListOf<AccessUnit>()
        var current = mutableListOf<ByteArray>()
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        fun flush() {
            val vcl = current.filter { H264.type(it) in H264.NAL_SLICE..H264.NAL_IDR }
            if (vcl.isNotEmpty()) {
                val idr = vcl.any { H264.type(it) == H264.NAL_IDR }
                val s = sps
                val p = pps
                units += AccessUnit(H264.toAvcc(vcl), idr, if (idr && s != null && p != null) H264.avcC(s, p) else null)
            }
            current = mutableListOf()
        }
        for (nal in H264.splitAnnexB(stream)) {
            when (H264.type(nal)) {
                H264.NAL_AUD -> flush()
                H264.NAL_SPS -> sps = nal
                H264.NAL_PPS -> pps = nal
                else -> current += nal
            }
        }
        flush()
        return units
    }
}
