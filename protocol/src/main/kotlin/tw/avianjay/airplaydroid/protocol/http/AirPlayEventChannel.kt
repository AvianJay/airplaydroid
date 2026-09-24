package tw.avianjay.airplaydroid.protocol.http

import tw.avianjay.airplaydroid.protocol.Endpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The AirPlay reverse-HTTP event channel.
 *
 * A sender opens a SECOND connection and sends `POST /reverse` with
 * `Upgrade: PTTH/1.0`. The receiver answers `101 Switching Protocols`, after
 * which the roles invert: the receiver issues HTTP requests down this socket to
 * report playback events, and the sender answers them.
 *
 * Why this exists: an Apple TV 4K on tvOS 26.6 accepts `POST /play` with `200`
 * and then answers `GET /playback-info` with `500` in ~2 ms -- the signature of
 * "I have no session by that id" rather than "playback failed". The control
 * request alone does not establish a session; the event channel, opened first
 * and carrying the SAME `X-Apple-Session-ID`, is what registers it.
 *
 * The reader runs on a daemon thread. It answers every inbound request with a
 * bare 200: the receiver only needs the acknowledgement, and nothing downstream
 * consumes the event bodies yet.
 */
class AirPlayEventChannel private constructor(
    private val socket: Socket,
    val sessionId: String,
    /**
     * Called for every inbound event. This is the receiver's own account of
     * what it is doing -- including why playback did not start -- so it is the
     * best diagnostic available when /play succeeds but nothing plays.
     */
    private val onEvent: ((requestLine: String, body: ByteArray) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var running = true

    private val reader = Thread({ pump() }, "airplay-event-channel").apply {
        isDaemon = true
        start()
    }

    private fun pump() {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            while (running && !socket.isClosed) {
                // Inbound request line; null means the peer closed the socket.
                val requestLine = readLine(input) ?: break

                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    val colon = header.indexOf(':')
                    if (colon > 0 &&
                        header.substring(0, colon).trim().equals("Content-Length", true)
                    ) {
                        contentLength = header.substring(colon + 1).trim().toIntOrNull() ?: 0
                    }
                }

                val body = ByteArray(contentLength.coerceAtMost(1 shl 20))
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) return
                    read += n
                }
                runCatching { onEvent?.invoke(requestLine, body) }

                output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
            }
        } catch (_: Throwable) {
            // The channel is best-effort: losing it must not take the session down.
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(b.toChar())
            if (buffer.length > 8192) return null
        }
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
        runCatching { reader.interrupt() }
    }

    companion object {

        /**
         * Opens the channel, answering a Digest challenge when required.
         * Returns null if the receiver declines the upgrade -- callers should
         * carry on, because older receivers do not need it.
         */
        fun open(
            endpoint: Endpoint,
            sessionId: String,
            password: String? = null,
            connectTimeoutMs: Int = SocketAirPlayConnection.DEFAULT_CONNECT_TIMEOUT_MS,
            onEvent: ((requestLine: String, body: ByteArray) -> Unit)? = null,
        ): AirPlayEventChannel? {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                // No read timeout: this connection is idle by design between events.
                socket.soTimeout = 0
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)

                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                fun request(auth: String?) = AirPlayRequest(
                    method = "POST",
                    uri = "/reverse",
                    protocol = AirPlayRequest.HTTP_1_1,
                    headers = buildList {
                        add("Upgrade" to "PTTH/1.0")
                        add("Connection" to "Upgrade")
                        add("X-Apple-Purpose" to "event")
                        add("X-Apple-Session-ID" to sessionId)
                        add("User-Agent" to "AirPlay/380.20.1")
                        add("Content-Length" to "0")
                        auth?.let { add("Authorization" to it) }
                    },
                )

                AirPlayHttp.write(output, request(null))
                var response = AirPlayHttp.readResponse(input)

                if (response.status == 401 && password != null) {
                    val challenge = DigestAuth.Challenge.parse(response.header("WWW-Authenticate"))
                    if (challenge != null) {
                        AirPlayHttp.write(
                            output,
                            request(DigestAuth.authorization(challenge, "POST", "/reverse", password)),
                        )
                        response = AirPlayHttp.readResponse(input)
                    }
                }

                if (response.status != 101) {
                    runCatching { socket.close() }
                    return null
                }
                return AirPlayEventChannel(socket, sessionId, onEvent)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                return null
            }
        }
    }
}
