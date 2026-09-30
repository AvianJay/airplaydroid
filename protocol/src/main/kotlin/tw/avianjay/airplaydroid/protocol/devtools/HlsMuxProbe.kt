package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.AirPlayV1Session
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.cast.LocalAddresses
import tw.avianjay.airplaydroid.protocol.http.AirPlayEventChannel
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.media.AacConfig
import tw.avianjay.airplaydroid.protocol.media.HlsStream
import tw.avianjay.airplaydroid.protocol.media.MediaHttpServer
import tw.avianjay.airplaydroid.protocol.mirror.H264
import java.io.File
import kotlin.concurrent.thread

/**
 * The phone's conversion output, from files, served the way the app serves it.
 *
 *   HlsMuxProbe <video.h264> <fps> [audio.aac] [--play host:port]
 *
 * Feeds an Annex B H.264 file (and optionally an ADTS AAC file) through
 * [HlsStream] exactly as the app's converter does, serves it from a
 * [MediaHttpServer], and prints the playlist URL -- so the output can be checked
 * with `ffprobe` or `ffplay`, or, with `--play`, handed to an AirPlay receiver
 * with `POST /play` to see whether the receiver plays what the phone makes.
 */
object HlsMuxProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val video = File(args[0])
        val fps = args[1].toDouble()
        val audio = args.getOrNull(2)?.takeIf { !it.startsWith("--") }?.let(::File)
        val playTarget = args.indexOf("--play").takeIf { it >= 0 }?.let { args[it + 1] }

        val stream = HlsStream(hasVideo = true, hasAudio = audio != null)
        val server = MediaHttpServer(log = { println("HTTP $it") })
        val port = server.start()
        val path = server.publishHls(stream)

        thread(name = "feeder") { feed(stream, video, fps, audio) }

        val host = playTarget?.substringBefore(':')
        val local = host?.let { LocalAddresses.facing(it) }?.let(LocalAddresses::urlHost) ?: "127.0.0.1"
        val url = "http://$local:$port$path"
        println("playlist: $url")

        if (playTarget != null) {
            val endpoint = Endpoint(playTarget.substringBefore(':'), playTarget.substringAfter(':').toInt())
            // The order PlaybackController uses: the event channel, with this
            // session id, before any control request.
            val sid = java.util.UUID.randomUUID().toString().uppercase()
            val events = AirPlayEventChannel.open(endpoint, sid) { line, _ -> println("EVENT $line") }
            println("event channel " + if (events != null) "open" else "declined")
            val session = AirPlayV1Session(SocketAirPlayConnection(endpoint), sid, eventChannel = events)
            session.play(url)
            session.rate(1.0)
            repeat(30) {
                Thread.sleep(2_000)
                println("playback-info: " + runCatching { session.playbackInfo() }.getOrElse { it.toString() })
            }
            runCatching { session.stop() }
            session.close()
            server.close()
        } else {
            Thread.currentThread().join()
        }
    }

    private fun feed(stream: HlsStream, video: File, fps: Double, audio: File?) {
        val nals = H264.splitAnnexB(video.readBytes())
        val sps = nals.first { H264.type(it) == H264.NAL_SPS }
        val pps = nals.first { H264.type(it) == H264.NAL_PPS }
        stream.setVideoConfig(sps, pps)

        // One access unit per picture: an IDR or non-IDR slice, with the NALs before it.
        val frames = ArrayList<Pair<ByteArray, Boolean>>()
        var pending = java.io.ByteArrayOutputStream()
        nals.forEach { nal ->
            when (H264.type(nal)) {
                H264.NAL_SPS, H264.NAL_PPS, H264.NAL_AUD -> Unit
                else -> {
                    pending.write(byteArrayOf(0, 0, 0, 1)); pending.write(nal)
                    if (H264.type(nal) == H264.NAL_IDR || H264.type(nal) == H264.NAL_SLICE) {
                        frames += pending.toByteArray() to (H264.type(nal) == H264.NAL_IDR)
                        pending = java.io.ByteArrayOutputStream()
                    }
                }
            }
        }
        val audioFrames = audio?.let { adtsFrames(it.readBytes()) }.orEmpty()
        val config = audioFrames.firstOrNull()?.first
        var audioIndex = 0
        frames.forEachIndexed { index, (frame, key) ->
            val pts = (index * 1_000_000 / fps).toLong()
            // Interleave: all audio up to this picture's time first.
            while (config != null && audioIndex < audioFrames.size &&
                audioIndex * 1024L * 1_000_000 / config.sampleRate <= pts
            ) {
                val (_, raw) = audioFrames[audioIndex]
                stream.writeAudio(raw, audioIndex * 1024L * 1_000_000 / config.sampleRate, config)
                audioIndex++
            }
            if (!stream.writeVideo(frame, pts, key)) return
        }
        stream.finish()
        println("fed ${frames.size} pictures and $audioIndex audio frames")
    }

    /** Splits ADTS into (config, raw frame) pairs. */
    private fun adtsFrames(bytes: ByteArray): List<Pair<AacConfig, ByteArray>> {
        val out = ArrayList<Pair<AacConfig, ByteArray>>()
        var i = 0
        while (i + 7 <= bytes.size) {
            if (bytes[i] != 0xFF.toByte() || (bytes[i + 1].toInt() and 0xF0) != 0xF0) { i++; continue }
            val protectionAbsent = bytes[i + 1].toInt() and 1
            val rateIndex = (bytes[i + 2].toInt() ushr 2) and 0xF
            val channels = ((bytes[i + 2].toInt() and 1) shl 2) or ((bytes[i + 3].toInt() ushr 6) and 3)
            val length = ((bytes[i + 3].toInt() and 3) shl 11) or ((bytes[i + 4].toInt() and 0xFF) shl 3) or
                ((bytes[i + 5].toInt() and 0xFF) ushr 5)
            val header = if (protectionAbsent == 1) 7 else 9
            if (length < header || i + length > bytes.size) break
            out += AacConfig(AacConfig.SAMPLE_RATES[rateIndex], channels) to bytes.copyOfRange(i + header, i + length)
            i += length
        }
        return out
    }
}
