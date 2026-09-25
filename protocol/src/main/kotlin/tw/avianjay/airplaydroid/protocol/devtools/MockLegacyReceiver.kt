package tw.avianjay.airplaydroid.protocol.devtools

import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayMessageCipher
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayRecords
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A local mock of a **legacy AirPlay 1 receiver**, for testing a sender without
 * a dongle on the network.
 *
 *   MockLegacyReceiver [port]
 *
 * It reproduces the observable behaviour of a real `AppleTV3,2` /
 * `AirTunes/220.68` receiver, captured byte for byte over RTSP:
 *
 * - `GET /info` returns the captured XML plist (2235-byte body).
 * - `POST /fp-setup` with a well-formed m1 returns the captured 142-byte m2.
 * - `POST /fp-setup` with an m3 that fails validation returns the 12-byte
 *   error frame `1e 1e 1e 1e 03 01 04 9c 00 00 00 00` and no m4.
 * - `OPTIONS` returns the receiver's `Public` method list.
 * - Any other path returns the receiver's `404`.
 *
 * ### Why this exists
 *
 * The real device's FairPlay challenge is **static**. Three consecutive
 * sessions returned an identical 142-byte body (md5
 * `d0e6e9db402b52d2f36cb95192a1f316`); only the `Date` header differed. So a
 * fixture can replay it exactly, and a sender can be developed and regression
 * tested offline against real bytes.
 *
 * That static challenge is also *why* this mock cannot be the only test. A
 * receiver that never varies its challenge cannot tell a correct response from
 * a hard-coded one. See [FairPlayRecords] and the note on `validatesM3` below.
 *
 * ### What it does NOT do
 *
 * It does **not** compute the correct 20-byte FairPlay response. That needs the
 * white-box core, which is not vendored here (it is ~500 KB of Apple-derived
 * tables under LGPL-3.0-or-later -- see `docs/fairplay-research.md`).
 *
 * So [SessionPolicy.REJECT_ALL] refuses every m3, and a *successful* mirroring
 * session cannot be reached against that policy alone. Point the sender at the
 * real receiver for that.
 *
 * [SessionPolicy.ACCEPT_FRESH_SESSION] is the useful middle ground, and it needs
 * no key schedule: it accepts an m3 whose local SAP it has not seen before and
 * refuses a byte-identical repeat. That is enough to catch the documented
 * *frozen replay* sender bug offline, which is the failure real receivers report
 * as `466 Key Management Error`.
 */
object MockLegacyReceiver {

    const val DEFAULT_PORT = 5000

    /** Model and version strings the real receiver reports. */
    const val MODEL = "AppleTV3,2"
    const val SOURCE_VERSION = "220.68"
    const val SERVER_HEADER = "AirTunes/$SOURCE_VERSION"
    const val DEVICE_ID = "271F67BA55C9"

    /**
     * Where captured fixtures are read from when running standalone.
     *
     * Tests load them from the classpath. `main` has no test resources on its
     * classpath, so it points this at a directory instead.
     *
     * The captures are deliberately **not** in `src/main/resources`: they are
     * recorded traffic from a third-party device, and `:protocol` is packaged
     * into the app, so putting them there would ship someone else's captures
     * inside the APK.
     */
    @Volatile
    internal var fixturesDir: java.io.File? = null

    private fun fixture(name: String): ByteArray {
        val dir = fixturesDir
        if (dir != null) {
            val file = java.io.File(dir, name)
            require(file.isFile) { "missing fixture $name under ${dir.path}" }
            return file.readBytes()
        }
        return MockLegacyReceiver::class.java.getResourceAsStream("/fairplay/$name")
            ?.use { it.readBytes() }
            ?: error(
                "missing classpath fixture fairplay/$name -- running standalone? " +
                    "pass --fixtures <dir> (the captures live in protocol/src/test/resources/fairplay)"
            )
    }

    /**
     * The captured 142-byte m2, read from the fixture rather than inlined, so
     * the mock and the tests assert against the same bytes the real receiver
     * actually sent.
     *
     * Static on real hardware -- see the class comment.
     */
    private fun m2Record(): ByteArray = fixture("appletv32_fpsetup_m2_body.bin")

    private val PUBLIC_METHODS = listOf(
        "ANNOUNCE", "SETUP", "PLAY", "DESCRIBE", "REDIRECT", "RECORD", "PAUSE",
        "FLUSH", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER", "POST", "GET",
    ).joinToString(", ")

    /** The captured `GET /info` body, read from the fixture. */
    private fun infoBody(): ByteArray = fixture("appletv32_info_body.bin")

    /**
     * Runs the mock standalone.
     *
     *   MockLegacyReceiver [port] [--accept-fresh] [--fixtures <dir>]
     *
     * `--fixtures` defaults to `protocol/src/test/resources/fairplay` relative to
     * the working directory, which is where the captures live in a checkout.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: DEFAULT_PORT
        val policy = if (args.contains("--accept-fresh")) SessionPolicy.ACCEPT_FRESH_SESSION
        else SessionPolicy.REJECT_ALL

        val dirArg = args.indexOf("--fixtures").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        fixturesDir = java.io.File(
            dirArg ?: "protocol/src/test/resources/fairplay"
        ).also {
            require(it.isDirectory) {
                "fixtures directory not found: ${it.absolutePath}\n" +
                    "pass --fixtures <dir> pointing at the captured .bin files"
            }
        }

        val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        println("mock legacy AirPlay 1 receiver ($MODEL / $SERVER_HEADER) on 127.0.0.1:$port")
        println("fixtures: ${fixturesDir!!.absolutePath}")
        println(
            when (policy) {
                SessionPolicy.REJECT_ALL ->
                    "FairPlay: returns the captured 142-byte m2; every m3 is refused, like a wrong key."
                SessionPolicy.ACCEPT_FRESH_SESSION ->
                    "FairPlay: returns the captured 142-byte m2; an m3 with a FRESH local SAP is accepted " +
                        "(m4), a byte-identical replay is refused."
                SessionPolicy.ACCEPT_DECRYPTABLE_BODY ->
                    "FairPlay: returns the captured 142-byte m2; an m3 whose body DECRYPTS to a " +
                        "well-formed local SAP is accepted (m4). Catches an unencrypted m3 body."
            }
        )
        // Shared across connections on purpose: a frozen-replay sender emits the
        // same local SAP on every session, including reconnects.
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                break
            }
            thread(isDaemon = true) { serve(socket, policy, seen, StreamRecorder()) }
        }
    }

    /**
     * How the mock treats an m3.
     *
     * Neither policy needs the white-box FairPlay tables, and that is the point:
     * the tables are a large reverse-engineered dependency, while the *session
     * semantics* they sit behind are what most often breaks a sender in
     * practice.
     */
    enum class SessionPolicy {
        /**
         * Refuse every m3, exactly as the real receiver refused a corrupted one.
         * Proves framing and the failure path; proves nothing about a correct
         * response.
         */
        REJECT_ALL,

        /**
         * Accept an m3 whose local SAP has not been seen before, and refuse a
         * byte-identical repeat.
         *
         * This is the offline test for the documented *frozen replay* bug. A
         * sender that splices in one captured local SAP emits the same m3 body
         * every session, and strict receivers reject that with
         * `RTSP/1.0 466 Key Management Error`. Detecting it needs only a set of
         * previously seen SAPs -- no key schedule.
         */
        ACCEPT_FRESH_SESSION,

        /**
         * Like [ACCEPT_FRESH_SESSION], but also require the m3 body to be a
         * correctly **encrypted** local SAP.
         *
         * A real receiver decrypts bytes 16..144 and folds the result into the
         * response it checks. A sender that writes the local SAP in the clear
         * produces a frame with correct framing, a correct label and a correct
         * response -- and which every receiver rejects, because the body it
         * decrypts is not the SAP the response was computed over.
         *
         * That was a real bug in this project, and nothing offline caught it. This
         * policy is the regression guard: it decrypts the body with
         * [FairPlayMessageCipher] and requires the plaintext to be a well-formed
         * local SAP (`00 01 ...`), which a raw body is not.
         */
        ACCEPT_DECRYPTABLE_BODY,
    }

    /**
     * Records what a sender writes to `/stream` on **one** connection.
     *
     * Deliberately an instance, not a global. An earlier version published into
     * process-wide atomics, which made the end-to-end tests flaky (3 of 5 runs
     * failed): the mock serves each connection on a daemon thread, those threads
     * outlive the test that started them, and a stale thread would set the shared
     * "closed" flag -- so a later test's wait returned immediately with empty or
     * partial bytes.
     *
     * A receiver consumes one stream per session, so per-connection state is also
     * the honest model.
     */
    class StreamRecorder {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var bytes: ByteArray = ByteArray(0)
            private set

        /** True once the sender has closed the stream. */
        @Volatile
        var closed: Boolean = false
            private set

        @Synchronized
        internal fun append(chunk: ByteArray, length: Int) {
            buffer.write(chunk, 0, length)
            bytes = buffer.toByteArray()
        }

        internal fun markClosed() {
            closed = true
        }

        /**
         * Waits up to [timeoutMs] for the sender to close, then returns
         * everything recorded.
         *
         * Waiting for close rather than for non-empty bytes is load-bearing: the
         * stream is a packet sequence with no length prefix, so bytes arriving
         * says nothing about whether the rest have.
         */
        fun awaitClosed(timeoutMs: Long = 5_000): ByteArray {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed && System.currentTimeMillis() < deadline) Thread.sleep(5)
            return bytes
        }
    }

    private fun serve(
        socket: Socket,
        policy: SessionPolicy,
        seenLocalSaps: MutableSet<String>,
        recorder: StreamRecorder,
    ) {
        socket.use { s ->
            s.tcpNoDelay = true
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            while (true) {
                val request = readRequest(input) ?: return
                if (!respond(request, output, policy, seenLocalSaps, input, recorder)) return
            }
        }
    }

    /**
     * Reads whatever the sender writes after `POST /stream`, until it closes.
     *
     * The stream has no length prefix of its own -- it is a sequence of
     * 128-byte-header packets -- so the only end is the peer closing. That is
     * exactly what a real receiver sees.
     */
    private fun drainPackets(input: InputStream, recorder: StreamRecorder) {
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                input.read(chunk)
            } catch (e: java.io.IOException) {
                break
            }
            if (n < 0) break
            recorder.append(chunk, n)
        }
        recorder.markClosed()
    }

    /** The `/stream.xml` body the real receiver family serves. */
    private val STREAM_INFO_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>width</key>
            <integer>1920</integer>
            <key>height</key>
            <integer>1080</integer>
            <key>refreshRate</key>
            <real>60</real>
            <key>overscanned</key>
            <false/>
            <key>version</key>
            <string>220.68</string>
        </dict>
        </plist>
    """.trimIndent()

    /**
     * Serves one connection in [SessionPolicy.REJECT_ALL], for tests that host
     * the mock on an ephemeral port inside the JVM.
     */
    internal fun serveForTest(socket: Socket) =
        serve(socket, SessionPolicy.REJECT_ALL, java.util.concurrent.ConcurrentHashMap.newKeySet(), StreamRecorder())

    /**
     * Serves one connection in [SessionPolicy.ACCEPT_FRESH_SESSION], sharing
     * [seenLocalSaps] so a replay across connections is still caught.
     */
    internal fun serveFreshSessionForTest(socket: Socket, seenLocalSaps: MutableSet<String>) =
        serve(socket, SessionPolicy.ACCEPT_FRESH_SESSION, seenLocalSaps, StreamRecorder())

    /**
     * Serves one connection under an explicit [policy], recording any `/stream`
     * traffic into [recorder].
     *
     * The recorder is passed in rather than held globally: each session owns its
     * own, which is what keeps concurrent or successive tests from observing each
     * other's bytes.
     */
    internal fun serveForTestWithPolicy(
        socket: Socket,
        policy: SessionPolicy,
        seenLocalSaps: MutableSet<String>,
        recorder: StreamRecorder,
    ) = serve(socket, policy, seenLocalSaps, recorder)

    private data class Request(
        val method: String,
        val uri: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    /** Reads one RTSP/HTTP request. Returns null at clean EOF. */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        val length = headers.entries
            .firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Request(
            method = parts[0],
            uri = parts[1],
            protocol = parts.getOrElse(2) { "RTSP/1.0" },
            headers = headers,
            body = body,
        )
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            buffer.write(b)
        }
    }

    /** Writes a response. Returns false when the connection should close. */
    private fun respond(
        request: Request,
        output: OutputStream,
        policy: SessionPolicy,
        seenLocalSaps: MutableSet<String>,
        input: InputStream,
        recorder: StreamRecorder,
    ): Boolean {
        val cseq = request.header("CSeq") ?: "1"
        val protocol = if (request.protocol.startsWith("HTTP/")) "HTTP/1.1" else "RTSP/1.0"

        // Every legacy AirPlay 1 reply carries these.
        val base = mutableListOf(
            "CSeq" to cseq,
            "Server" to SERVER_HEADER,
        )

        when {
            request.uri.startsWith("/fp-setup") -> {
                return respondFairPlay(request, output, protocol, base, policy, seenLocalSaps)
            }

            request.uri.startsWith("/info") -> {
                val body = infoBody()
                writeResponse(output, protocol, 200, "OK", base + listOf(
                    "Content-Type" to "text/x-apple-plist+xml",
                    "Audio-Jack-Status" to "connected; type=digital",
                    "Session" to "DEADBEEF",
                ), body)
                return true
            }

            request.method == "OPTIONS" -> {
                writeResponse(output, protocol, 200, "OK", base + listOf(
                    "Public" to PUBLIC_METHODS,
                    "Audio-Jack-Status" to "connected; type=digital",
                ), ByteArray(0))
                return true
            }

            // --- the port-7100 mirroring endpoint ---
            request.uri.startsWith("/stream.xml") -> {
                writeResponse(output, protocol, 200, "OK", base + listOf(
                    "Content-Type" to "text/x-apple-plist+xml",
                ), STREAM_INFO_XML.toByteArray())
                return true
            }

            request.uri.startsWith("/stream") -> {
                // The reply is a plist; after it the socket carries raw packets,
                // so this mock records what it receives until the peer closes.
                writeResponse(output, protocol, 200, "OK", base + listOf(
                    "Content-Type" to "application/x-apple-binary-plist",
                ), ByteArray(0))
                drainPackets(input, recorder)
                return false
            }

            else -> {
                // The real device answers unknown paths on this port with 404.
                writeResponse(output, protocol, 404, "Not Found", base + listOf(
                    "Content-Type" to "text/plain",
                    "Connection" to "close",
                ), "Not Found".toByteArray())
                return true
            }
        }
    }

    /**
     * Decrypts an m3 body the way a receiver does, or null if it is not
     * decryptable.
     *
     * A receiver treats the 128 bytes at m3[16:144] as the mode's message cipher
     * output and decrypts them to recover the sender's local SAP. A sender that
     * writes the SAP in the clear produces a body that decrypts to noise, which
     * is why such an m3 is rejected.
     */
    private fun decryptM3Body(mode: FairPlayRecords.Mode, body: ByteArray): ByteArray? =
        runCatching { FairPlayMessageCipher.decryptBody(mode, body) }.getOrNull()

    private fun respondFairPlay(
        request: Request,
        output: OutputStream,
        protocol: String,
        base: List<Pair<String, String>>,
        policy: SessionPolicy,
        seenLocalSaps: MutableSet<String>,
    ): Boolean {
        val headers = base + listOf(
            "X-Apple-ET" to "null",
            "Content-Type" to "application/octet-stream",
        )

        when (request.body.size) {
            FairPlayRecords.M1_BYTES -> {
                // m1 -> the captured m2.
                writeResponse(output, protocol, 200, "OK", headers, m2Record())
                return true
            }

            FairPlayRecords.M3_BYTES -> {
                val m3 = FairPlayRecords.parseM3(request.body).getOrNull()
                val accepted = when (policy) {
                    SessionPolicy.REJECT_ALL -> false

                    SessionPolicy.ACCEPT_FRESH_SESSION -> {
                        // A well-formed m3 carrying a local SAP this receiver has
                        // not seen before. A frozen-replay sender fails here on
                        // every session after its first, which is the behaviour
                        // real receivers report as `466 Key Management Error`.
                        m3 != null && seenLocalSaps.add(m3.body.toHex())
                    }

                    SessionPolicy.ACCEPT_DECRYPTABLE_BODY -> {
                        // A real receiver decrypts the body and uses the plaintext
                        // as the SAP. Require that to be possible and well-formed.
                        val sap = m3?.let { decryptM3Body(it.mode, it.body) }
                        sap != null &&
                            sap[0] == 0x00.toByte() &&
                            sap[1] == 0x01.toByte() &&
                            seenLocalSaps.add(sap.toHex())
                    }
                }
                if (accepted) {
                    writeResponse(output, protocol, 200, "OK", headers, FairPlayRecords.m4(m3!!.response))
                } else {
                    // The real receiver's refusal, captured verbatim. Note the
                    // 200 status: the refusal is in the *body*, not the status.
                    writeResponse(output, protocol, 200, "OK", headers, errorFrame())
                }
                return true
            }

            else -> {
                writeResponse(output, protocol, 200, "OK", headers, errorFrame())
                return true
            }
        }
    }

    /** The captured 12-byte refusal frame. */
    private fun errorFrame(): ByteArray =
        MockLegacyReceiver::class.java.getResourceAsStream("/fairplay/appletv32_m3_rejection_frame.bin")
            ?.use { it.readBytes() }
            ?: error("missing test resource fairplay/appletv32_m3_rejection_frame.bin")

    private fun writeResponse(
        output: OutputStream,
        protocol: String,
        status: Int,
        reason: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ) {
        val head = StringBuilder()
        head.append(protocol).append(' ').append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
