package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.mirror.H264
import tw.avianjay.airplaydroid.protocol.mirror.MirrorSession
import java.io.File

/**
 * Drives [MirrorSession] from a desktop JVM, streaming an H.264 file:
 *
 *   MirrorProbe <host> <port> <credentials-file> <password> <annexb.h264>
 *
 * The same code path the app uses, minus the screen capture, so a protocol
 * change can be checked against real hardware without a phone. The file must
 * be Annex B with an AUD before every access unit, e.g.
 *
 *   ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 20 -c:v libx264
 *     -profile:v baseline -g 30 -bf 0 -x264-params aud=1:repeat-headers=1 -f h264 out.h264
 *
 * A receiver that cannot authenticate a frame drops the data channel within
 * milliseconds, so "ended by receiver" before the file is done means failure.
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

        var endedBy: String? = null
        val session = MirrorSession.open(endpoint, credentials, args[3], "AirPlayDroid probe") { reason ->
            endedBy = reason
            println("${t()} !!! session ended by receiver: $reason")
        }
        println("${t()} session open, streaming ${units.size} access units")

        val begin = System.nanoTime()
        var codecSent: ByteArray? = null
        for ((index, unit) in units.withIndex()) {
            if (!session.isOpen) break
            val wait = begin + index * 1_000_000_000L / FPS - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            unit.avcC?.let { avcC ->
                if (!avcC.contentEquals(codecSent)) {
                    session.sendCodecConfig(avcC, 1280, 720)
                    codecSent = avcC
                }
            }
            session.sendFrame(unit.avcc, unit.idr)
            if (index % (FPS * 5) == 0) println("${t()} frame $index")
        }

        println("${t()} done; holding 3 s")
        Thread.sleep(3_000)
        val survived = session.isOpen
        session.close()
        println(if (survived) "RESULT: receiver accepted the whole stream" else "RESULT: receiver ended it: $endedBy")
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
