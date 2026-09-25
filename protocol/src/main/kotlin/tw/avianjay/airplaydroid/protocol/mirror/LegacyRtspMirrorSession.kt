package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayKeyWrap
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayMessageCipher
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponder
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponderImpl
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlaySapSession
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.http.DigestAuth
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.pairing.LegacyPairVerify
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import tw.avianjay.airplaydroid.protocol.plist.Plists
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Legacy (AirPlay 1) mirroring over **RTSP `SETUP` type 110** -- what every iOS
 * release since 9 sends to a receiver that does not advertise HAP pairing.
 *
 * Every third-party receiver checked speaks this, and several speak nothing
 * else: LonelyScreen listens only on its RTSP port (7000), and RPiPlay, UxPlay
 * and the dongles built on them answer the same way. The port-7100 `/stream`
 * path in [LegacyMirrorSession] is the iOS 6-8 protocol.
 *
 * All on one control connection:
 *
 * ```
 * 1. GET /info                         the display size (optional)
 * 2. /pair-setup, /pair-verify ×2      Ed25519 / X25519  (LegacyPairVerify)
 * 3. /fp-setup ×2                      FairPlay SAP      (FairPlaySapSession)
 * 4. SETUP  {ekey, eiv, timingPort}    -> eventPort, timingPort
 * 5. SETUP  {streams: [type 110]}      -> dataPort
 * 6. RECORD
 * 7. TCP to dataPort: 128-byte-header packets, AES-CTR video
 *    POST /feedback every 2 s; the receiver queries our clock over UDP
 * ```
 *
 * ### Why it must be one connection
 *
 * The receiver keeps the FairPlay state per connection. The `ekey` in step 4 is
 * unwrapped with the m3 that *this connection* carried, so a SETUP on a fresh
 * connection has nothing to unwrap it with.
 *
 * ### The video key
 *
 * Not the FairPlay key itself. RPiPlay's `mirror_buffer_init_aes`:
 *
 * ```
 * seed = SHA-512(aesKey || ecdhSecret)[0:16]          (aesKey alone if unpaired)
 * key  = SHA-512("AirPlayStreamKey" + id || seed)[0:16]
 * iv   = SHA-512("AirPlayStreamIV"  + id || seed)[0:16]
 * ```
 *
 * where `id` is the `streamConnectionID` in **decimal** (`%llu`). One CTR
 * keystream then runs across every video payload, exactly as on the port-7100
 * path ([LegacyMirrorCipher]).
 */
class LegacyRtspMirrorSession private constructor(
    private val control: SocketAirPlayConnection,
    private val requester: Requester,
    private val controlUri: String,
    private val data: Socket,
    private val video: LegacyVideoStream,
    private val timing: LegacyTimingServer,
    /** The receiver's display from `/info`, when it reported one. */
    val display: ReceiverDisplay?,
    /** Whether pair-verify ran; if not, the video key was derived without it. */
    val paired: Boolean,
    /** The seed the video key was derived from: [KeySeed.RAW] or [KeySeed.MIXED]. */
    val keySeed: KeySeed,
    private val listener: Listener,
) : VideoStreamSink, Closeable {

    /**
     * How the FairPlay key becomes the video key's seed.
     *
     * Receivers disagree, and nothing they advertise says which one they want.
     * Measured on 2026-09-25, each receiver showing a picture with one seed and
     * nothing with the other:
     *
     * | receiver | seed | SETUP replies carry a `timingPort` |
     * |---|---|---|
     * | LonelyScreen | [RAW] | no |
     * | iPhoneMirror (airplay2dll) | [MIXED] | yes |
     *
     * `X-Apple-PD: 1` on the pairing requests did not move LonelyScreen to
     * [MIXED]. RPiPlay's source mixes, and its SETUP reply also carries a
     * `timingPort`.
     */
    enum class KeySeed {
        /** The FairPlay key as is. */
        RAW,

        /**
         * `SHA-512(key || pair-verify secret)[0:16]`, as RPiPlay's
         * `mirror_buffer_init_aes` does. [RAW] when the receiver did not pair.
         */
        MIXED,

        /**
         * [MIXED] when a SETUP reply advertised a `timingPort` -- the RPiPlay
         * lineage, which runs its own NTP client -- and [RAW] otherwise.
         *
         * A heuristic from the two receivers above, not a rule: doubletake
         * reports a real Apple TV 3 that queries timing yet wants [RAW]. That is
         * why the app lets the user override it per receiver.
         */
        AUTO,
    }

    /** Told once when the receiver ends the session. Not called for [close]. */
    fun interface Listener {
        fun onEnded(reason: String)
    }

    sealed class Failure(message: String, cause: Throwable? = null) : IOException(message, cause) {
        class Refused(val step: String, val status: Int) : Failure("receiver refused $step with $status")
        class Malformed(detail: String) : Failure("malformed reply: $detail")
    }

    private val closed = AtomicBoolean(false)
    @Volatile private var firstFrameSent = false

    val isOpen: Boolean get() = !closed.get()

    /** How many clock queries the receiver has made; zero means it never synced. */
    val timingQueries: Int get() = timing.answered.get()

    private fun start() {
        thread(isDaemon = true, name = "legacy-data-watch") {
            // The receiver never writes on the data channel; a read that returns
            // means it hung up.
            runCatching { data.getInputStream().read() }
            end("the receiver closed the video connection")
        }
        thread(isDaemon = true, name = "legacy-heartbeat") {
            while (isOpen && !firstFrameSent) sleepQuietly(50)
            while (isOpen) {
                try {
                    video.sendHeartbeat()
                } catch (e: IOException) {
                    end("video connection failed: ${e.message}")
                    break
                }
                sleepQuietly(1_000)
            }
        }
        thread(isDaemon = true, name = "legacy-feedback") {
            var failures = 0
            while (isOpen) {
                sleepQuietly(2_000)
                if (!isOpen) break
                val response = runCatching { requester.request("POST", "/feedback") }.getOrNull()
                failures = if (response?.isSuccess == true) 0 else failures + 1
                if (failures >= MAX_FEEDBACK_FAILURES) {
                    end("the receiver stopped answering (/feedback ${response?.status ?: "failed"})")
                    break
                }
            }
        }
    }

    override fun setCodecConfig(avcC: ByteArray, width: Int, height: Int) {
        if (!isOpen) throw IOException("mirroring session is closed")
        video.setCodecConfig(avcC, width, height)
    }

    override fun sendFrame(avcc: ByteArray, keyframe: Boolean, captureNanos: Long) {
        if (!isOpen) throw IOException("mirroring session is closed")
        video.sendFrame(avcc, keyframe, captureNanos)
        firstFrameSent = true
    }

    private fun end(reason: String) {
        if (closed.compareAndSet(false, true)) {
            shutdown(teardown = false)
            listener.onEnded(reason)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) shutdown(teardown = true)
    }

    private fun shutdown(teardown: Boolean) {
        if (teardown) runCatching { requester.request("TEARDOWN", controlUri) }
        runCatching { data.close() }
        runCatching { timing.close() }
        runCatching { control.close() }
    }

    /** Serialises control requests, answering a Digest challenge once one is seen. */
    internal class Requester(
        private val control: SocketAirPlayConnection,
        private val password: String?,
        private var cseq: Int,
    ) {
        private var challenge: DigestAuth.Challenge? = null

        @Synchronized
        fun request(
            method: String,
            uri: String,
            body: PlistValue.PDict? = null,
            extra: List<Pair<String, String>> = emptyList(),
        ): AirPlayResponse {
            fun send() = control.exchange(
                AirPlayRequest(
                    method = method,
                    uri = uri,
                    protocol = AirPlayRequest.RTSP_1_0,
                    headers = buildList {
                        add("CSeq" to (cseq++).toString())
                        add("User-Agent" to USER_AGENT)
                        if (body != null) add("Content-Type" to CONTENT_TYPE_BPLIST)
                        addAll(extra)
                        val c = challenge
                        if (c != null && password != null) {
                            add("Authorization" to DigestAuth.authorization(c, method, uri, password))
                        }
                    },
                    body = body?.let { BinaryPlist.encode(it) } ?: ByteArray(0),
                )
            )
            var response = send()
            if (response.status == 401 && password != null && challenge == null) {
                challenge = DigestAuth.Challenge.parse(response.header("WWW-Authenticate"))
                if (challenge != null) response = send()
            }
            return response
        }
    }

    companion object {
        const val USER_AGENT = "AirPlay/220.68"
        private const val CONTENT_TYPE_BPLIST = "application/x-apple-binary-plist"
        private const val LATENCY_MS = 100
        private const val MAX_FEEDBACK_FAILURES = 3

        /**
         * Opens a session end to end. Blocking; call from a worker thread.
         *
         * [streamKey] is the raw AES key FairPlay wraps; exposed so a test can
         * decrypt what the mock receiver recorded.
         */
        fun open(
            endpoint: Endpoint,
            password: String? = null,
            senderName: String = "AirPlayDroid",
            random: SecureRandom = SecureRandom(),
            responder: FairPlayResponder = FairPlayResponderImpl,
            listener: Listener = Listener { },
            streamKey: ByteArray = ByteArray(16).also { random.nextBytes(it) },
            trace: (String) -> Unit = {},
            keySeed: KeySeed = KeySeed.AUTO,
            deviceId: String = macFrom(random),
        ): LegacyRtspMirrorSession {
            val control = SocketAirPlayConnection(endpoint)
            var timing: LegacyTimingServer? = null
            var data: Socket? = null
            try {
                // ---- 1. The display, as a sender would ask first. Optional:
                // a receiver that does not answer is still worth trying.
                val display = runCatching { readDisplay(control) }.getOrNull()
                trace("GET /info -> display $display")

                // ---- 2. Pairing. A receiver without it answers 404, and then
                // the video key is derived from the FairPlay key alone.
                var cseq = 2
                val pairing = try {
                    LegacyPairVerify(control, cseq = cseq).run().also { cseq = it.nextCseq }
                } catch (e: LegacyPairVerify.Failure.Refused) {
                    trace("pairing skipped: ${e.message}")
                    cseq += 2
                    null
                }
                if (pairing != null) trace("pair-verify ok")

                // ---- 3. FairPlay, on this same connection.
                val fp = FairPlaySapSession(control, responder, random, cseq).handshake()
                cseq += 2
                trace("FairPlay ok (mode ${fp.mode})")
                val receiverSap = FairPlayMessageCipher.decryptBody(fp.mode, fp.challenge)
                val ekey = FairPlayKeyWrap.wrap(receiverSap, fp.localSap, streamKey, random)
                val eiv = ByteArray(16).also { random.nextBytes(it) }

                val requester = Requester(control, password, cseq)
                timing = LegacyTimingServer()

                val sessionId = random.nextLong() and Long.MAX_VALUE
                val controlUri = "rtsp://${endpoint.host}/$sessionId"

                // ---- 4. Keys and timing.
                val keys = requester.request(
                    "SETUP", controlUri,
                    PlistValue.dict(
                        "deviceID" to PlistValue.PString(deviceId),
                        "macAddress" to PlistValue.PString(deviceId),
                        "sessionUUID" to PlistValue.PString(UUID.randomUUID().toString().uppercase()),
                        "sourceVersion" to PlistValue.PString("220.68"),
                        "isScreenMirroringSession" to PlistValue.PBool(true),
                        "timingProtocol" to PlistValue.PString("NTP"),
                        "timingPort" to PlistValue.PInt(timing.port.toLong()),
                        "ekey" to PlistValue.PData(ekey),
                        "eiv" to PlistValue.PData(eiv),
                        "et" to PlistValue.PInt(32),
                        "name" to PlistValue.PString(senderName),
                        "model" to PlistValue.PString("iPhone10,6"),
                        "osName" to PlistValue.PString("iPhone OS"),
                        "osVersion" to PlistValue.PString("12.4"),
                        "osBuildVersion" to PlistValue.PString("16G77"),
                    ),
                )
                trace("SETUP keys -> ${describe(keys)}")
                if (!keys.isSuccess) throw Failure.Refused("SETUP (keys)", keys.status)

                // ---- 5. The screen stream.
                val streamId = random.nextLong() and Long.MAX_VALUE
                val stream = requester.request(
                    "SETUP", controlUri,
                    PlistValue.dict(
                        "streams" to PlistValue.PArray(
                            listOf(
                                PlistValue.dict(
                                    "type" to PlistValue.PInt(110),
                                    "streamConnectionID" to PlistValue.PInt(streamId),
                                    "latencyMs" to PlistValue.PInt(LATENCY_MS.toLong()),
                                    "timestampInfo" to PlistValue.PArray(
                                        listOf("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc").map {
                                            PlistValue.dict("name" to PlistValue.PString(it))
                                        }
                                    ),
                                )
                            )
                        ),
                    ),
                )
                trace("SETUP type 110 -> ${describe(stream)}")
                if (!stream.isSuccess) throw Failure.Refused("SETUP (type 110)", stream.status)
                val dataPort = dataPortOf(stream.body)
                    ?: throw Failure.Malformed("the type-110 SETUP reply carried no dataPort")

                // ---- 6. RECORD. RPiPlay-family receivers answer it; one that
                // refuses is still sent video, since the stream is already set up.
                runCatching {
                    requester.request(
                        "RECORD", controlUri,
                        extra = listOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0"),
                    )
                }.fold(
                    onSuccess = { trace("RECORD -> ${describe(it)}") },
                    onFailure = { trace("RECORD failed: ${it.message}") },
                )

                // ---- 7. The data channel.
                data = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(endpoint.host, dataPort), 5_000)
                }
                val wanted = when (keySeed) {
                    KeySeed.AUTO -> if (advertisesTiming(keys.body) || advertisesTiming(stream.body)) KeySeed.MIXED else KeySeed.RAW
                    else -> keySeed
                }
                val mix = if (wanted == KeySeed.MIXED) pairing?.sharedSecret else null
                val used = if (mix != null) KeySeed.MIXED else KeySeed.RAW
                val (key, iv) = videoKey(streamKey, mix, streamId)
                trace("video key seed: $used (asked for $keySeed); timing on UDP ${timing.port}")
                val video = LegacyVideoStream(
                    data.getOutputStream(),
                    LegacyMirrorCipher(key, iv),
                    unixEpochTimestamps = true,
                    headerStyle = LegacyStreamPackets.HeaderStyle.IOS9,
                )
                return LegacyRtspMirrorSession(
                    control, requester, controlUri, data, video, timing, display,
                    paired = pairing != null, keySeed = used, listener,
                ).also { it.start() }
            } catch (t: Throwable) {
                runCatching { data?.close() }
                runCatching { timing?.close() }
                runCatching { control.close() }
                throw t
            }
        }

        /**
         * The video AES key and IV, as RPiPlay's `mirror_buffer_init_aes` derives
         * them. [ecdhSecret] null means the receiver did not pair.
         */
        fun videoKey(aesKey: ByteArray, mixSecret: ByteArray?, streamConnectionId: Long): Pair<ByteArray, ByteArray> {
            val seed = if (mixSecret != null) sha512(aesKey, mixSecret).copyOf(16) else aesKey
            // %llu: unsigned decimal.
            val id = java.lang.Long.toUnsignedString(streamConnectionId)
            val key = sha512("AirPlayStreamKey$id".toByteArray(), seed).copyOf(16)
            val iv = sha512("AirPlayStreamIV$id".toByteArray(), seed).copyOf(16)
            return key to iv
        }

        private fun readDisplay(control: SocketAirPlayConnection): ReceiverDisplay? {
            val info = control.exchange(
                AirPlayRequest(
                    method = "GET",
                    uri = "/info",
                    protocol = AirPlayRequest.RTSP_1_0,
                    headers = listOf("CSeq" to "1", "User-Agent" to USER_AGENT),
                )
            )
            if (!info.isSuccess) return null
            // Binary from current receivers, XML from older ones.
            val dict = Plists.decode(info.body) as? PlistValue.PDict ?: return null
            val first = (dict["displays"] as? PlistValue.PArray)?.values?.firstOrNull() as? PlistValue.PDict
                ?: return null
            val width = first.int("width") ?: return null
            val height = first.int("height") ?: return null
            return ReceiverDisplay(width, height)
        }

        /** Status, headers and any plist body, for [open]'s trace. */
        private fun describe(response: AirPlayResponse): String {
            val body = if (response.body.isEmpty()) "" else
                runCatching { " " + BinaryPlist.decode(response.body) }.getOrElse { " (${response.body.size} bytes)" }
            return "${response.status} ${response.reason}; ${response.headers}$body"
        }

        /** Whether a SETUP reply names a non-zero `timingPort` of the receiver's own. */
        private fun advertisesTiming(body: ByteArray): Boolean {
            if (body.isEmpty()) return false
            val dict = runCatching { BinaryPlist.decode(body) }.getOrNull() as? PlistValue.PDict ?: return false
            return (dict.int("timingPort") ?: 0) > 0
        }

        private fun dataPortOf(body: ByteArray): Int? {
            val dict = runCatching { BinaryPlist.decode(body) }.getOrNull() as? PlistValue.PDict ?: return null
            val stream = (dict["streams"] as? PlistValue.PArray)?.values?.firstOrNull() as? PlistValue.PDict
            return stream?.int("dataPort")?.takeIf { it > 0 }
        }

        /**
         * A locally administered MAC-style id, `AA:BB:..`, for [open]'s `deviceId`.
         *
         * Pass the same one every session: receivers key what they remember
         * about a sender on it, and iPhoneMirror asks the user to set up every
         * id it has not seen before.
         */
        fun macFrom(random: SecureRandom): String {
            val bytes = ByteArray(6).also { random.nextBytes(it) }
            // Locally administered, unicast.
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }

        private fun sha512(vararg chunks: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            chunks.forEach { digest.update(it) }
            return digest.digest()
        }

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
