package tw.avianjay.airplaydroid.protocol.http

import tw.avianjay.airplaydroid.protocol.pairing.HapCrypto
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The encrypted control channel that follows pair-verify. The same socket keeps
 * carrying RTSP, but every chunk of at most 1024 bytes travels as
 *
 *     [uint16-LE length][ChaCha20-Poly1305 ciphertext][16-byte tag]
 *
 * with the two length bytes as AAD and a nonce of four zero bytes followed by a
 * little-endian 64-bit counter, one counter per direction. This is the framing
 * seen on the wire right after M4 in the iPhone capture, and what pyatv's
 * HAPSession implements.
 */
internal object HapFrames {
    const val MAX_FRAME = 1024
    const val TAG = 16

    fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        return n
    }
}

/** Buffers plaintext and emits it as encrypted frames on [flush]. */
internal class HapFrameOutputStream(
    private val raw: OutputStream,
    private val key: ByteArray,
) : OutputStream() {

    private val pending = ByteArrayOutputStream()
    private var counter = 0L

    override fun write(b: Int) = pending.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = pending.write(b, off, len)

    override fun flush() {
        val data = pending.toByteArray()
        pending.reset()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(HapFrames.MAX_FRAME, data.size - offset)
            val aad = byteArrayOf(len.toByte(), (len ushr 8).toByte())
            raw.write(aad)
            raw.write(HapCrypto.seal(key, HapFrames.nonce(counter++), data.copyOfRange(offset, offset + len), aad))
            offset += len
        }
        raw.flush()
    }

    override fun close() = raw.close()
}

/** Reads encrypted frames and serves their plaintext. */
internal class HapFrameInputStream(
    private val raw: InputStream,
    private val key: ByteArray,
) : InputStream() {

    private var frame = ByteArray(0)
    private var position = 0
    private var counter = 0L

    override fun read(): Int {
        if (!fill()) return -1
        return frame[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, frame.size - position)
        System.arraycopy(frame, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = frame.size - position

    /** Ensures unread plaintext is buffered; false at clean EOF between frames. */
    private fun fill(): Boolean {
        while (position >= frame.size) {
            val lo = raw.read()
            if (lo < 0) return false
            val hi = raw.read()
            if (hi < 0) throw EOFException("connection closed inside a frame header")
            val aad = byteArrayOf(lo.toByte(), hi.toByte())
            val sealed = readFully((lo or (hi shl 8)) + HapFrames.TAG)
            frame = try {
                HapCrypto.open(key, HapFrames.nonce(counter++), sealed, aad)
            } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
                throw IOException("encrypted frame failed authentication", e)
            }
            position = 0
        }
        return true
    }

    private fun readFully(size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = raw.read(out, read, size - read)
            if (n < 0) throw EOFException("connection closed inside a frame")
            read += n
        }
        return out
    }

    override fun close() = raw.close()
}
