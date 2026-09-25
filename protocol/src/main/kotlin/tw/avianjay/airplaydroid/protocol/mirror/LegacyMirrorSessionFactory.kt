package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayKeyWrap
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayMessageCipher
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayRecords
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponder
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponderImpl
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlaySapSession
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.pairing.LegacyPairVerify
import java.io.Closeable
import java.security.SecureRandom

/**
 * Opens a **legacy (AirPlay 1)** mirroring session end to end.
 *
 * [connect] is the entry point: it tries the RTSP type-110 path
 * ([LegacyRtspMirrorSession]) that current senders use, and falls back to the
 * iOS 6-8 port-7100 path in [open] only when the receiver refuses the first.
 *
 * The port-7100 sequence, against the receiver's RTSP port:
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
 * The RTSP type-110 path is verified on hardware: LonelyScreen, which issues a
 * fresh FairPlay challenge per session, unwrapped our `ekey`, decrypted the
 * video and displayed it. The port-7100 path has only run against
 * [tw.avianjay.airplaydroid.protocol.devtools.MockLegacyReceiver]; no receiver
 * that serves port 7100 has been available.
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
        /** Answers the receiver's NTP queries on port 7010; closed with the rest. */
        private val timing: LegacyTimingServer? = null,
    ) : Closeable {
        /** How many clock queries the receiver has made; zero means it never asked. */
        val timingQueries: Int get() = timing?.answered?.get() ?: 0

        override fun close() {
            runCatching { video.close() }
            runCatching { socket.close() }
            runCatching { timing?.close() }
        }
    }

    /** Why a legacy session could not be opened. */
    class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** True when the receiver answered 401: the password is missing or wrong. */
        val passwordRejected: Boolean
            get() = generateSequence(cause) { it.cause }
                .any { it is LegacyRtspMirrorSession.Failure.Refused && it.status == HTTP_UNAUTHORIZED }
    }

    /** Which legacy protocol a [Connected] session speaks. */
    enum class Path {
        /** RTSP `SETUP` type 110 and a `dataPort`: iOS 9 and later. */
        RTSP_TYPE_110,

        /** `/stream.xml` and `POST /stream` on port 7100: iOS 6-8. */
        PORT_7100,
    }

    /** A legacy session, on whichever path the receiver accepted. */
    class Connected(
        val path: Path,
        /** The receiver's display, when it reported one. */
        val display: ReceiverDisplay?,
        /** Where encoded frames go. */
        val video: VideoStreamSink,
        private val resource: Closeable,
    ) : Closeable {
        override fun close() {
            runCatching { resource.close() }
        }
    }

    /**
     * Opens a legacy session on whichever path the receiver speaks.
     *
     * The RTSP type-110 path goes first: it is what every sender since iOS 9
     * uses, and several receivers (LonelyScreen, RPiPlay, UxPlay) serve nothing
     * else. Port 7100 is tried only when that path is **refused** -- a SETUP
     * answered with an error, or a reply with no `dataPort`. A failed FairPlay
     * handshake, a 401 or an unreachable receiver is reported as is, because
     * port 7100 would fail the same way and hide the real reason.
     *
     * [onEnded] is told when the receiver ends an RTSP session; the port-7100
     * path has no such signal, and its end shows up as a failed write instead.
     */
    fun connect(
        host: String,
        rtspPort: Int,
        password: String? = null,
        senderName: String = "AirPlayDroid",
        streamPort: Int = LegacyMirrorSession.DEFAULT_PORT,
        random: SecureRandom = SecureRandom(),
        responder: FairPlayResponder = FairPlayResponderImpl,
        onEnded: (String) -> Unit = {},
        trace: (String) -> Unit = {},
        keySeed: LegacyRtspMirrorSession.KeySeed = LegacyRtspMirrorSession.KeySeed.AUTO,
        deviceId: String = LegacyRtspMirrorSession.macFrom(random),
    ): Connected {
        val rtsp = try {
            LegacyRtspMirrorSession.open(
                Endpoint(host, rtspPort), password, senderName, random, responder,
                listener = { onEnded(it) }, trace = trace, keySeed = keySeed, deviceId = deviceId,
            )
        } catch (e: LegacyRtspMirrorSession.Failure) {
            if (e is LegacyRtspMirrorSession.Failure.Refused && e.status == HTTP_UNAUTHORIZED) {
                throw Failure("$host refused the password (${e.message})", e)
            }
            trace("RTSP mirroring refused (${e.message}); trying port $streamPort")
            val opened = try {
                open(host, rtspPort, password, streamPort, random, responder)
            } catch (f: Failure) {
                throw Failure("$host refused RTSP mirroring (${e.message}) and port $streamPort (${f.message})", f)
            }
            return Connected(Path.PORT_7100, opened.display, opened.video, opened)
        } catch (e: FairPlaySapSession.Failure) {
            throw Failure("FairPlay handshake failed: ${e.message}", e)
        } catch (e: LegacyPairVerify.Failure) {
            throw Failure("pairing failed: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw Failure("could not mirror to $host:$rtspPort: ${e.message}", e)
        }
        return Connected(Path.RTSP_TYPE_110, rtsp.display, rtsp, rtsp)
    }

    /**
     * Runs the whole legacy handshake.
     *
     * [rtspPort] is the receiver's RTSP port (`/fp-setup` lives there); the
     * mirroring endpoint is [streamPort]. The dongle this was written against
     * answers RTSP on 5000 and `/stream.xml` on 7100, which is why they are
     * separate parameters rather than one port.
     *
     * ### Password-protected receivers
     *
     * [password] answers the receiver's HTTP Digest challenge on the mirroring
     * endpoint (realm `AirPlay`, username `AirPlay`); see [LegacyMirrorSession].
     */
    fun open(
        host: String,
        rtspPort: Int,
        password: String? = null,
        streamPort: Int = LegacyMirrorSession.DEFAULT_PORT,
        random: SecureRandom = SecureRandom(),
        responder: FairPlayResponder = FairPlayResponderImpl,
    ): Opened {
        // A password-protected receiver is handled by HTTP Digest on the mirroring
        // endpoint, not by SRP pairing: the nto spec's "Password Protection"
        // section says AirPlay 1 passwords are plain Digest, realm `AirPlay`,
        // username `AirPlay`. [password] is passed straight to the session, which
        // answers the 401 challenge.
        //
        // Note the FairPlay handshake itself is unauthenticated -- the receiver
        // asks for credentials on the mirroring endpoint, not on /fp-setup.

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
        val mirror = LegacyMirrorSession(Endpoint(host, streamPort), password = password)
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

        // --- 4. Start the stream. The receiver starts querying our clock on
        // port 7010 as soon as it has the request, so answer before sending it.
        val timing = runCatching { LegacyTimingServer() }.getOrNull()
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
            runCatching { timing?.close() }
            throw Failure("POST /stream failed: ${e.message}", e)
        }

        val video = LegacyVideoStream(open, LegacyMirrorCipher(streamKey, iv))
        return Opened(
            display = ReceiverDisplay(info.width, info.height),
            video = video,
            socket = open,
            timing = timing,
        )
    }

    /** `AirPlay/220.68`, the version the captures report. */
    const val VERSION = "220.68"

    /** Any stable per-install id works; the receiver only echoes it back. */
    const val DEVICE_ID = 0x271F67BA55C9L

    /** The spec's example latency. */
    const val LATENCY_MS = 100

    private const val HTTP_UNAUTHORIZED = 401
}
