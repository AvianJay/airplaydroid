package tw.avianjay.airplaydroid.cast

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import tw.avianjay.airplaydroid.protocol.media.AacConfig
import tw.avianjay.airplaydroid.protocol.media.HlsStream
import tw.avianjay.airplaydroid.protocol.mirror.H264
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.min

/**
 * Converts a URL AirPlay cannot play into H.264 + AAC, as a live [HlsStream].
 *
 *   MediaExtractor -> decoders -> (video: GL) -> H.264 / AAC encoders -> HlsStream
 *
 * Anything this phone can decode can be converted: WebM with VP8/VP9/AV1,
 * Matroska, Ogg Vorbis/Opus, FLAC, and so on. What it cannot open is adaptive
 * streaming (DASH), which [MediaExtractor] does not speak; that is refused
 * before a converter is made.
 *
 * One thread drives everything, polling each codec in turn. The stream's
 * back-pressure ([HlsStream.writeVideo] blocking) therefore pauses the whole
 * pipeline, which is what keeps a phone that converts faster than real time
 * from running minutes ahead of the receiver.
 *
 * Output timestamps start at zero at [startUs]: a converter made for a seek
 * produces a stream that begins where the seek landed, and the caller adds the
 * offset back when it reports the position.
 */
class MediaConverter(
    private val sourceUrl: String,
    private val startUs: Long,
    private val log: (String) -> Unit,
) {
    lateinit var stream: HlsStream
        private set

    /** The source's duration, when its container states one. */
    var durationSeconds: Double? = null
        private set

    private val extractor = MediaExtractor()
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    @Volatile private var stopped = false

    /**
     * Opens the source and picks the tracks this phone can decode. Blocking: it
     * reads the container's header over the network.
     */
    fun prepare(): HlsStream {
        extractor.setDataSource(sourceUrl, mapOf("User-Agent" to USER_AGENT))
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0 && canDecode(codecs, format, mime)) {
                videoTrack = i; videoFormat = format
            } else if (mime.startsWith("audio/") && audioTrack < 0 && canDecode(codecs, format, mime)) {
                audioTrack = i; audioFormat = format
            }
        }
        if (videoTrack < 0 && audioTrack < 0) {
            extractor.release()
            throw IOException("no track this phone can decode")
        }
        durationSeconds = listOfNotNull(videoFormat, audioFormat)
            .mapNotNull { runCatching { it.getLong(MediaFormat.KEY_DURATION) }.getOrNull() }
            .maxOrNull()?.takeIf { it > 0 }?.let { it / 1_000_000.0 }
        log("convert: video=${videoFormat?.getString(MediaFormat.KEY_MIME)} " +
            "audio=${audioFormat?.getString(MediaFormat.KEY_MIME)} duration=$durationSeconds")
        stream = HlsStream(hasVideo = videoTrack >= 0, hasAudio = audioTrack >= 0)
        return stream
    }

    /** Converts on a thread of its own. [onFailure] is not called for a [stop]. */
    fun start(onFailure: (String) -> Unit) {
        thread(name = "media-converter", isDaemon = true) {
            val pipeline = Pipeline()
            try {
                pipeline.run()
                if (!stopped) stream.finish()
                log("convert: finished (${pipeline.videoFrames} pictures)")
            } catch (t: Throwable) {
                if (!stopped) {
                    val reason = t.message ?: t.javaClass.simpleName
                    log("convert: failed: $t")
                    stream.fail(reason)
                    onFailure(reason)
                }
            } finally {
                pipeline.release()
                runCatching { extractor.release() }
            }
        }
    }

    fun stop() {
        stopped = true
        // Unblocks a producer waiting on back-pressure.
        if (::stream.isInitialized) stream.close()
    }

    private fun canDecode(codecs: MediaCodecList, format: MediaFormat, mime: String): Boolean =
        codecs.findDecoderForFormat(format) != null ||
            runCatching { MediaCodec.createDecoderByType(mime).release(); true }.getOrDefault(false)

    private fun createDecoder(format: MediaFormat): MediaCodec {
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        return if (name != null) MediaCodec.createByCodecName(name)
        else MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    }

    /** Everything that lives on the converter thread. */
    private inner class Pipeline {
        private val info = MediaCodec.BufferInfo()

        private var videoDecoder: MediaCodec? = null
        private var videoEncoder: MediaCodec? = null
        private var encoderInput: Surface? = null
        private var gl: GlFrameCopier? = null
        private var width = 0
        private var height = 0

        private var audioDecoder: MediaCodec? = null
        private var audioEncoder: MediaCodec? = null
        private var pcm: PcmConverter? = null
        private var aac: AacConfig? = null
        private val pending = ArrayDeque<ShortArray>()
        private var pendingOffset = 0
        private var audioBaseUs = -1L
        private var audioFramesQueued = 0L
        private var audioEosQueued = false

        private val eosSent = HashSet<Int>()
        private var inputDone = false
        private var videoDecoderDone = false
        private var videoDone = false
        private var audioDecoderDone = false
        private var audioDone = false

        var videoFrames = 0
            private set

        fun run() {
            videoFormat?.let(::setUpVideo)
            audioFormat?.let(::setUpAudioDecoder)
            videoDone = videoEncoder == null
            videoDecoderDone = videoDecoder == null
            audioDone = audioDecoder == null
            audioDecoderDone = audioDecoder == null

            if (videoTrack >= 0) extractor.selectTrack(videoTrack)
            if (audioTrack >= 0) extractor.selectTrack(audioTrack)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (!stopped && !(videoDone && audioDone)) {
                var progressed = false
                if (!inputDone) progressed = feedInput() || progressed
                if (!videoDecoderDone) progressed = drainVideoDecoder() || progressed
                if (!videoDone) progressed = drainVideoEncoder() || progressed
                if (!audioDecoderDone) progressed = drainAudioDecoder() || progressed
                progressed = feedAudioEncoder() || progressed
                if (!audioDone) progressed = drainAudioEncoder() || progressed
                // A track with no decodable output at all never makes an encoder.
                if (audioDecoderDone && audioEncoder == null) audioDone = true
                if (!progressed) Thread.sleep(IDLE_SLEEP_MS)
            }
        }

        // ------------------------------------------------------------- video

        private fun setUpVideo(source: MediaFormat) {
            val rotation = source.intOrNull(MediaFormat.KEY_ROTATION)?.let { ((it % 360) + 360) % 360 } ?: 0
            val sourceWidth = source.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = source.getInteger(MediaFormat.KEY_HEIGHT)
            val (uprightWidth, uprightHeight) =
                if (rotation % 180 != 0) sourceHeight to sourceWidth else sourceWidth to sourceHeight
            val fps = source.intOrNull(MediaFormat.KEY_FRAME_RATE)?.takeIf { it in 1..120 } ?: DEFAULT_FPS

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder = encoder
            val capabilities = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            val (w, h) = encoderSize(uprightWidth, uprightHeight) { cw, ch -> capabilities?.isSizeSupported(cw, ch) != false }
            width = w
            height = h
            try {
                encoder.configure(videoEncoderFormat(w, h, fps, baseline = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // As in ScreenEncoder: some encoders refuse an explicit profile.
                encoder.reset()
                encoder.configure(videoEncoderFormat(w, h, fps, baseline = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val input = encoder.createInputSurface()
            encoderInput = input
            val copier = GlFrameCopier(input, rotation)
            gl = copier
            encoder.start()

            val decoder = createDecoder(source)
            videoDecoder = decoder
            decoder.configure(source, copier.decoderSurface, null, 0)
            decoder.start()
            log("convert: video ${sourceWidth}x$sourceHeight rot $rotation -> ${w}x$h @ $fps fps via ${decoder.name}")
        }

        private fun videoEncoderFormat(w: Int, h: Int, fps: Int, baseline: Boolean) =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps / 10).coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
                // The transport stream carries PTS only, so pictures must come out
                // in presentation order: no B-frames.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (baseline) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
            }

        private fun drainVideoDecoder(): Boolean {
            val decoder = videoDecoder ?: return false
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index < 0) return true
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs
            // Pictures between the keyframe the seek landed on and the seek target are decoded, not shown.
            val render = info.size > 0 && pts >= startUs
            decoder.releaseOutputBuffer(index, render)
            if (render && gl!!.copyFrame(pts - startUs, width, height)) videoFrames++
            if (eos) {
                videoEncoder!!.signalEndOfInputStream()
                videoDecoderDone = true
            }
            return true
        }

        private fun drainVideoEncoder(): Boolean {
            val encoder = videoEncoder ?: return false
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = encoder.outputFormat
                val sps = format.getByteBuffer("csd-0")?.let(::bytesOf)
                val pps = format.getByteBuffer("csd-1")?.let(::bytesOf)
                if (sps != null && pps != null) setVideoConfig(sps + pps)
                return true
            }
            if (index < 0) return true
            val buffer = encoder.getOutputBuffer(index)!!
            buffer.position(info.offset).limit(info.offset + info.size)
            val bytes = ByteArray(info.size).also { buffer.get(it) }
            val flags = info.flags
            val pts = info.presentationTimeUs
            encoder.releaseOutputBuffer(index, false)
            when {
                flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> setVideoConfig(bytes)
                bytes.isNotEmpty() -> {
                    val keyframe = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (!stream.writeVideo(bytes, pts, keyframe)) stopped = true
                }
            }
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoDone = true
            return true
        }

        private fun setVideoConfig(annexB: ByteArray) {
            val nals = H264.splitAnnexB(annexB)
            val sps = nals.firstOrNull { H264.type(it) == H264.NAL_SPS } ?: return
            val pps = nals.firstOrNull { H264.type(it) == H264.NAL_PPS } ?: return
            stream.setVideoConfig(sps, pps)
        }

        // ------------------------------------------------------------- audio

        private fun setUpAudioDecoder(source: MediaFormat) {
            val decoder = createDecoder(source)
            // Ask for 16-bit PCM; a decoder that ignores it reports float, which PcmConverter also reads.
            source.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(source, null, null, 0)
            decoder.start()
            audioDecoder = decoder
        }

        /** Created once the decoder has said what it outputs: the encoder's rate and channels follow from it. */
        private fun setUpAudioEncoder(decoded: MediaFormat) {
            val rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = decoded.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
            val converter = PcmConverter(rate, channels, float = encoding == AudioFormat.ENCODING_PCM_FLOAT)
            pcm = converter
            val config = AacConfig(converter.outRate, converter.outChannels)
            aac = config
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (config.channels == 1) 96_000 else 160_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            encoder.start()
            audioEncoder = encoder
            log("convert: audio $rate Hz x$channels -> ${config.sampleRate} Hz x${config.channels}")
        }

        private fun drainAudioDecoder(): Boolean {
            val decoder = audioDecoder ?: return false
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (audioEncoder == null) setUpAudioEncoder(decoder.outputFormat)
                return true
            }
            if (index < 0) return true
            if (info.size > 0 && info.presentationTimeUs >= startUs) {
                if (audioEncoder == null) setUpAudioEncoder(decoder.outputFormat)
                val buffer = decoder.getOutputBuffer(index)!!
                buffer.position(info.offset).limit(info.offset + info.size)
                pending.addLast(pcm!!.convert(buffer))
                if (audioBaseUs < 0) audioBaseUs = info.presentationTimeUs - startUs
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            decoder.releaseOutputBuffer(index, false)
            if (eos) audioDecoderDone = true
            return true
        }

        private fun feedAudioEncoder(): Boolean {
            val encoder = audioEncoder ?: return false
            if (audioEosQueued) return false
            if (pending.isEmpty() && !audioDecoderDone) return false
            val index = encoder.dequeueInputBuffer(0)
            if (index < 0) return false
            val converter = pcm!!
            val pts = audioBaseUs.coerceAtLeast(0) + audioFramesQueued * 1_000_000 / converter.outRate
            if (pending.isEmpty()) {
                encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                audioEosQueued = true
                return true
            }
            val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.clear()
            // Whole frames only: a frame split across buffers would shift the channels.
            var room = (buffer.remaining() / 2 / converter.outChannels) * converter.outChannels
            var written = 0
            while (room > 0 && pending.isNotEmpty()) {
                val head = pending.first()
                val count = min(room, head.size - pendingOffset)
                for (i in 0 until count) buffer.putShort(head[pendingOffset + i])
                pendingOffset += count
                room -= count
                written += count
                if (pendingOffset == head.size) {
                    pending.removeFirst()
                    pendingOffset = 0
                }
            }
            encoder.queueInputBuffer(index, 0, written * 2, pts, 0)
            audioFramesQueued += written / converter.outChannels
            return true
        }

        private fun drainAudioEncoder(): Boolean {
            val encoder = audioEncoder ?: return false
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index < 0) return true
            val buffer = encoder.getOutputBuffer(index)!!
            buffer.position(info.offset).limit(info.offset + info.size)
            val bytes = ByteArray(info.size).also { buffer.get(it) }
            val flags = info.flags
            val pts = info.presentationTimeUs
            encoder.releaseOutputBuffer(index, false)
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bytes.isNotEmpty()) {
                if (!stream.writeAudio(bytes, pts, aac!!)) stopped = true
            }
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) audioDone = true
            return true
        }

        // ------------------------------------------------------------- input

        private fun feedInput(): Boolean {
            val track = extractor.sampleTrackIndex
            if (track < 0) {
                // End of the source: every decoder gets its end-of-stream once.
                var progressed = false
                listOfNotNull(videoDecoder, audioDecoder).forEach { decoder ->
                    val id = System.identityHashCode(decoder)
                    if (id in eosSent) return@forEach
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent += id
                        progressed = true
                    }
                }
                inputDone = eosSent.size == listOfNotNull(videoDecoder, audioDecoder).size
                return progressed
            }
            val decoder = when (track) {
                videoTrack -> videoDecoder
                audioTrack -> audioDecoder
                else -> null
            }
            if (decoder == null) {
                extractor.advance()
                return true
            }
            val index = decoder.dequeueInputBuffer(0)
            if (index < 0) return false
            val buffer = decoder.getInputBuffer(index)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosSent += System.identityHashCode(decoder)
            } else {
                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
            return true
        }

        fun release() {
            listOf(videoDecoder, videoEncoder, audioDecoder, audioEncoder).forEach { codec ->
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }
            runCatching { gl?.release() }
            runCatching { encoderInput?.release() }
        }
    }

    /**
     * Decoded PCM to what the AAC encoder takes: 16-bit, at most two channels,
     * at a rate AAC has an index for.
     *
     * More than two channels are folded down (centre and surrounds mixed into
     * left and right at -3 dB). A rate AAC cannot carry -- 96 kHz FLAC, say -- is
     * resampled to 48 kHz by linear interpolation, which is audible only on
     * content that did not need 96 kHz in the first place.
     */
    private class PcmConverter(private val inRate: Int, private val inChannels: Int, private val float: Boolean) {
        val outChannels = if (inChannels >= 2) 2 else 1
        val outRate = if (inRate in ENCODER_RATES) inRate else 48_000

        private val step = inRate.toDouble() / outRate
        private var position = 0.0
        private var previous: FloatArray? = null

        fun convert(buffer: ByteBuffer): ShortArray {
            buffer.order(ByteOrder.nativeOrder())
            val sampleCount = if (float) buffer.remaining() / 4 else buffer.remaining() / 2
            val frameCount = sampleCount / inChannels
            val frames = Array(frameCount) { FloatArray(inChannels) }
            for (f in 0 until frameCount) for (c in 0 until inChannels) {
                frames[f][c] = if (float) buffer.float else buffer.short / 32768f
            }
            val mixed = frames.map(::downmix)
            val resampled = if (outRate == inRate) mixed else resample(mixed)
            val out = ShortArray(resampled.size * outChannels)
            resampled.forEachIndexed { f, frame ->
                for (c in 0 until outChannels) {
                    out[f * outChannels + c] = (frame[c].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
            return out
        }

        private fun downmix(frame: FloatArray): FloatArray = when {
            inChannels <= 2 -> frame
            else -> {
                // FL, FR, FC, LFE, BL, BR, ... in Android's channel order.
                val centre = frame[2] * MINUS_3DB
                val backLeft = if (inChannels >= 5) frame[4] * MINUS_3DB else 0f
                val backRight = if (inChannels >= 6) frame[5] * MINUS_3DB else 0f
                val scale = if (inChannels >= 5) 1f / (1f + 2 * MINUS_3DB) else 1f / (1f + MINUS_3DB)
                floatArrayOf((frame[0] + centre + backLeft) * scale, (frame[1] + centre + backRight) * scale)
            }
        }

        /** Linear interpolation, carrying the last input frame and the fractional position across calls. */
        private fun resample(frames: List<FloatArray>): List<FloatArray> {
            val extended = (previous?.let { listOf(it) } ?: emptyList()) + frames
            if (extended.size < 2) {
                previous = extended.lastOrNull()
                return emptyList()
            }
            val out = ArrayList<FloatArray>()
            while (position + 1 < extended.size) {
                val i = floor(position).toInt()
                val t = (position - i).toFloat()
                val a = extended[i]
                val b = extended[i + 1]
                out += FloatArray(a.size) { c -> a[c] + (b[c] - a[c]) * t }
                position += step
            }
            position -= (extended.size - 1)
            previous = extended.last()
            return out
        }

        private companion object {
            const val MINUS_3DB = 0.7071f
            /** What Android's AAC encoders accept; 64 kHz and above are left out on purpose. */
            val ENCODER_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
        }
    }

    private companion object {
        const val USER_AGENT = "AirPlayDroid"
        const val DEFAULT_FPS = 30
        const val KEYFRAME_INTERVAL_S = 2
        const val MIN_VIDEO_BITRATE = 1_500_000L
        const val MAX_VIDEO_BITRATE = 8_000_000L
        const val IDLE_SLEEP_MS = 2L

        /**
         * The largest size in the first box that holds the picture upright and the
         * encoder accepts. Never scaled up, and always even: H.264 encoders refuse
         * odd dimensions.
         */
        fun encoderSize(width: Int, height: Int, supported: (Int, Int) -> Boolean): Pair<Int, Int> {
            val boxes = listOf(1920 to 1080, 1280 to 720, 854 to 480)
            var last = 0 to 0
            for ((long, short) in boxes) {
                val (boxW, boxH) = if (width >= height) long to short else short to long
                val scale = min(1.0, min(boxW / width.toDouble(), boxH / height.toDouble()))
                val w = ((width * scale).toInt() and 1.inv()).coerceAtLeast(2)
                val h = ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
                last = w to h
                if (supported(w, h)) return last
            }
            return last
        }

        fun MediaFormat.intOrNull(key: String): Int? =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

        fun bytesOf(buffer: ByteBuffer): ByteArray {
            val copy = buffer.duplicate()
            copy.position(0)
            return ByteArray(copy.remaining()).also { copy.get(it) }
        }
    }
}
