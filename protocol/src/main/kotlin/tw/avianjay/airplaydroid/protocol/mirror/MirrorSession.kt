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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One AirPlay 2 screen-mirroring session, with no FairPlay anywhere.
 *
 * Verified end to end against an Apple TV 4K on tvOS 26.6:
 *
 *  1. pair-verify with stored HomeKit credentials, then the control connection
 *     switches to HAP-encrypted framing;
 *  2. control SETUP with PTP timing (HTTP Digest on top when `flags` bit 7 is set);
 *  3. the receiver's event channel, HAP-encrypted with the Events-Salt keys;
 *  4. RECORD, then a SETUP for a type-110 screen stream, which yields a dataPort;
 *  5. on the data channel: an `avcC` codec packet, then 128-byte-header frames
 *     sealed with ChaCha20-Poly1305 under
 *     HKDF(pair-verify secret, "DataStream-Salt<id>", "DataStream-Output-Encryption-Key"),
 *     the header as AAD. A frame sealed with any other key makes the receiver
 *     drop the data channel within milliseconds.
 *
 * Presentation timestamps are anchored to the receiver's own clock, read from
 * `X-Apple-RequestReceivedTimestamp` on the SETUP reply, so no PTP client runs.
 *
 * Blocking by design. [open] and [close] do network I/O; [sendCodecConfig] and
 * [sendFrame] write to a socket and may block on back-pressure. All are safe to
 * call from one encoder thread while the session's own keep-alive threads run.
 */
class MirrorSession private constructor(
    private val control: SocketAirPlayConnection,
    private val requester: Requester,
    private val controlUri: String,
    private val data: Socket,
    private val events: Socket?,
    private val videoKey: ByteArray,
    private val clockId: Long,
    private val anchorMs: Long,
    private val anchorNanos: Long,
    private val listener: Listener,
) : Closeable {

    /** Called from the session's own threads. */
    fun interface Listener {
        /** The session ended without [close] being called; [reason] says why. */
        fun onEnded(reason: String)
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

    val isOpen: Boolean get() = !closed.get()

    private fun start() {
        thread(isDaemon = true, name = "mirror-data-watch") {
            // The receiver never writes on the data channel; a read that returns
            // means it hung up -- typically because a frame failed to authenticate.
            runCatching { data.getInputStream().read() }
            end("the receiver closed the video connection")
        }
        thread(isDaemon = true, name = "mirror-heartbeat") {
            val header = ByteArray(HEADER_SIZE).also { it[4] = 0x02; it[6] = 0x1e }
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
                val status = runCatching { requester.request("POST", "/feedback").status }.getOrNull()
                failures = if (status == 200) 0 else failures + 1
                if (failures >= MAX_FEEDBACK_FAILURES) {
                    end("the receiver stopped answering (/feedback ${status ?: "failed"})")
                    break
                }
                sleepQuietly(2_000)
            }
        }
    }

    /**
     * Sends a codec packet: the stream's `avcC` record. Must precede the first
     * frame, and be re-sent whenever the SPS/PPS or the picture size change.
     */
    fun sendCodecConfig(avcC: ByteArray, width: Int, height: Int) {
        val header = ByteArray(HEADER_SIZE)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        le.putInt(0, avcC.size)
        header[4] = 0x01 // codec data
        header[6] = 0x16 // H.264 format description
        header[7] = 0x01
        le.putLong(8, nextTimestamp())
        // Encoded size, then source and destination rects covering the whole picture.
        for (offset in intArrayOf(16, 40, 56)) {
            le.putFloat(offset, width.toFloat())
            le.putFloat(offset + 4, height.toFloat())
        }
        write(header, avcC)
    }

    /** Sends one access unit in AVCC form (4-byte big-endian NAL lengths). */
    fun sendFrame(avcc: ByteArray, keyframe: Boolean) {
        val header = ByteArray(HEADER_SIZE)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        le.putInt(0, avcc.size + TAG_SIZE)
        header[4] = 0x00 // video
        header[5] = if (keyframe) 0x10 else 0x00
        le.putLong(8, nextTimestamp())
        le.putLong(40, clockId)
        synchronized(writeLock) {
            val nonceBytes = ByteArray(12)
            ByteBuffer.wrap(nonceBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(4, nonce++)
            write(header, HapCrypto.seal(videoKey, nonceBytes, avcc, header))
        }
    }

    private fun write(header: ByteArray, payload: ByteArray) {
        if (!isOpen) throw IOException("mirroring session is closed")
        synchronized(writeLock) {
            out.write(header)
            out.write(payload)
            out.flush()
        }
    }

    /** Now, plus the playout lead, on the receiver's clock, as 32.32 fixed point; never decreasing. */
    private fun nextTimestamp(): Long = synchronized(writeLock) {
        val nanos = anchorMs * 1_000_000 + (System.nanoTime() - anchorNanos) + LATENCY_MS * 1_000_000
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ts = (seconds shl 32) or fraction
        (if (ts > lastTimestamp) ts else lastTimestamp + 1).also { lastTimestamp = it }
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
                        addAll(extra)
                        val c = challenge
                        if (c != null && password != null) {
                            add("Authorization" to DigestAuth.authorization(c, method, uri, password))
                        }
                    },
                    body = body?.let { BinaryPlist.encode(it) },
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
        private const val SOURCE_VERSION = "980.71.1"
        private const val HEADER_SIZE = 128
        private const val TAG_SIZE = 16
        private const val MAX_FEEDBACK_FAILURES = 3

        /** How far ahead of "now" frames are stamped: the receiver's playout buffer. */
        const val LATENCY_MS = 250L

        /**
         * Pairs with a receiver for the first time. [password] is its AirPlay
         * password (`flags` bit 7), used as the SRP PIN. Persist the result and
         * pass it to [open] from then on.
         */
        fun pair(endpoint: Endpoint, password: String): HomeKitPairing.Credentials =
            SocketAirPlayConnection(endpoint).use { HomeKitPairing(it).pair(password) }

        /**
         * Establishes the session up to an open data channel. Throws
         * [HomeKitPairing.Failure] if the receiver no longer accepts [credentials]
         * (it was reset, or the pairing removed) and [Failure] for anything later.
         */
        fun open(
            endpoint: Endpoint,
            credentials: HomeKitPairing.Credentials,
            password: String?,
            senderName: String,
            listener: Listener,
        ): MirrorSession {
            val control = SocketAirPlayConnection(endpoint)
            var events: Socket? = null
            var data: Socket? = null
            try {
                val pairing = HomeKitPairing(control, clientId = credentials.clientId).verify(credentials)
                control.enableEncryption(pairing.controlWriteKey, pairing.controlReadKey)
                val requester = Requester(control, password)

                val host = endpoint.host
                val controlUri = "rtsp://$host:${endpoint.port}/${randomId()}"
                val deviceId = "02:AD:D5:%02X:%02X:%02X".format(
                    credentials.clientSeed[0], credentials.clientSeed[1], credentials.clientSeed[2],
                )
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
                val anchorNanos = System.nanoTime()
                if (!first.isSuccess) throw Failure.Refused("control SETUP", first.status)
                val setup = BinaryPlist.decode(first.body) as? PDict
                    ?: throw Failure.Malformed("control SETUP reply is not a dictionary")
                val anchorMs = (first.header("X-Apple-RequestReceivedTimestamp")?.toLongOrNull() ?: 0L) +
                    (first.header("X-Apple-ProcessingTime")?.toLongOrNull() ?: 0L)
                val clockId = ((setup.entries["timingPeerInfo"] as? PDict)?.entries?.get("ClockID") as? PInt)?.value ?: 0L

                (setup.entries["eventPort"] as? PInt)?.value?.toInt()?.let { port ->
                    events = openEventChannel(host, port, pairing.sharedSecret)
                }

                if ((setup.entries["skipRecord"] as? PBool)?.value != true) {
                    val record = requester.request(
                        "RECORD", controlUri,
                        extra = listOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0"),
                    )
                    if (!record.isSuccess) throw Failure.Refused("RECORD", record.status)
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
                val dataPort = (((BinaryPlist.decode(video.body) as? PDict)?.entries?.get("streams") as? PArray)
                    ?.values?.firstOrNull() as? PDict)?.entries?.get("dataPort") as? PInt
                    ?: throw Failure.Malformed("screen stream SETUP carried no dataPort")

                data = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, dataPort.value.toInt()), 5_000)
                }

                val videoKey = HapCrypto.hkdf(
                    "DataStream-Salt$videoId", "DataStream-Output-Encryption-Key", pairing.sharedSecret,
                )
                return MirrorSession(
                    control, requester, controlUri, data!!, events, videoKey,
                    clockId, anchorMs, anchorNanos, listener,
                ).also { it.start() }
            } catch (t: Throwable) {
                runCatching { data?.close() }
                runCatching { events?.close() }
                runCatching { control.close() }
                throw t
            }
        }

        private fun randomId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

        /**
         * The receiver's event channel: RTSP requests *from* it, HAP-encrypted
         * with the Events-Salt keys (it writes with Events-Write, we answer with
         * Events-Read). Every request is answered 200; the contents are not
         * needed to keep the stream running.
         */
        private fun openEventChannel(host: String, port: Int, sharedSecret: ByteArray): Socket {
            val readKey = HapCrypto.hkdf("Events-Salt", "Events-Write-Encryption-Key", sharedSecret)
            val writeKey = HapCrypto.hkdf("Events-Salt", "Events-Read-Encryption-Key", sharedSecret)
            val socket = Socket().apply { connect(InetSocketAddress(host, port), 5_000) }
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
                        output.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
            return socket
        }

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
            DatagramSocket().use {
                it.connect(InetSocketAddress(host, 9))
                it.localAddress.hostAddress
            }

        private fun sleepQuietly(ms: Long) {
            try { Thread.sleep(ms) } catch (_: InterruptedException) { }
        }
    }
}
