package tw.avianjay.airplaydroid.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import tw.avianjay.airplaydroid.protocol.mirror.H264
import tw.avianjay.airplaydroid.protocol.mirror.MirrorSession
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Screen -> H.264 -> [MirrorSession].
 *
 * A VirtualDisplay renders the screen straight into the encoder's input
 * surface, so no pixel ever passes through Java. The drain thread converts
 * MediaCodec's Annex B output into the avcC codec packet and AVCC frames the
 * data channel carries.
 *
 * The picture size is fixed for the session. Rotating the phone makes the
 * VirtualDisplay letterbox the rotated content inside the original frame
 * rather than renegotiating; handling rotation properly is future work.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val session: MirrorSession,
    val width: Int,
    val height: Int,
    private val densityDpi: Int,
    private val onFailure: (String) -> Unit,
) {
    private lateinit var codec: MediaCodec
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false

    fun start() {
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format(withBaselineProfile = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject an explicit profile; their default is fine.
            Log.w(TAG, "encoder rejected the baseline profile, using its default", e)
            codec.reset()
            codec.configure(format(withBaselineProfile = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true

        display = projection.createVirtualDisplay(
            "AirPlayDroid-mirror",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )
        thread(name = "mirror-encoder-drain") { drain() }
    }

    private fun format(withBaselineProfile: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // A still screen produces no new frames; re-emit the last one so the
            // receiver keeps getting fresh timestamps.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (withBaselineProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
        }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var sentConfig: ByteArray? = null
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 100_000L)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                val bytes = if (buffer != null && info.size > 0) {
                    ByteArray(info.size).also {
                        buffer.position(info.offset)
                        buffer.get(it)
                    }
                } else {
                    null
                }
                codec.releaseOutputBuffer(index, false)
                if (bytes == null) continue

                val nals = H264.splitAnnexB(bytes)
                nals.forEach {
                    when (H264.type(it)) {
                        H264.NAL_SPS -> sps = it
                        H264.NAL_PPS -> pps = it
                    }
                }
                val s = sps
                val p = pps
                if (s != null && p != null) {
                    val avcC = H264.avcC(s, p)
                    if (!avcC.contentEquals(sentConfig)) {
                        session.sendCodecConfig(avcC, width, height)
                        sentConfig = avcC
                    }
                }

                val vcl = nals.filter { H264.type(it) in H264.NAL_SLICE..H264.NAL_IDR }
                if (vcl.isEmpty()) continue
                if (sentConfig == null) {
                    // Nothing decodable without SPS/PPS; ask for a fresh keyframe.
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    continue
                }
                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                    vcl.any { H264.type(it) == H264.NAL_IDR }
                session.sendFrame(H264.toAvcc(vcl), keyframe)
            }
        } catch (e: IOException) {
            if (running) onFailure("Lost the video connection: ${e.message}")
        } catch (e: IllegalStateException) {
            // The codec was stopped under us by stop(); only report if unexpected.
            if (running) onFailure("The video encoder stopped: ${e.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { display?.release() }
        runCatching { codec.signalEndOfInputStream() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface?.release() }
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val BIT_RATE = 6_000_000
        private const val FRAME_RATE = 30

        /**
         * The encoded size for a screen of [screenWidth] x [screenHeight]: the
         * same aspect ratio, the longer side capped at [maxSide], both sides a
         * multiple of 16 so every hardware encoder accepts them.
         */
        fun encodedSize(screenWidth: Int, screenHeight: Int, maxSide: Int = 1280): Pair<Int, Int> {
            val scale = minOf(1.0, maxSide.toDouble() / maxOf(screenWidth, screenHeight))
            fun align(v: Double) = maxOf(16, (v / 16).toInt() * 16)
            return align(screenWidth * scale) to align(screenHeight * scale)
        }
    }
}
