package tw.avianjay.airplaydroid.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import tw.avianjay.airplaydroid.protocol.mirror.AlacVerbatim
import tw.avianjay.airplaydroid.protocol.mirror.MirrorSession
import kotlin.concurrent.thread

/**
 * Captures what other apps are playing (AudioPlaybackCapture, API 29+) and
 * feeds it to a [MirrorSession] as 352-sample stereo frames at 44.1 kHz.
 *
 * Recording starts as soon as this is created, before any session exists:
 * on Android 11 capture may only *start* while the app is in the foreground,
 * and that is just after the consent prompt returns. Frames read before
 * [attach] are discarded.
 *
 * Only media, game and unknown-usage audio from apps that allow capture is
 * included; notifications, calls and apps that opt out (typically DRM
 * streaming apps) stay on the phone. The phone keeps playing the sound too --
 * no public API mutes it.
 */
class AudioCapture private constructor(private val record: AudioRecord) {

    @Volatile private var session: MirrorSession? = null
    @Volatile private var running = true

    private val reader = thread(name = "mirror-audio-capture") { read() }

    fun attach(session: MirrorSession) {
        this.session = session
    }

    private fun read() {
        val spf = AlacVerbatim.SAMPLES_PER_FRAME
        val pcm = ShortArray(spf * 2)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        try {
            while (running) {
                var filled = 0
                while (filled < pcm.size && running) {
                    val n = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read failed: $n")
                        return
                    }
                    filled += n
                }
                if (!running) break
                framesRead += spf
                val target = session ?: continue

                // When the first sample of this frame was captured, on the
                // System.nanoTime() clock the session maps to the receiver.
                val first = framesRead - spf
                val captureNanos =
                    if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        timestamp.nanoTime + (first - timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
                    } else {
                        System.nanoTime() - spf * 1_000_000_000L / SAMPLE_RATE
                    }
                target.sendAudio(pcm, captureNanos)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped", e)
        }
    }

    fun stop() {
        running = false
        runCatching { record.stop() }
        reader.join(500)
        runCatching { record.release() }
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 44_100

        /**
         * Starts capturing through [projection], or returns null if this device
         * cannot (no RECORD_AUDIO, or the audio policy refused). Mirroring then
         * carries video only.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        @SuppressLint("MissingPermission") // checked by the caller; a denial throws and is caught
        fun start(projection: MediaProjection): AudioCapture? = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, AlacVerbatim.SAMPLES_PER_FRAME * 4) * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                Log.w(TAG, "playback capture did not start")
                null
            } else {
                AudioCapture(record)
            }
        } catch (e: Exception) {
            Log.w(TAG, "playback capture unavailable", e)
            null
        }
    }
}
