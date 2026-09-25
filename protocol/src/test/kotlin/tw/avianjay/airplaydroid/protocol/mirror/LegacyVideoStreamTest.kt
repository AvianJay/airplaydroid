package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The legacy video stream: the CTR cipher and the packetised writer.
 *
 * The property that matters most is that the keystream is **contiguous across
 * packets**. The receiver carries a saved keystream tail between payloads, so
 * splitting a stream into packets must not change the ciphertext -- a per-packet
 * reset is the documented "counter desync" bug, where the first frame decrypts
 * and every later one does not.
 */
class LegacyVideoStreamTest {

    private val key = ByteArray(16) { (it * 7 + 1).toByte() }
    private val iv = ByteArray(16) { (it * 3 + 5).toByte() }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------------- the cipher

    @Test
    fun `ctr is its own inverse`() {
        val cipher = LegacyMirrorCipher(key, iv)
        val plaintext = ByteArray(1000) { (it % 256).toByte() }

        val encrypted = cipher.apply(plaintext)
        assertNotEquals(plaintext.toHex(), encrypted.toHex(), "encryption must change the bytes")

        // A fresh cipher is the receiver's starting state.
        assertContentEquals(plaintext, LegacyMirrorCipher(key, iv).apply(encrypted), "decrypt must restore it")
    }

    @Test
    fun `the keystream is contiguous across packets`() {
        // The load-bearing property. Encrypting 100 bytes in one call must equal
        // encrypting 19 + 81 across two calls, because the receiver's keystream
        // position carries over.
        val plaintext = ByteArray(100) { it.toByte() }

        val whole = LegacyMirrorCipher(key, iv).apply(plaintext)

        val split = LegacyMirrorCipher(key, iv)
        val a = split.apply(plaintext.copyOfRange(0, 19))
        val b = split.apply(plaintext.copyOfRange(19, 100))

        assertContentEquals(whole, a + b, "splitting a stream must not change the ciphertext")
    }

    @Test
    fun `three packets in sequence match one contiguous keystream`() {
        // The same property with a non-block-aligned split at every step, which
        // is what real frames look like.
        val plaintext = ByteArray(300) { (it * 11 % 256).toByte() }
        val whole = LegacyMirrorCipher(key, iv).apply(plaintext)

        val cipher = LegacyMirrorCipher(key, iv)
        val pieces = listOf(0 until 37, 37 until 90, 90 until 300).flatMap { range ->
            listOf(cipher.apply(plaintext.copyOfRange(range.first, range.last + 1)))
        }
        assertContentEquals(whole, pieces.reduce { acc, p -> acc + p })
    }

    @Test
    fun `the cipher matches a plain AES-CTR reference`() {
        // Pins that this really is standard AES-CTR with the IV as the initial
        // counter block, and not some bespoke construction.
        val plaintext = ByteArray(64) { it.toByte() }

        val referenceCipher = Cipher.getInstance("AES/CTR/NoPadding")
        referenceCipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(this.key, "AES"),
            IvParameterSpec(this.iv),
        )
        val reference = referenceCipher.doFinal(plaintext)

        assertContentEquals(reference, LegacyMirrorCipher(this.key, this.iv).apply(plaintext))
    }

    @Test
    fun `a different IV gives a different keystream`() {
        val plaintext = ByteArray(64) { 0x5a }
        val a = LegacyMirrorCipher(key, iv).apply(plaintext)
        val b = LegacyMirrorCipher(key, ByteArray(16) { 9 }).apply(plaintext)
        assertNotEquals(a.toHex(), b.toHex())
    }

    @Test
    fun `ctr preserves length exactly`() {
        // No padding and no tag: the packet's declared payload size must stay
        // true, or the receiver reads the next packet misaligned.
        for (size in listOf(1, 15, 16, 17, 100, 4096)) {
            assertEquals(size, LegacyMirrorCipher(key, iv).apply(ByteArray(size)).size, "size $size")
        }
    }

    @Test
    fun `an empty payload is passed through`() {
        assertContentEquals(ByteArray(0), LegacyMirrorCipher(key, iv).apply(ByteArray(0)))
    }

    @Test
    fun `wrongly sized key or IV is refused`() {
        assertFailsWith<IllegalArgumentException> { LegacyMirrorCipher(ByteArray(15), iv) }
        assertFailsWith<IllegalArgumentException> { LegacyMirrorCipher(key, ByteArray(15)) }
    }

    // ------------------------------------------------------------- the stream

    @Test
    fun `an unencrypted stream writes the payload verbatim`() {
        val out = ByteArrayOutputStream()
        LegacyVideoStream(out, cipher = null).use { stream ->
            stream.sendFrame(byteArrayOf(1, 2, 3, 4), keyframe = true, captureNanos = System.nanoTime())
        }

        val decoded = LegacyStreamPackets.decode(out.toByteArray())!!
        assertEquals(LegacyStreamPackets.TYPE_VIDEO, decoded.type)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.payload)
    }

    @Test
    fun `an encrypted stream writes ciphertext that decrypts back`() {
        val out = ByteArrayOutputStream()
        val frame = ByteArray(200) { (it * 3).toByte() }

        LegacyVideoStream(out, LegacyMirrorCipher(key, iv)).use { stream ->
            stream.sendFrame(frame, keyframe = true, captureNanos = System.nanoTime())
        }

        val decoded = LegacyStreamPackets.decode(out.toByteArray())!!
        assertNotEquals(frame.toHex(), decoded.payload.toHex(), "the payload must be encrypted on the wire")
        assertContentEquals(frame, LegacyMirrorCipher(key, iv).apply(decoded.payload), "a receiver must recover it")
    }

    @Test
    fun `two frames in one session decrypt as one keystream`() {
        // End to end through the packetiser: the receiver decrypts payloads in
        // arrival order, so this must hold across real packets.
        val out = ByteArrayOutputStream()
        val f1 = ByteArray(64) { 0x11 }
        val f2 = ByteArray(100) { 0x22 }

        LegacyVideoStream(out, LegacyMirrorCipher(key, iv)).use { stream ->
            stream.sendFrame(f1, keyframe = true)
            stream.sendFrame(f2, keyframe = false)
        }

        val bytes = out.toByteArray()
        val p1 = LegacyStreamPackets.decode(bytes, 0)!!
        val p2 = LegacyStreamPackets.decode(bytes, LegacyStreamPackets.wireSize(p1.payload.size))!!

        val receiver = LegacyMirrorCipher(key, iv)
        assertContentEquals(f1, receiver.apply(p1.payload), "frame 1")
        assertContentEquals(f2, receiver.apply(p2.payload), "frame 2, continuing the keystream")
    }

    @Test
    fun `codec data is never encrypted and does not advance the keystream`() {
        // The avcC record has to be readable, and it must not consume keystream:
        // the receiver does not decrypt it, so advancing here would desynchronise
        // every frame that follows.
        val out = ByteArrayOutputStream()
        val avcC = hex("0164c028ffe10010")
        val frame = ByteArray(32) { 0x33 }

        LegacyVideoStream(out, LegacyMirrorCipher(key, iv)).use { stream ->
            stream.setCodecConfig(avcC, 1280, 720)
            stream.sendFrame(frame, keyframe = true)
        }

        val bytes = out.toByteArray()
        val codec = LegacyStreamPackets.decode(bytes, 0)!!
        val video = LegacyStreamPackets.decode(bytes, LegacyStreamPackets.wireSize(codec.payload.size))!!

        assertEquals(LegacyStreamPackets.TYPE_CODEC_DATA, codec.type)
        assertContentEquals(avcC, codec.payload, "codec config must go out in the clear")
        assertContentEquals(
            frame,
            LegacyMirrorCipher(key, iv).apply(video.payload),
            "the first frame must use the keystream from position 0",
        )
    }

    @Test
    fun `the codec config is sent once and resent only when it changes`() {
        val out = ByteArrayOutputStream()
        val avcC = hex("0164c028ffe10010")
        LegacyVideoStream(out, null).use { stream ->
            stream.setCodecConfig(avcC, 1280, 720)
            stream.setCodecConfig(avcC, 1280, 720) // identical: suppressed
            stream.setCodecConfig(hex("0164c028ffe10011"), 1280, 720) // changed: sent
        }

        var offset = 0
        var count = 0
        val bytes = out.toByteArray()
        while (offset < bytes.size) {
            val decoded = LegacyStreamPackets.decode(bytes, offset)!!
            count++
            offset += LegacyStreamPackets.wireSize(decoded.payload.size)
        }
        assertEquals(2, count, "an unchanged codec config must not be resent")
    }

    @Test
    fun `a heartbeat is a bare header`() {
        val out = ByteArrayOutputStream()
        LegacyVideoStream(out, LegacyMirrorCipher(key, iv)).use { it.sendHeartbeat(0) }

        val bytes = out.toByteArray()
        assertEquals(128, bytes.size, "a heartbeat carries no payload")
        assertEquals(LegacyStreamPackets.TYPE_HEARTBEAT, LegacyStreamPackets.decode(bytes)!!.type)
    }

    @Test
    fun `consecutive frames produce packets a receiver can walk`() {
        // The stream is a sequence of packets with no separator, so a receiver
        // reads one, advances by 128 + payload size, and reads the next. If that
        // arithmetic is wrong the whole stream desynchronises.
        val out = ByteArrayOutputStream()
        val frames = listOf(ByteArray(10), ByteArray(300), ByteArray(1), ByteArray(1500))

        LegacyVideoStream(out, LegacyMirrorCipher(key, iv)).use { stream ->
            frames.forEach { stream.sendFrame(it, keyframe = false, captureNanos = System.nanoTime()) }
        }

        val bytes = out.toByteArray()
        var offset = 0
        frames.forEachIndexed { i, frame ->
            val decoded = LegacyStreamPackets.decode(bytes, offset)
                ?: error("packet $i did not decode at offset $offset")
            assertEquals(frame.size, decoded.payload.size, "packet $i payload size")
            offset += LegacyStreamPackets.wireSize(decoded.payload.size)
        }
        assertEquals(bytes.size, offset, "the walk must consume the stream exactly")
    }

    @Test
    fun `the timestamp tracks the clock offset`() {
        val out = ByteArrayOutputStream()
        val stream = LegacyVideoStream(out, null)
        val ntp = NtpClock()

        stream.clockOffsetMillis = 0
        stream.sendHeartbeat(0)
        val withoutOffset = LegacyStreamPackets.decode(out.toByteArray())!!.ntpTimestamp

        out.reset()
        stream.clockOffsetMillis = 5_000
        stream.sendHeartbeat(0)
        val withOffset = LegacyStreamPackets.decode(out.toByteArray())!!.ntpTimestamp

        assertTrue(
            ntp.fromNtp(withOffset) - ntp.fromNtp(withoutOffset) in 4_000..6_000,
            "a 5 s offset should move the stamped time by about 5 s",
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
