package tw.avianjay.airplaydroid.protocol.mirror

/**
 * Maps this device's monotonic clock (`System.nanoTime()`, which is also the
 * time base of MediaCodec surface timestamps and of AudioRecord's MONOTONIC
 * timestamps) onto the receiver's clock -- the PTP timeline its ClockID names.
 *
 * No PTP client runs. Every RTSP reply carries `X-Apple-RequestReceivedTimestamp`
 * and `X-Apple-ProcessingTime` in milliseconds on that timeline, so a reply
 * received at local time `r` says the receiver clock read about
 * `received + processing` ms at `r` -- low by the reply's one-way network
 * delay and by up to 1 ms of truncation. The first sample (the control SETUP
 * reply) anchors the mapping.
 *
 * The two clocks drift apart by tens of ppm, which over an hour is enough to
 * eat the playout lead. So later samples (from each `/feedback` reply) are kept
 * in a sliding window, and the offset slews toward the window's maximum -- the
 * sample with the least network delay -- by at most [MAX_SLEW_NANOS] per update.
 * The bound keeps one delayed reply from moving the picture or the sound.
 */
class ReceiverClock(receiverMs: Long, localNanos: Long) {

    private val window = ArrayDeque<Long>()
    @Volatile private var offsetNanos: Long = receiverMs * 1_000_000 - localNanos

    /** The receiver-clock time, in nanoseconds, of local instant [localNanos]. */
    fun receiverNanos(localNanos: Long): Long = localNanos + offsetNanos

    /** Feeds one more (receiver ms, local nanos) sample, from a reply received at [localNanos]. */
    @Synchronized
    fun observe(receiverMs: Long, localNanos: Long) {
        window.addLast(receiverMs * 1_000_000 - localNanos)
        while (window.size > WINDOW) window.removeFirst()
        val target = window.max()
        val delta = (target - offsetNanos).coerceIn(-MAX_SLEW_NANOS, MAX_SLEW_NANOS)
        offsetNanos += delta
    }

    companion object {
        private const val WINDOW = 8
        /** 2 ms per /feedback (every 2 s) = 1000 ppm, far above any crystal's drift. */
        const val MAX_SLEW_NANOS = 2_000_000L

        /** Reads the receiver timestamp headers of a reply, or null if absent. */
        fun receiverMsOf(header: (String) -> String?): Long? {
            val received = header("X-Apple-RequestReceivedTimestamp")?.toLongOrNull() ?: return null
            val processing = header("X-Apple-ProcessingTime")?.toLongOrNull() ?: 0L
            return received + processing
        }

        /** Nanoseconds to the 32.32 fixed-point seconds video headers carry. */
        fun toFixed32(nanos: Long): Long {
            val seconds = nanos / 1_000_000_000L
            val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
            return (seconds shl 32) or fraction
        }
    }
}
