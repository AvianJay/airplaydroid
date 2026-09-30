package tw.avianjay.airplaydroid.protocol.media

import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A live HLS stream built while it plays: encoded samples go in, segments and a
 * playlist come out.
 *
 * The playlist is a sliding window, not an EVENT or VOD playlist, because the
 * stream is converted on a phone: a two-hour film cannot be kept whole in
 * memory, and its segment boundaries are not known before it is encoded.
 * `EXT-X-START:TIME-OFFSET=0` makes a player start at the first segment rather
 * than at the live edge, so nothing is skipped.
 *
 * **Back-pressure** holds the window together. The producer ([writeVideo],
 * [writeAudio]) blocks once it is [maxAhead] segments past the newest one a
 * player has asked for, so a converter that runs faster than real time cannot
 * push out segments nobody has fetched. Segments more than [keepBehind] behind
 * that point are dropped.
 *
 * Segments are cut at the first video keyframe at least [targetSeconds] into the
 * segment, or, for audio only, at the first frame past it. `EXTINF` is the
 * measured duration, never the target.
 */
class HlsStream(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    private val targetSeconds: Double = 4.0,
    private val maxAhead: Int = 5,
    private val keepBehind: Int = 3,
) {
    class Segment(val sequence: Int, val durationSeconds: Double, val bytes: ByteArray)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private val segments = ArrayDeque<Segment>()
    private var nextSequence = 0
    private var newestRequested = -1
    private var finished = false
    private var failed: String? = null
    @Volatile private var closed = false

    private val writer = MpegTsWriter(hasVideo, hasAudio)
    private var segmentStartUs = -1L
    private var lastPtsUs = -1L
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** SPS and PPS without start codes, from the encoder's codec-config output. */
    fun setVideoConfig(sps: ByteArray, pps: ByteArray) = lock.withLock {
        this.sps = sps
        this.pps = pps
    }

    /** Returns false once the stream is closed: the producer should stop. */
    fun writeVideo(annexB: ByteArray, ptsUs: Long, keyframe: Boolean): Boolean {
        if (!awaitRoom()) return false
        lock.withLock {
            if (keyframe) maybeCut(ptsUs)
            // Nothing is written before the first keyframe: a segment must start with one.
            if (segmentStartUs < 0) return true
            writer.writeVideo(annexB, ptsUs, keyframe, sps, pps)
            lastPtsUs = maxOf(lastPtsUs, ptsUs)
        }
        return true
    }

    fun writeAudio(rawAac: ByteArray, ptsUs: Long, config: AacConfig): Boolean {
        if (!awaitRoom()) return false
        lock.withLock {
            if (!hasVideo) maybeCut(ptsUs)
            // With video, audio waits for the first keyframe to open the first segment.
            if (segmentStartUs < 0) return true
            writer.writeAudio(rawAac, ptsUs, config)
            lastPtsUs = maxOf(lastPtsUs, ptsUs)
        }
        return true
    }

    /** The source ended: the last segment is closed and the playlist gets `EXT-X-ENDLIST`. */
    fun finish() = lock.withLock {
        if (segmentStartUs >= 0 && writer.pending > 0) closeSegment(lastPtsUs)
        finished = true
        changed.signalAll()
    }

    /** The converter failed: players are told the stream is over, and [failure] says why. */
    fun fail(reason: String) = lock.withLock {
        failed = reason
        finish()
    }

    val failure: String? get() = lock.withLock { failed }

    /** Unblocks the producer and every waiting request; nothing more is served. */
    fun close() = lock.withLock {
        closed = true
        segments.clear()
        changed.signalAll()
    }

    /**
     * The playlist, once there is something in it. Waits up to [timeoutMs] for
     * the first segment, because a player that fetches an empty live playlist
     * backs off for a whole target duration or gives up.
     */
    fun playlist(timeoutMs: Long): String? = lock.withLock {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && segments.isEmpty() && !finished) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            changed.awaitNanos(left)
        }
        if (closed || segments.isEmpty()) return null
        buildPlaylist()
    }

    /**
     * Segment [sequence], waiting up to [timeoutMs] for it to be produced.
     * Asking for it is what moves the back-pressure window forward.
     */
    fun segment(sequence: Int, timeoutMs: Long): Segment? = lock.withLock {
        if (sequence > newestRequested) {
            newestRequested = sequence
            evict()
            changed.signalAll()
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed) {
            segments.firstOrNull { it.sequence == sequence }?.let { return it }
            // Evicted, or past the end: it will never come.
            if (sequence < (segments.firstOrNull()?.sequence ?: nextSequence) || finished) return null
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            changed.awaitNanos(left)
        }
        null
    }

    private fun awaitRoom(): Boolean = lock.withLock {
        while (!closed && nextSequence - newestRequested > maxAhead) {
            changed.await(1, TimeUnit.SECONDS)
        }
        !closed
    }

    /** Under [lock]. Opens the first segment, or closes the current one once it is long enough. */
    private fun maybeCut(ptsUs: Long) {
        if (segmentStartUs < 0) {
            segmentStartUs = ptsUs
            writer.writeTables()
            return
        }
        if ((ptsUs - segmentStartUs) / 1_000_000.0 >= targetSeconds) {
            closeSegment(ptsUs)
            segmentStartUs = ptsUs
            writer.writeTables()
        }
    }

    private fun closeSegment(endUs: Long) {
        val duration = ((endUs - segmentStartUs).coerceAtLeast(0) / 1_000_000.0).coerceAtLeast(MIN_DURATION)
        segments.addLast(Segment(nextSequence++, duration, writer.take()))
        evict()
        changed.signalAll()
    }

    private fun evict() {
        while (segments.isNotEmpty() && segments.first().sequence < newestRequested - keepBehind) {
            segments.removeFirst()
        }
    }

    private fun buildPlaylist(): String {
        val first = segments.first().sequence
        val target = kotlin.math.ceil(maxOf(targetSeconds, segments.maxOf { it.durationSeconds })).toInt()
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
            // Only at the very start: once the window has slid, the player already has its place.
            if (first == 0) append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
            if (finished && first == 0) append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            segments.forEach { segment ->
                append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", segment.durationSeconds)).append(",\n")
                append(segment.sequence).append(".ts\n")
            }
            if (finished) append("#EXT-X-ENDLIST\n")
        }
    }

    private companion object {
        /** A zero EXTINF makes some players skip the segment; a frame's worth is the floor. */
        const val MIN_DURATION = 0.04
    }
}
