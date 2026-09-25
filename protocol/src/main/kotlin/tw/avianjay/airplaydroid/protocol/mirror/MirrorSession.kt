package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.http.DigestAuth
import tw.avianjay.airplaydroid.protocol.http.HapFrameInputStream
import tw.avianjay.airplaydroid.protocol.http.HapFrameOutputStream
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.pairing.HapCrypto
import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PArray
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PBool
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PData
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PDict
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PInt
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One AirPlay 2 screen-mirroring session, with no FairPlay anywhere.
 *
 * Verified end to end against an Apple TV 4K on tvOS 26.6:
 *
 *  1. pair-verify with stored HomeKit credentials -- or, for a receiver with no
 *     password, transient pair-setup on the control connection itself ([Access]) --
 *     then the control connection switches to HAP-encrypted framing;
 *  2. control SETUP with PTP timing (HTTP Digest on top when `flags` bit 7 is set);
 *  3. the receiver's event channel, HAP-encrypted with the Events-Salt keys,
 *     which also reports the receiver's display box and supported formats;
 *  4. RECORD; optionally a SETUP for a type-96 screen-audio stream
 *     ([ScreenAudioStream]); then a SETUP for a type-110 screen stream, which
 *     yields a dataPort;
 *  5. on the data channel: an `avcC` codec packet, then 128-byte-header frames
 *     sealed with ChaCha20-Poly1305 under
 *     HKDF(pair-verify secret, "DataStream-Salt<id>", "DataStream-Output-Encryption-Key"),
 *     the header as AAD. A frame sealed with any other key makes the receiver
 *     drop the data channel within milliseconds.
 *
 * Presentation timestamps are capture time plus [LATENCY_MS] on the receiver's
 * own clock ([ReceiverClock]), so no PTP client runs, and audio and video that
 * were captured together present together.
 *
 * Blocking by design. [open] and [close] do network I/O; the send methods write
 * to sockets and may block on back-pressure. Video and audio may be fed from
 * two different threads while the session's own keep-alive threads run.
 */
class MirrorSession private constructor(
    private val control: SocketAirPlayConnection,
    private val requester: Requester,
    private val controlUri: String,
    private val data: Socket,
    private val events: EventChannel?,
    private val audio: ScreenAudioStream?,
    private val videoKey: ByteArray,
    private val clock: ReceiverClock,
    /** What the receiver's display is, per its updateInfo event, if it said. */
    val receiverDisplay: Display?,
    /** Why screen audio is not running, if it was asked for and is not. */
    val audioFailure: String?,
    private val listener: Listener,
) : VideoStreamSink, Closeable {

    /** Called from the session's own threads. */
    fun interface Listener {
        /** The session ended without [close] being called; [reason] says why. */
        fun onEnded(reason: String)
    }

    data class Display(val width: Int, val height: Int)

    /** How a session authenticates to the receiver. */
    sealed class Access {
        /** A stored persistent pairing (password or PIN receivers): pair-verify. */
        class Paired(val credentials: HomeKitPairing.Credentials) : Access()

        /**
         * Transient pairing, for a receiver with no password or PIN (`flags` bits
         * 3, 7 and 9 clear): pair-setup M1-M4 with the fixed SRP password
         * [HomeKitPairing.TRANSIENT_PASSWORD] on the control connection, and no
         * pair-verify. The 64-byte SRP session key then keys every channel, as
         * owntone, pyatv and the receivers (shairport-sync, airplay2-receiver)
         * do. [clientId] should be stable per install.
         */
        class Transient(val clientId: String) : Access()
    }

    sealed class Failure(message: String) : IOException(message) {
        class Refused(val step: String, val status: Int) :
            Failure("$step refused with $status" + if (status == 401) " (wrong or missing password)" else "")

        class Malformed(val detail: String) : Failure("malformed response: $detail")
    }

    private val out: OutputStream = data.getOutputStream()
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private var nonce = 0L
    private var lastTimestamp = 0L
    private var pendingConfig: ByteArray? = null
    @Volatile private var firstFrameSent = false

    val isOpen: Boolean get() = !closed.get()

    /** True when screen audio was negotiated and [sendAudio] reaches the receiver. */
    val hasAudio: Boolean get() = audio != null

    /** How many packet retransmissions the receiver has asked for; a sign it is receiving audio. */
    val audioRetransmitRequests: Int get() = audio?.retransmitRequests?.get() ?: 0

    private fun start() {
        thread(isDaemon = true, name = "mirror-data-watch") {
            // The receiver never writes on the data channel; a read that returns
            // means it hung up -- typically because a frame failed to authenticate.
            runCatching { data.getInputStream().read() }
            end("the receiver closed the video connection")
        }
        thread(isDaemon = true, name = "mirror-heartbeat") {
            val header = ByteArray(HEADER_SIZE).also { it[4] = 0x02; it[6] = 0x1e }
            // Real senders start heartbeats only once video flows; a heartbeat
            // ahead of the first codec packet is a shape receivers never see.
            while (isOpen && !firstFrameSent) sleepQuietly(50)
            while (isOpen) {
                try {
                    synchronized(writeLock) { out.write(header); out.flush() }
                } catch (e: IOException) {
                    end("video connection failed: ${e.message}")
                    break
                }
                sleepQuietly(1_000)
            }
        }
        thread(isDaemon = true, name = "mirror-feedback") {
            var failures = 0
            while (isOpen) {
                val response = runCatching { requester.request("POST", "/feedback") }.getOrNull()
                val receivedAt = System.nanoTime()
                if (response?.status == 200) {
                    failures = 0
                    ReceiverClock.receiverMsOf(response::header)?.let { clock.observe(it, receivedAt) }
                } else {
                    failures++
                }
                if (failures >= MAX_FEEDBACK_FAILURES) {
                    end("the receiver stopped answering (/feedback ${response?.status ?: "failed"})")
                    break
                }
                sleepQuietly(2_000)
            }
        }
    }

    /**
     * Sets the stream's `avcC` record. It goes out as a codec packet just before
     * the next keyframe, stamped with that keyframe's timestamp -- receivers pair
     * the two by timestamp. Set it again whenever the SPS/PPS change.
     */
    override fun setCodecConfig(avcC: ByteArray, width: Int, height: Int) {
        val header = ByteArray(HEADER_SIZE)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        le.putInt(0, avcC.size)
        header[4] = 0x01 // codec data
        header[6] = 0x16 // H.264 format description
        header[7] = 0x01
        // Encoded size, then source and destination rects covering the whole picture.
        for (offset in intArrayOf(16, 40, 56)) {
            le.putFloat(offset, width.toFloat())
            le.putFloat(offset + 4, height.toFloat())
        }
        synchronized(writeLock) { pendingConfig = header + avcC }
    }

    /**
     * Sends one access unit in AVCC form (4-byte big-endian NAL lengths),
     * captured at local monotonic time [captureNanos] (for a MediaCodec surface
     * encoder: `presentationTimeUs * 1000`).
     */
    override fun sendFrame(avcc: ByteArray, keyframe: Boolean, captureNanos: Long) {
        val header = ByteArray(HEADER_SIZE)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        le.putInt(0, avcc.size + TAG_SIZE)
        header[4] = 0x00 // video
        header[5] = if (keyframe) 0x10 else 0x00
        le.putLong(40, clockId)
        synchronized(writeLock) {
            if (!isOpen) throw IOException("mirroring session is closed")
            val ts = timestampFor(captureNanos)
            le.putLong(8, ts)
            val config = pendingConfig
            if (keyframe && config != null) {
                ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN).putLong(8, ts)
                out.write(config)
                pendingConfig = null
            }
            val nonceBytes = ByteArray(12)
            ByteBuffer.wrap(nonceBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(4, nonce++)
            out.write(header)
            out.write(HapCrypto.seal(videoKey, nonceBytes, avcc, header))
            out.flush()
            firstFrameSent = true
        }
    }

    /**
     * Sends one screen-audio frame: exactly 352 interleaved stereo 16-bit
     * samples at 44.1 kHz, the first captured at local monotonic time
     * [captureNanos]. A no-op when the session has no audio.
     */
    fun sendAudio(pcm: ShortArray, captureNanos: Long) {
        if (isOpen) audio?.send(pcm, captureNanos)
    }

    /** Test hook: consumes an audio frame's sequence number without sending it. */
    internal fun withholdAudio(pcm: ShortArray, captureNanos: Long) {
        if (isOpen) audio?.send(pcm, captureNanos, transmit = false)
    }

    /** Capture time plus the playout lead, on the receiver's clock, as 32.32; never decreasing. */
    private fun timestampFor(captureNanos: Long): Long {
        val ts = ReceiverClock.toFixed32(clock.receiverNanos(captureNanos) + LATENCY_MS * 1_000_000)
        return maxOf(ts, lastTimestamp).also { lastTimestamp = it }
    }

    private val clockId: Long get() = events?.clockId ?: 0L

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
        runCatching { audio?.close() }
        runCatching { data.close() }
        runCatching { events?.close() }
        runCatching { control.close() }
    }

    /** Serialises control requests and answers the Digest challenge once one has been seen. */
    private class Requester(
        private val control: SocketAirPlayConnection,
        private val password: String?,
    ) {
        private var cseq = 10
        private var challenge: DigestAuth.Challenge? = null

        @Synchronized
        fun request(
            method: String,
            uri: String,
            body: PDict? = null,
            extra: List<Pair<String, String>> = emptyList(),
            text: String? = null,
        ): AirPlayResponse {
            fun send() = control.exchange(
                AirPlayRequest(
                    method = method,
                    uri = uri,
                    protocol = AirPlayRequest.RTSP_1_0,
                    headers = buildList {
                        add("CSeq" to (cseq++).toString())
                        add("User-Agent" to "AirPlay/$SOURCE_VERSION")
                        if (body != null) add("Content-Type" to "application/x-apple-binary-plist")
                        if (text != null) add("Content-Type" to "text/parameters")
                        addAll(extra)
                        val c = challenge
                        if (c != null && password != null) {
                            add("Authorization" to DigestAuth.authorization(c, method, uri, password))
                        }
                    },
                    body = body?.let { BinaryPlist.encode(it) } ?: text?.toByteArray(),
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

    /**
     * The receiver's event channel: RTSP requests *from* it, HAP-encrypted with
     * the Events-Salt keys (it writes with Events-Write, we answer with
     * Events-Read). Every request is answered 200. Two commands matter:
     * `updateInfo` carries the display box and supported formats, and
     * `updateTimingPeerInfo` moves the stream to a new PTP ClockID.
     */
    private class EventChannel(host: String, port: Int, sharedSecret: ByteArray, initialClockId: Long) : Closeable {
        private val socket = Socket().apply { connect(InetSocketAddress(host, port), 5_000) }
        private val infoArrived = CountDownLatch(1)

        @Volatile var clockId: Long = initialClockId
        @Volatile var display: Display? = null
        @Volatile var screenStreamFormats: Long? = null

        init {
            val readKey = HapCrypto.hkdf("Events-Salt", "Events-Write-Encryption-Key", sharedSecret)
            val writeKey = HapCrypto.hkdf("Events-Salt", "Events-Read-Encryption-Key", sharedSecret)
            val input = BufferedInputStream(HapFrameInputStream(socket.getInputStream(), readKey))
            val output = HapFrameOutputStream(socket.getOutputStream(), writeKey)
            thread(isDaemon = true, name = "mirror-events") {
                runCatching {
                    while (true) {
                        readLine(input) ?: break
                        var cseq = "0"
                        var length = 0
                        while (true) {
                            val header = readLine(input) ?: return@runCatching
                            if (header.isEmpty()) break
                            val name = header.substringBefore(':').trim()
                            val value = header.substringAfter(':').trim()
                            if (name.equals("CSeq", true)) cseq = value
                            if (name.equals("Content-Length", true)) length = value.toIntOrNull() ?: 0
                        }
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) return@runCatching
                            read += n
                        }
                        runCatching { handle(BinaryPlist.decode(body)) }
                        output.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
        }

        private fun handle(command: PlistValue) {
            val dict = command as? PDict ?: return
            val value = dict.entries["value"] as? PDict ?: return
            when ((dict.entries["type"] as? PString)?.value) {
                "updateInfo" -> {
                    val first = ((value.entries["displays"] as? PArray)?.values?.firstOrNull() as? PDict)?.entries
                    val w = (first?.get("widthPixels") as? PInt)?.value?.toInt()
                    val h = (first?.get("heightPixels") as? PInt)?.value?.toInt()
                    if (w != null && h != null && w > 0 && h > 0) display = Display(w, h)
                    val formats = (value.entries["supportedFormats"] as? PDict)?.entries
                    screenStreamFormats = (formats?.get("screenStream") as? PInt)?.value
                    infoArrived.countDown()
                }
                "updateTimingPeerInfo" ->
                    (value.entries["ClockID"] as? PInt)?.value?.takeIf { it != 0L }?.let { clockId = it }
            }
        }

        /** Waits up to [ms] for the first updateInfo. */
        fun awaitInfo(ms: Long) = infoArrived.await(ms, TimeUnit.MILLISECONDS)

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val SOURCE_VERSION = "980.71.1"
        private const val HEADER_SIZE = 128
        private const val TAG_SIZE = 16
        private const val MAX_FEEDBACK_FAILURES = 3

        /** Feature bit 59: the receiver takes `streamConnections` in audio stream descriptors. */
        private const val FEATURE_STREAM_CONNECTIONS = 59

        /** `supportedFormats.screenStream` bit 18: ALAC 44100/16/2. */
        private const val FORMAT_ALAC_44100_16_2 = 0x40000L

        /**
         * How far behind capture a frame or sample is presented: the receiver's
         * playout buffer. Covers encoding plus the network; audio and video share it.
         */
        const val LATENCY_MS = 250L

        private val LATENCY_SAMPLES = (LATENCY_MS * ScreenAudioStream.SAMPLE_RATE / 1000).toInt()

        /**
         * Pairs with a receiver for the first time. [password] is its AirPlay
         * password (`flags` bit 7), used as the SRP PIN. Persist the result and
         * pass it to [open] from then on.
         */
        fun pair(endpoint: Endpoint, password: String): HomeKitPairing.Credentials =
            SocketAirPlayConnection(endpoint).use { HomeKitPairing(it).pair(password) }

        /**
         * Pairs with a receiver in PIN mode (`flags` bit 9 or 3): asks it to show a
         * one-time code, then blocks in [awaitPin] until the user has read it off
         * the screen. Returns null if [awaitPin] does (the user gave up). The code
         * is used once; persist the credentials and [open] with them afterwards.
         * Verified on an Apple TV 4K (tvOS 26.6) with Allow Access set but no
         * password, where every new device must enter the code once.
         */
        fun pairWithPin(endpoint: Endpoint, awaitPin: () -> String?): HomeKitPairing.Credentials? =
            // Held open while the user types: pin-start and pair-setup must share it.
            SocketAirPlayConnection(endpoint, readTimeoutMs = 30_000).use { connection ->
                val pairing = HomeKitPairing(connection)
                pairing.startPin()
                val pin = awaitPin() ?: return null
                pairing.pair(pin)
            }

        /**
         * Establishes the session up to an open data channel. Throws
         * [HomeKitPairing.Failure] if the receiver no longer accepts [credentials]
         * (it was reset, or the pairing removed) and [Failure] for anything later.
         *
         * With [withAudio], a screen-audio stream is set up as well. Audio is
         * best-effort: if the receiver refuses it, the session still opens,
         * video-only, and [audioFailure] says why.
         *
         * [features] are the receiver's advertised feature bits (the `features`
         * TXT value); they pick the audio descriptor layout.
         */
        fun open(
            endpoint: Endpoint,
            credentials: HomeKitPairing.Credentials,
            password: String?,
            senderName: String,
            withAudio: Boolean = false,
            features: ULong = 0uL,
            listener: Listener,
        ): MirrorSession = open(endpoint, Access.Paired(credentials), password, senderName, withAudio, features, listener)

        /**
         * As above, authenticating per [access]. A transient session throws
         * [HomeKitPairing.Failure.Refused] with 470 when the receiver wants a
         * password or PIN after all.
         */
        fun open(
            endpoint: Endpoint,
            access: Access,
            password: String?,
            senderName: String,
            withAudio: Boolean = false,
            features: ULong = 0uL,
            listener: Listener,
        ): MirrorSession {
            val control = SocketAirPlayConnection(endpoint)
            var events: EventChannel? = null
            var audio: ScreenAudioStream? = null
            var data: Socket? = null
            try {
                val pairing = when (access) {
                    is Access.Paired ->
                        HomeKitPairing(control, clientId = access.credentials.clientId).verify(access.credentials)
                    // M1-M4 on this very connection: the receiver switches it to
                    // encrypted framing right after its plaintext M4.
                    is Access.Transient -> HomeKitPairing.Session(
                        HomeKitPairing(control, clientId = access.clientId)
                            .pairSetup(HomeKitPairing.Mode.TRANSIENT, HomeKitPairing.TRANSIENT_PASSWORD)
                    )
                }
                control.enableEncryption(pairing.controlWriteKey, pairing.controlReadKey)
                val requester = Requester(control, password)

                val host = endpoint.host
                // The control URI's id doubles as the audio stream's
                // streamConnectionID; RECORD, SET_PARAMETER and TEARDOWN use it too.
                val controlId = randomId()
                val controlUri = "rtsp://$host:${endpoint.port}/$controlId"
                // A stable per-sender id: from the pairing key, or hashed from the client id.
                val idSeed = when (access) {
                    is Access.Paired -> access.credentials.clientSeed
                    is Access.Transient -> MessageDigest.getInstance("SHA-256").digest(access.clientId.toByteArray())
                }
                val deviceId = "02:AD:D5:%02X:%02X:%02X".format(idSeed[0], idSeed[1], idSeed[2])
                val timingPeer = PDict(
                    mapOf(
                        "ID" to PString(UUID.randomUUID().toString().uppercase()),
                        "SupportsClockPortMatchingOverride" to PBool(true),
                        "DeviceType" to PInt(0),
                        "Addresses" to PArray(listOf(PString(localAddressTowards(host)))),
                    )
                )
                val first = requester.request(
                    "SETUP", controlUri,
                    PDict(
                        mapOf(
                            "deviceID" to PString(deviceId),
                            "macAddress" to PString(deviceId),
                            "sessionUUID" to PString(UUID.randomUUID().toString().uppercase()),
                            "sourceVersion" to PString(SOURCE_VERSION),
                            "isScreenMirroringSession" to PBool(true),
                            // PTP, not NTP: with NTP the receiver probes our timing
                            // port before it answers, and we run no timing server.
                            "timingProtocol" to PString("PTP"),
                            "timingPeerInfo" to timingPeer,
                            "timingPeerList" to PArray(listOf(timingPeer)),
                            "osBuildVersion" to PString("13F69"),
                            "model" to PString("AirPlayDroid"),
                            "name" to PString(senderName),
                            "updateSessionRequest" to PBool(false),
                            "combinedGetInfoWithControlSetup" to PBool(true),
                        )
                    ),
                )
                val firstAt = System.nanoTime()
                if (!first.isSuccess) throw Failure.Refused("control SETUP", first.status)
                val setup = BinaryPlist.decode(first.body) as? PDict
                    ?: throw Failure.Malformed("control SETUP reply is not a dictionary")
                val clock = ReceiverClock(ReceiverClock.receiverMsOf(first::header) ?: 0L, firstAt)
                // Every later reply is another clock sample; the least delayed one wins.
                fun observe(reply: AirPlayResponse) =
                    ReceiverClock.receiverMsOf(reply::header)?.let { clock.observe(it, System.nanoTime()) }
                val clockId = ((setup.entries["timingPeerInfo"] as? PDict)?.entries?.get("ClockID") as? PInt)?.value ?: 0L

                (setup.entries["eventPort"] as? PInt)?.value?.toInt()?.takeIf { it > 0 }?.let { port ->
                    events = EventChannel(host, port, pairing.sharedSecret, clockId)
                }

                if ((setup.entries["skipRecord"] as? PBool)?.value != true) {
                    val record = requester.request(
                        "RECORD", controlUri,
                        extra = listOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0"),
                    )
                    if (!record.isSuccess) throw Failure.Refused("RECORD", record.status)
                    observe(record)
                }
                // The display box and formats arrive in updateInfo, right after RECORD.
                events?.awaitInfo(1_500)

                var audioFailure: String? = null
                if (withAudio) {
                    val formats = events?.screenStreamFormats
                    if (formats != null && formats and FORMAT_ALAC_44100_16_2 == 0L) {
                        audioFailure = "the receiver does not accept ALAC screen audio"
                    } else {
                        try {
                            audio = setupAudio(
                                requester, controlUri, controlId, InetAddress.getByName(host), clock,
                                { events?.clockId ?: clockId },
                                modernLayout = features and (1uL shl FEATURE_STREAM_CONNECTIONS) != 0uL,
                            )
                        } catch (e: IOException) {
                            audioFailure = e.message ?: "audio setup failed"
                        }
                    }
                }

                val videoId = randomId()
                val video = requester.request(
                    "SETUP", "rtsp://$host:${endpoint.port}/$videoId",
                    PDict(
                        mapOf(
                            "streams" to PArray(
                                listOf(
                                    PDict(
                                        mapOf(
                                            "type" to PInt(110),
                                            "streamConnectionID" to PInt(videoId),
                                            "latencyMs" to PInt(LATENCY_MS),
                                            "timestampInfo" to PArray(
                                                listOf("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc").map {
                                                    PDict(mapOf("name" to PString(it)))
                                                }
                                            ),
                                            "shk" to PData(pairing.controlWriteKey.copyOf(16)),
                                            "shiv" to PData(pairing.controlReadKey.copyOf(16)),
                                        )
                                    )
                                )
                            )
                        )
                    ),
                )
                if (!video.isSuccess) throw Failure.Refused("screen stream SETUP", video.status)
                observe(video)
                val dataPort = (((BinaryPlist.decode(video.body) as? PDict)?.entries?.get("streams") as? PArray)
                    ?.values?.firstOrNull() as? PDict)?.entries?.get("dataPort") as? PInt
                    ?: throw Failure.Malformed("screen stream SETUP carried no dataPort")

                data = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, dataPort.value.toInt()), 5_000)
                }

                if (audio != null) {
                    // 0 dB, i.e. full scale. Real senders send it twice. A refusal
                    // is harmless, but an exception is not caught: it means the
                    // control connection is gone, and the session with it.
                    repeat(2) { observe(requester.request("SET_PARAMETER", controlUri, text = "volume: 0.000000\r\n")) }
                }

                val videoKey = HapCrypto.hkdf(
                    "DataStream-Salt$videoId", "DataStream-Output-Encryption-Key", pairing.sharedSecret,
                )
                return MirrorSession(
                    control, requester, controlUri, data!!, events, audio, videoKey,
                    clock, events?.display, audioFailure, listener,
                ).also { it.start() }
            } catch (t: Throwable) {
                runCatching { data?.close() }
                runCatching { audio?.close() }
                runCatching { events?.close() }
                runCatching { control.close() }
                throw t
            }
        }

        /**
         * SETUP for the type-96 screen-audio stream, on the control URI. Tries the
         * layout the features suggest and, if the receiver rejects the shape, the
         * other one once.
         */
        private fun setupAudio(
            requester: Requester,
            controlUri: String,
            streamConnectionId: Long,
            host: InetAddress,
            clock: ReceiverClock,
            clockId: () -> Long,
            modernLayout: Boolean,
        ): ScreenAudioStream {
            val controlSocket = DatagramSocket()
            try {
                val key = ScreenAudioStream.newKey()
                var modern = modernLayout
                var response = requester.request("SETUP", controlUri, audioDescriptor(streamConnectionId, key, controlSocket.localPort, modern))
                if (response.status in SHAPE_REJECTED) {
                    modern = !modern
                    response = requester.request("SETUP", controlUri, audioDescriptor(streamConnectionId, key, controlSocket.localPort, modern))
                }
                if (!response.isSuccess) throw Failure.Refused("screen audio SETUP", response.status)

                val stream = (((BinaryPlist.decode(response.body) as? PDict)?.entries?.get("streams") as? PArray)
                    ?.values?.firstOrNull() as? PDict)?.entries
                    ?: throw Failure.Malformed("screen audio SETUP carried no stream")
                var dataPort = (stream["dataPort"] as? PInt)?.value?.toInt()
                var remoteControlPort = (stream["controlPort"] as? PInt)?.value?.toInt()
                (stream["streamConnections"] as? PDict)?.entries?.let { connections ->
                    fun port(type: String) = ((connections[type] as? PDict)?.entries?.get("streamConnectionKeyPort") as? PInt)?.value?.toInt()
                    port("streamConnectionTypeRTP")?.let { dataPort = it }
                    port("streamConnectionTypeRTCP")?.let { remoteControlPort = it }
                }
                if (dataPort == null || remoteControlPort == null) {
                    throw Failure.Malformed("screen audio SETUP carried no ports")
                }
                return ScreenAudioStream(host, dataPort!!, remoteControlPort!!, controlSocket, key, clock, clockId, LATENCY_SAMPLES)
            } catch (t: Throwable) {
                controlSocket.close()
                throw t
            }
        }

        private val SHAPE_REJECTED = setOf(400, 406, 415, 455, 500, 501)

        private fun audioDescriptor(id: Long, key: ByteArray, localControlPort: Int, modern: Boolean): PDict {
            val stream = linkedMapOf<String, PlistValue>(
                "type" to PInt(96),
                "streamConnectionID" to PInt(id),
                "ct" to PInt(2), // ALAC
                "spf" to PInt(AlacVerbatim.SAMPLES_PER_FRAME.toLong()),
                "sr" to PInt(ScreenAudioStream.SAMPLE_RATE),
                "audioFormat" to PInt(FORMAT_ALAC_44100_16_2),
                "audioMode" to PString("default"),
                "usingScreen" to PBool(true),
                // The lead is announced once, as latencyMax; a nonzero minimum
                // makes the receiver add it a second time.
                "latencyMin" to PInt(0),
                "latencyMax" to PInt(LATENCY_SAMPLES.toLong()),
                "shk" to PData(key),
            )
            if (modern) {
                stream["isMedia"] = PBool(false)
                stream["supportsDynamicStreamID"] = PBool(true)
                stream["streamConnections"] = PDict(
                    mapOf(
                        "streamConnectionTypeRTP" to PDict(mapOf("streamConnectionKeyUseStreamEncryptionKey" to PBool(true))),
                        "streamConnectionTypeRTCP" to PDict(mapOf("streamConnectionKeyPort" to PInt(localControlPort.toLong()))),
                    )
                )
            } else {
                stream["controlPort"] = PInt(localControlPort.toLong())
            }
            return PDict(mapOf("streams" to PArray(listOf(PDict(stream)))))
        }

        private fun randomId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
                if (b == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
                buffer.write(b)
            }
        }

        /** The local address the OS would use to reach [host], for the PTP peer entry. */
        private fun localAddressTowards(host: String): String =
            java.net.DatagramSocket().use {
                it.connect(InetSocketAddress(host, 9))
                it.localAddress.hostAddress
            }

        private fun sleepQuietly(ms: Long) {
            try { Thread.sleep(ms) } catch (_: InterruptedException) { }
        }
    }
}
