package tw.avianjay.airplaydroid.protocol.mirror

import java.io.Closeable
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The legacy mirroring video cipher: AES-128-CTR over **one continuous
 * keystream**, keyed directly by the FairPlay-unwrapped stream key and the IV
 * sent as `param2`.
 *
 * ### Two paths, two derivations -- do not mix them up
 *
 * There are two ways an AirPlay 1 receiver can be given a mirroring key, and
 * they derive the cipher differently:
 *
 * | | port-7100 `/stream` (**this** class) | RTSP `SETUP` type 110 |
 * |---|---|---|
 * | key | the FairPlay-unwrapped key, used **raw** | `SHA-512("AirPlayStreamKey"+id‖seed)[0:16]` |
 * | IV | `param2`, sent in the request | `SHA-512("AirPlayStreamIV"+id‖seed)[0:16]` |
 * | needs `streamConnectionID` | **no** | yes |
 *
 * The nto specification's `POST /stream` body carries `param1` ("AES key,
 * encrypted with FairPlay") and `param2` ("AES initialization vector") and has
 * **no** `streamConnectionID` field at all. `doubletake` agrees: its
 * `deriveStreamMasterKey` returns the raw key for a legacy receiver
 * ("using raw fpAesKey (legacy receiver or no SharedSecret)").
 *
 * RPiPlay's and UxPlay's `mirror_buffer_init_aes` does the SHA-512 derivation,
 * but it is reached only from `raop_rtp_init_mirror_aes(streamConnectionID)` --
 * the SETUP path. Applying that here would be wrong twice over: it would hash
 * with an id this path never sends, and the receiver would never match it.
 *
 * ### The keystream runs across the whole stream
 *
 * The receiver carries `nextDecryptCount` and a saved keystream tail between
 * payloads, so the effect is one contiguous CTR keystream over the concatenated
 * payloads. A per-packet counter produces a first frame that decrypts and every
 * later frame that does not -- recorded upstream as a "counter desync issue".
 *
 * ### The 128-byte header is not encrypted
 *
 * Only the payload is. The header carries the size and NTP stamp the receiver
 * needs *before* it can decrypt anything, and it is not part of the keystream.
 *
 * ### Not verified on hardware
 *
 * This is a faithful reading of the specification and of `doubletake`, but no
 * receiver has decrypted a frame from it yet -- the dongle went offline. The
 * first hardware run is what settles it.
 */
class LegacyMirrorCipher(
    key: ByteArray,
    iv: ByteArray,
) {
    init {
        require(key.size == KEY_BYTES) { "the stream key is $KEY_BYTES bytes, got ${key.size}" }
        require(iv.size == IV_BYTES) { "the stream IV is $IV_BYTES bytes, got ${iv.size}" }
    }

    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    /**
     * Encrypts (or decrypts -- CTR is symmetric) [payload], advancing the
     * keystream so the next call continues where this one stopped.
     *
     * Deliberately stateful: that continuity *is* the protocol. A stateless
     * helper would have to be handed a running offset, and getting that wrong is
     * the documented desync bug.
     */
    @Synchronized
    fun apply(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return payload
        // update() rather than doFinal(): doFinal would finalise the cipher and
        // lose the counter position the next packet depends on.
        return cipher.update(payload)
    }

    companion object {
        const val KEY_BYTES = 16
        const val IV_BYTES = 16
    }
}

/**
 * The legacy mirroring data channel: writes packetised, encrypted H.264 to an
 * open `/stream` socket.
 *
 * Implements [VideoStreamSink], so `ScreenEncoder` drives it without knowing
 * which protocol is in use.
 *
 * ### The clock
 *
 * The legacy path is NTP-based. Each packet carries an NTP timestamp on the
 * **receiver's** clock, learned from the NTP exchange the receiver initiates on
 * port 7010. [clockOffsetMillis] is added to local time to reach the receiver's;
 * until it is set, local time is used, which is correct only if the two clocks
 * already agree.
 */
class LegacyVideoStream(
    private val output: OutputStream,
    private val cipher: LegacyMirrorCipher?,
    private val ntp: NtpClock = NtpClock(),
) : VideoStreamSink, Closeable {

    /**
     * Wraps an already-open `/stream` socket.
     *
     * [LegacyMirrorSession.OpenStream] is the intended source; it is accepted as
     * an [OutputStream] provider rather than a concrete type so this class stays
     * independent of the session that produced it.
     */
    constructor(
        stream: LegacyMirrorSession.OpenStream,
        cipher: LegacyMirrorCipher?,
        ntp: NtpClock = NtpClock(),
    ) : this(stream.outputStream(), cipher, ntp)

    /** Local-time-to-receiver-clock offset in milliseconds; 0 until measured. */
    @Volatile
    var clockOffsetMillis: Long = 0L

    private var codecConfigSent: ByteArray? = null

    /**
     * Sends the `avcC` record.
     *
     * Never encrypted, and never fed through the CTR keystream: the receiver
     * parses it to configure the decoder before any frame arrives, and advancing
     * the keystream here would desynchronise every frame after it.
     */
    @Synchronized
    override fun setCodecConfig(avcC: ByteArray, width: Int, height: Int) {
        if (avcC.contentEquals(codecConfigSent)) return
        write(LegacyStreamPackets.TYPE_CODEC_DATA, avcC, encrypt = false, captureNanos = System.nanoTime())
        codecConfigSent = avcC
    }

    /**
     * Sends one video frame.
     *
     * [captureNanos] is on the phone's monotonic clock; it becomes wall-clock
     * milliseconds, shifted by [clockOffsetMillis], and is packed as NTP.
     * [keyframe] is unused: the legacy packet format has no flag for it, and a
     * receiver detects an IDR NAL by its type.
     */
    @Synchronized
    override fun sendFrame(avcc: ByteArray, keyframe: Boolean, captureNanos: Long) {
        write(LegacyStreamPackets.TYPE_VIDEO, avcc, encrypt = cipher != null, captureNanos = captureNanos)
    }

    /** Sends a heartbeat. Receivers use these to notice a stalled sender. */
    @Synchronized
    fun sendHeartbeat(captureNanos: Long = System.nanoTime()) {
        write(LegacyStreamPackets.TYPE_HEARTBEAT, ByteArray(0), encrypt = false, captureNanos = captureNanos)
    }

    private fun write(type: Int, payload: ByteArray, encrypt: Boolean, captureNanos: Long) {
        // The header is built first and left in the clear: it carries the payload
        // size and the NTP stamp, which the receiver needs before it can decrypt.
        val packet = LegacyStreamPackets.packet(type, ntpTimestamp(captureNanos), payload)
        val out = if (encrypt && payload.isNotEmpty()) {
            cipher!!.apply(payload).copyInto(packet, LegacyStreamPackets.HEADER_BYTES)
            packet
        } else {
            packet
        }
        output.write(out)
        output.flush()
    }

    private fun ntpTimestamp(captureNanos: Long): Long {
        val epochMillis = System.currentTimeMillis() +
            (captureNanos - System.nanoTime()) / 1_000_000L +
            clockOffsetMillis
        return ntp.toNtp(epochMillis)
    }

    override fun close() {
        runCatching { output.close() }
    }
}
