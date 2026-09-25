package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayKeyWrap
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayMessageCipher
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayRecords
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponder
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponderImpl
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlaySapSession
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.io.Closeable
import java.security.SecureRandom

/**
 * Opens a **legacy (AirPlay 1)** mirroring session end to end.
 *
 * The sequence, against the receiver's RTSP port:
 *
 * ```
 * 1. FairPlay SAP:  m1 -> m2 -> m3 -> m4          (FairPlaySapSession)
 * 2. GET /stream.xml                              -> the display size
 * 3. wrap the stream key into param1              (FairPlayKeyWrap)
 * 4. POST /stream                                 -> the video socket
 * ```
 *
 * Steps 1 and 3 both need the **same** local SAP: the m3 body carries it, and the
 * receiver folds it into the key it derives. Generating a second one for the key
 * wrap would produce a stream key the receiver cannot recover, which shows up as
 * a picture that never appears rather than as an error -- so the local SAP comes
 * from the handshake result and is threaded through both.
 *
 * This lives in the protocol module, not the app, because it is pure protocol
 * logic with no Android dependency -- which is what lets the whole handshake be
 * tested against a mock receiver offline.
 *
 * ### What is verified
 *
 * Every step has offline tests against captured bytes, the FairPlay core passes
 * 70 conformance vectors, and [LegacySessionEndToEndTest] drives this whole
 * method against a mock receiver. **No real receiver has accepted a session from
 * this code yet** -- the dongle that was available had gone offline. Treat the
 * first hardware run as the real test.
 */
object LegacyMirrorSessionFactory {

    /** A legacy session that is up and streaming-ready. */
    class Opened(
        /** The receiver's display, for sizing the encoder canvas. */
        val display: ReceiverDisplay,
        /** Where encoded frames go. */
        val video: LegacyVideoStream,
        /** The underlying socket, closed with [video]. */
        private val socket: Closeable,
    ) : Closeable {
        override fun close() {
            runCatching { video.close() }
            runCatching { socket.close() }
        }
    }

    /** Why a legacy session could not be opened. */
    class Failure(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Runs the whole legacy handshake.
     *
     * [rtspPort] is the receiver's RTSP port (`/fp-setup` lives there); the
     * mirroring endpoint is [streamPort]. The dongle this was written against
     * answers RTSP on 5000 and `/stream.xml` on 7100, which is why they are
     * separate parameters rather than one port.
     *
     * ### Password-protected receivers are not supported yet
     *
     * A password-protected legacy receiver wants **legacy SRP pairing** before the
     * FairPlay handshake. `LegacyPairing` implements that, but it is not wired in
     * here, so a non-null [password] is refused rather than accepted and ignored.
     *
     * Refusing is deliberate. Accepting the parameter and dropping it would make a
     * password-protected receiver fail at `/fp-setup` with a message about
     * FairPlay, sending the next person to debug the wrong layer entirely.
     */
    fun open(
        host: String,
        rtspPort: Int,
        password: String? = null,
        streamPort: Int = LegacyMirrorSession.DEFAULT_PORT,
        random: SecureRandom = SecureRandom(),
        responder: FairPlayResponder = FairPlayResponderImpl,
    ): Opened {
        if (!password.isNullOrEmpty()) {
            throw Failure(
                "legacy pairing with an AirPlay password is not implemented yet; " +
                    "this receiver needs it before FairPlay"
            )
        }

        // --- 1. FairPlay SAP, on the RTSP port.
        val result: FairPlaySapSession.Result
        try {
            SocketAirPlayConnection(Endpoint(host, rtspPort)).use { control ->
                result = FairPlaySapSession(control, responder, random).handshake()
            }
        } catch (e: FairPlaySapSession.Failure) {
            throw Failure("FairPlay handshake failed: ${e.message}", e)
        } catch (e: Exception) {
            throw Failure("could not reach $host:$rtspPort for FairPlay: ${e.message}", e)
        }

        // The local SAP must be the one the m3 actually carried. Taking a fresh
        // one here would key the stream with something the receiver never saw,
        // and the symptom is a picture that never appears rather than an error.
        val localSap = result.localSap
        val challenge = result.challenge

        // --- 2. The mirroring endpoint's display description.
        val mirror = LegacyMirrorSession(Endpoint(host, streamPort))
        val info = try {
            mirror.streamInfo()
        } catch (e: Exception) {
            throw Failure("could not read /stream.xml from $host:$streamPort: ${e.message}", e)
        }

        // --- 3. Wrap the stream key. The receiver's own SAP is the decrypted
        // challenge; the wrap needs it alongside our local SAP.
        val streamKey = ByteArray(FairPlayKeyWrap.RAW_KEY_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val receiverSap = try {
            FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, challenge)
        } catch (e: Exception) {
            throw Failure("could not derive the receiver's SAP: ${e.message}", e)
        }

        val param1 = try {
            FairPlayKeyWrap.wrap(receiverSap, localSap, streamKey, random)
        } catch (e: Exception) {
            throw Failure("could not wrap the stream key: ${e.message}", e)
        }

        // --- 4. Start the stream.
        val request = LegacyMirrorSession.StreamRequest(
            deviceId = DEVICE_ID,
            sessionId = random.nextInt() and 0x7FFF_FFFF,
            version = VERSION,
            param1 = param1,
            param2 = iv,
            latencyMs = LATENCY_MS,
        )

        val open = try {
            mirror.startStream(request)
        } catch (e: Exception) {
            throw Failure("POST /stream failed: ${e.message}", e)
        }

        val video = LegacyVideoStream(open, LegacyMirrorCipher(streamKey, iv))
        return Opened(
            display = ReceiverDisplay(info.width, info.height),
            video = video,
            socket = open,
        )
    }

    /** `AirPlay/220.68`, the version the captures report. */
    const val VERSION = "220.68"

    /** Any stable per-install id works; the receiver only echoes it back. */
    const val DEVICE_ID = 0x271F67BA55C9L

    /** The spec's example latency. */
    const val LATENCY_MS = 100
}
