package tw.avianjay.airplaydroid.protocol.mirror

/**
 * ALAC without compression: the "escape" frame an ALAC decoder must accept,
 * which carries raw PCM behind a small bit-packed header. It lets us send
 * ALAC 44100/16/2 -- the screen-audio format receivers advertise as
 * `supportedFormats.screenStream` bit 18 -- with no encoder at all, at the
 * cost of sending PCM-sized frames (1416 bytes per 352 stereo samples).
 *
 * Layout, MSB-first, for one channel-pair element (Apple's ALACDecoder):
 *
 *     3  element tag = 1 (channel pair)
 *     4  instance tag = 0
 *    12  unused = 0
 *     1  partial frame = 1, so the sample count follows explicitly
 *     2  bytes shifted = 0
 *     1  escape = 1 (uncompressed)
 *    32  sample count
 *    16  L, 16 R  per sample, two's complement, interleaved
 *     3  end tag = 7, then zero padding to a byte
 *
 * The samples start at bit 55, so they cannot be byte-copied.
 */
object AlacVerbatim {

    const val SAMPLES_PER_FRAME = 352

    /** Size of a frame of [samples] stereo 16-bit samples. */
    fun frameSize(samples: Int = SAMPLES_PER_FRAME): Int = (23 + 32 + samples * 32 + 3 + 7) / 8

    /**
     * Encodes interleaved stereo [pcm] (L, R, L, R, ...), [samples] frames of it
     * starting at [offset], into [out]. Returns the number of bytes written.
     */
    fun encode(pcm: ShortArray, out: ByteArray, samples: Int = SAMPLES_PER_FRAME, offset: Int = 0): Int {
        require(pcm.size - offset >= samples * 2) { "need ${samples * 2} interleaved samples" }
        val size = frameSize(samples)
        require(out.size >= size) { "output needs $size bytes" }

        var acc = 0L // pending bits, right-aligned
        var bits = 0
        var pos = 0
        fun put(value: Int, width: Int) {
            acc = (acc shl width) or (value.toLong() and ((1L shl width) - 1))
            bits += width
            while (bits >= 8) {
                bits -= 8
                out[pos++] = (acc ushr bits).toByte()
            }
            acc = acc and ((1L shl bits) - 1)
        }

        put(1, 3)
        put(0, 4)
        put(0, 12)
        put(1, 1)
        put(0, 2)
        put(1, 1)
        put(samples ushr 16, 16)
        put(samples and 0xFFFF, 16)
        for (i in 0 until samples * 2) put(pcm[offset + i].toInt(), 16)
        put(7, 3)
        if (bits > 0) put(0, 8 - bits)
        return pos
    }
}
