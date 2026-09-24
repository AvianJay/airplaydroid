package tw.avianjay.airplaydroid.protocol.devtools

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
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PReal
import tw.avianjay.airplaydroid.protocol.plist.PlistValue.PString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.concurrent.thread

/**
 * End-to-end screen-mirroring probe, with no FairPlay anywhere:
 *
 *   MirrorProbe <host> <port> <credentials-file> <password> [annexb.h264]
 *
 * pair-verify -> encrypted control channel -> control SETUP (PTP) -> event
 * channel -> RECORD -> type-110 SETUP -> data channel. Without a file it stops
 * there; with one it streams the H.264 elementary stream at 30 fps, sealing each
 * frame with the DataStream key derived from the pair-verify secret.
 *
 * The file must be Annex B with an AUD before every access unit, e.g.
 *   ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 20 -c:v libx264
 *     -profile:v baseline -g 30 -bf 0 -x264-params aud=1:repeat-headers=1 -f h264 out.h264
 *
 * Everything the receiver says is printed -- event-channel commands, /feedback
 * statuses, and the moment it closes the data channel -- so the outcome can be
 * judged without looking at the TV.
 */
object MirrorProbe {

    private const val SOURCE_VERSION = "980.71.1"
    private const val LATENCY_MS = 500L
    private const val FPS = 30

    @Volatile private var dataClosedAt: Long = 0
    private val started = System.currentTimeMillis()
    private fun t() = "[%6.2fs]".format((System.currentTimeMillis() - started) / 1000.0)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 4..5) { "usage: MirrorProbe <host> <port> <credentials-file> <password> [annexb.h264]" }
        val host = args[0]
        val port = args[1].toInt()
        val credentials = PairProbe.readCredentials(File(args[2]))
        val password = args[3]
        val video = args.getOrNull(4)?.let { File(it) }

        SocketAirPlayConnection(Endpoint(host, port)).use { control ->
            val session = HomeKitPairing(control, clientId = credentials.clientId).verify(credentials)
            control.enableEncryption(session.controlWriteKey, session.controlReadKey)
            println("${t()} pair-verify OK, control channel encrypted")

            var cseq = 10
            var challenge: DigestAuth.Challenge? = null
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
                            challenge?.let { add("Authorization" to DigestAuth.authorization(it, method, uri, password)) }
                        },
                        body = body?.let { BinaryPlist.encode(it) },
                    )
                )
                var response = send()
                if (response.status == 401) {
                    challenge = DigestAuth.Challenge.parse(response.header("WWW-Authenticate"))
                    if (challenge != null) response = send()
                }
                return response
            }

            val deviceId = "02:AD:D5:00:00:01"
            val sessionUuid = UUID.randomUUID().toString().uppercase()
            val controlUri = "rtsp://$host:$port/${System.nanoTime() and Long.MAX_VALUE}"
            val timingPeer = PDict(
                mapOf(
                    "ID" to PString(UUID.randomUUID().toString().uppercase()),
                    "SupportsClockPortMatchingOverride" to PBool(true),
                    "DeviceType" to PInt(0),
                    "Addresses" to PArray(listOf(PString(localAddressTowards(host)))),
                )
            )

            // --- control SETUP --------------------------------------------------
            val first = request(
                "SETUP", controlUri,
                PDict(
                    mapOf(
                        "deviceID" to PString(deviceId),
                        "macAddress" to PString(deviceId),
                        "sessionUUID" to PString(sessionUuid),
                        "sourceVersion" to PString(SOURCE_VERSION),
                        "isScreenMirroringSession" to PBool(true),
                        "timingProtocol" to PString("PTP"),
                        "timingPeerInfo" to timingPeer,
                        "timingPeerList" to PArray(listOf(timingPeer)),
                        "osBuildVersion" to PString("13F69"),
                        "model" to PString("AirPlayDroid"),
                        "name" to PString("AirPlayDroid"),
                        "updateSessionRequest" to PBool(false),
                        "combinedGetInfoWithControlSetup" to PBool(true),
                    )
                ),
            )
            val firstAt = System.nanoTime()
            println("${t()} control SETUP -> ${first.status}")
            if (!first.isSuccess) return
            val firstBody = BinaryPlist.decode(first.body) as PDict
            val eventPort = (firstBody.entries["eventPort"] as? PInt)?.value?.toInt()
            val peer = firstBody.entries["timingPeerInfo"] as? PDict
            val clockId = (peer?.entries?.get("ClockID") as? PInt)?.value ?: 0L
            val skipRecord = (firstBody.entries["skipRecord"] as? PBool)?.value ?: false

            // The receiver's own clock, in ms, at the moment it handled SETUP.
            val anchorMs = (first.header("X-Apple-RequestReceivedTimestamp")?.toLongOrNull() ?: 0L) +
                (first.header("X-Apple-ProcessingTime")?.toLongOrNull() ?: 0L)
            println("${t()}   eventPort=$eventPort clockId=0x${clockId.toULong().toString(16)} anchor=${anchorMs}ms skipRecord=$skipRecord")
            fun networkTime(): Long =
                compact(anchorMs * 1_000_000 + (System.nanoTime() - firstAt) + LATENCY_MS * 1_000_000)

            // --- event channel --------------------------------------------------
            eventPort?.let { openEventChannel(host, it, session.sharedSecret) }

            // --- RECORD ---------------------------------------------------------
            if (!skipRecord) {
                val record = request(
                    "RECORD", controlUri,
                    extra = listOf("Session" to sessionUuid, "Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0"),
                )
                println("${t()} RECORD -> ${record.status}")
            }

            // --- video SETUP ----------------------------------------------------
            val videoId = (System.nanoTime() + 1) and Long.MAX_VALUE
            val videoSetup = request(
                "SETUP", "rtsp://$host:$port/$videoId",
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
                                        "shk" to PData(session.controlWriteKey.copyOf(16)),
                                        "shiv" to PData(session.controlReadKey.copyOf(16)),
                                    )
                                )
                            )
                        )
                    )
                ),
            )
            println("${t()} video SETUP -> ${videoSetup.status}")
            if (!videoSetup.isSuccess) return
            val streams = (BinaryPlist.decode(videoSetup.body) as PDict).entries["streams"] as PArray
            val dataPort = ((streams.values.first() as PDict).entries["dataPort"] as PInt).value.toInt()

            // --- data channel ---------------------------------------------------
            val data = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, dataPort), 5000)
            }
            println("${t()} data channel connected to :$dataPort")
            thread(isDaemon = true, name = "data-watch") {
                // The receiver never writes on this socket; a read returning means it hung up.
                val n = runCatching { data.getInputStream().read() }
                dataClosedAt = System.currentTimeMillis()
                println("${t()} !!! data channel closed by receiver (read=${n.getOrNull()}, ${n.exceptionOrNull()?.message ?: "EOF"})")
            }
            val out = data.getOutputStream()
            // -Dmirror.wrongKey=true seals frames with a random key instead: the
            // control run that shows whether the receiver reacts to undecryptable video.
            val videoKey = if (System.getProperty("mirror.wrongKey") == "true") {
                println("${t()} CONTROL RUN: sealing frames with a random key")
                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            } else {
                HapCrypto.hkdf("DataStream-Salt$videoId", "DataStream-Output-Encryption-Key", session.sharedSecret)
            }
            val lock = Any()

            // --- keep-alives ----------------------------------------------------
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            thread(isDaemon = true, name = "heartbeat") {
                while (running.get() && dataClosedAt == 0L) {
                    val header = ByteArray(128)
                    header[4] = 0x02; header[6] = 0x1e
                    runCatching { synchronized(lock) { out.write(header); out.flush() } }
                    Thread.sleep(1000)
                }
            }
            val feedback = thread(isDaemon = true, name = "feedback") {
                var n = 0
                while (running.get()) {
                    val r = runCatching { synchronized(control) { request("POST", "/feedback") } }
                    val status = r.getOrNull()?.status
                    if (n++ < 3 || status != 200) println("${t()} /feedback -> ${status ?: r.exceptionOrNull()?.message}")
                    Thread.sleep(2000)
                }
            }

            // --- stream ---------------------------------------------------------
            if (video != null) {
                stream(video, out, lock, videoKey, clockId, ::networkTime)
            } else {
                println("${t()} no video file given; holding the session for 10 s")
                Thread.sleep(10_000)
            }

            println("${t()} holding 5 s after the last frame to watch for a reaction")
            Thread.sleep(5_000)
            running.set(false)
            feedback.join(3000)
            val closedBeforeTeardown = dataClosedAt
            val teardown = runCatching { synchronized(control) { request("TEARDOWN", controlUri) } }
            println("${t()} TEARDOWN -> ${teardown.getOrNull()?.status ?: teardown.exceptionOrNull()?.message}")
            data.close()
            println(
                if (closedBeforeTeardown == 0L) "RESULT: receiver kept the data channel open until our TEARDOWN"
                else "RESULT: receiver closed the data channel on its own at ${(closedBeforeTeardown - started) / 1000.0}s"
            )
        }
    }

    private fun stream(
        file: File,
        out: OutputStream,
        lock: Any,
        key: ByteArray,
        clockId: Long,
        networkTime: () -> Long,
    ) {
        val units = accessUnits(file.readBytes())
        println("${t()} streaming ${units.size} access units from ${file.name}")
        var codecSent: ByteArray? = null
        var nonce = 0L
        val begin = System.nanoTime()

        units.forEachIndexed { index, unit ->
            if (dataClosedAt != 0L) {
                println("${t()} stopping at frame $index: data channel is gone")
                return
            }
            val due = begin + index * 1_000_000_000L / FPS
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())

            val ts = networkTime()
            synchronized(lock) {
                unit.avcC?.let { avcC ->
                    if (!avcC.contentEquals(codecSent)) {
                        out.write(codecHeader(avcC.size, ts, unit.width, unit.height))
                        out.write(avcC)
                        codecSent = avcC
                        println("${t()} codec packet (${avcC.size} bytes, ${unit.width}x${unit.height})")
                    }
                }
                val header = ByteArray(128)
                val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                le.putInt(0, unit.payload.size + 16)
                header[4] = 0x00
                header[5] = if (unit.idr) 0x10 else 0x00
                le.putLong(8, ts)
                le.putLong(40, clockId)
                val nonceBytes = ByteArray(12)
                ByteBuffer.wrap(nonceBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(4, nonce++)
                out.write(header)
                out.write(HapCrypto.seal(key, nonceBytes, unit.payload, header))
                out.flush()
            }
            if (index % (FPS * 5) == 0) println("${t()} frame $index${if (unit.idr) " (IDR)" else ""}")
        }
        println("${t()} sent all ${units.size} frames")
    }

    private fun codecHeader(size: Int, ts: Long, width: Int, height: Int): ByteArray {
        val header = ByteArray(128)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        le.putInt(0, size)
        header[4] = 0x01
        header[6] = 0x16
        header[7] = 0x01
        le.putLong(8, ts)
        for (offset in intArrayOf(16, 40, 56)) {
            le.putFloat(offset, width.toFloat())
            le.putFloat(offset + 4, height.toFloat())
        }
        return header
    }

    private class AccessUnit(val payload: ByteArray, val idr: Boolean, val avcC: ByteArray?, val width: Int, val height: Int)

    /**
     * Splits Annex B at AUD NAL units, converts each unit to AVCC (4-byte
     * big-endian lengths), and lifts SPS/PPS out into an avcC record.
     */
    private fun accessUnits(stream: ByteArray): List<AccessUnit> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i + 3 <= stream.size) {
            val isStart = stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() && stream[i + 2] == 1.toByte()
            if (isStart) {
                if (start >= 0) {
                    var end = i
                    while (end > start && stream[end - 1] == 0.toByte()) end--
                    nals += stream.copyOfRange(start, end)
                }
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0) nals += stream.copyOfRange(start, stream.size)

        val units = mutableListOf<AccessUnit>()
        var current = mutableListOf<ByteArray>()
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        fun flush() {
            if (current.isEmpty()) return
            val vcl = current.filter { (it[0].toInt() and 0x1f) in 1..5 }
            if (vcl.isNotEmpty()) {
                val body = ByteArrayOutputStream()
                vcl.forEach { nal ->
                    body.write(ByteBuffer.allocate(4).putInt(nal.size).array())
                    body.write(nal)
                }
                val idr = vcl.any { (it[0].toInt() and 0x1f) == 5 }
                val s = sps
                val p = pps
                val avcC = if (idr && s != null && p != null) avcC(s, p) else null
                units += AccessUnit(body.toByteArray(), idr, avcC, 1280, 720)
            }
            current = mutableListOf()
        }
        for (nal in nals) {
            when (nal[0].toInt() and 0x1f) {
                9 -> flush()
                7 -> sps = nal
                8 -> pps = nal
                else -> current += nal
            }
        }
        flush()
        return units
    }

    private fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()))
        out.write(byteArrayOf((sps.size ushr 8).toByte(), sps.size.toByte()))
        out.write(sps)
        out.write(1)
        out.write(byteArrayOf((pps.size ushr 8).toByte(), pps.size.toByte()))
        out.write(pps)
        return out.toByteArray()
    }

    /** Nanoseconds to the 32.32 fixed-point seconds the stream header carries. */
    private fun compact(nanos: Long): Long {
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        return (seconds shl 32) or fraction
    }

    /**
     * The receiver's event channel: RTSP requests it sends us, HAP-encrypted with
     * the Events-Salt keys (it writes with Events-Write, we answer with
     * Events-Read). Every request is printed and answered 200.
     */
    private fun openEventChannel(host: String, port: Int, sharedSecret: ByteArray) {
        val readKey = HapCrypto.hkdf("Events-Salt", "Events-Write-Encryption-Key", sharedSecret)
        val writeKey = HapCrypto.hkdf("Events-Salt", "Events-Read-Encryption-Key", sharedSecret)
        val socket = Socket().apply { connect(InetSocketAddress(host, port), 5000) }
        println("${t()} event channel connected to :$port")
        val input = BufferedInputStream(HapFrameInputStream(socket.getInputStream(), readKey))
        val output = HapFrameOutputStream(socket.getOutputStream(), writeKey)
        thread(isDaemon = true, name = "events") {
            try {
                while (true) {
                    val line = readLine(input) ?: break
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val h = readLine(input) ?: break
                        if (h.isEmpty()) break
                        headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length).also { var r = 0; while (r < length) r += input.read(it, r, length - r) }
                    println("${t()} EVENT <- $line")
                    runCatching { BinaryPlist.decode(body) }.getOrNull()?.let { dump(it, "           ") }
                    output.write("RTSP/1.0 200 OK\r\nCSeq: ${headers["cseq"] ?: "0"}\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                }
                println("${t()} event channel closed by receiver")
            } catch (e: Exception) {
                println("${t()} event channel ended: ${e.message}")
            }
        }
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

    private fun localAddressTowards(host: String): String =
        DatagramSocket().use {
            it.connect(InetSocketAddress(host, 9))
            it.localAddress.hostAddress
        }

    private fun dump(value: PlistValue, indent: String) {
        when (value) {
            is PDict -> value.entries.forEach { (k, v) ->
                if (v is PDict || v is PArray) { println("$indent$k:"); dump(v, "$indent  ") } else println("$indent$k = ${scalar(v)}")
            }
            is PArray -> value.values.forEachIndexed { i, v ->
                if (v is PDict || v is PArray) { println("$indent[$i]"); dump(v, "$indent  ") } else println("$indent[$i] ${scalar(v)}")
            }
            else -> println("$indent${scalar(value)}")
        }
    }

    private fun scalar(v: PlistValue): String = when (v) {
        is PString -> "\"${v.value}\""
        is PInt -> v.value.toString()
        is PReal -> v.value.toString()
        is PBool -> v.value.toString()
        is PData -> "<${v.value.size} bytes>"
        else -> v.toString()
    }
}
