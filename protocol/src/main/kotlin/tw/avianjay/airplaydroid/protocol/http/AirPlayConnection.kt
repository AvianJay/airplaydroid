package tw.avianjay.airplaydroid.protocol.http

import tw.avianjay.airplaydroid.protocol.Endpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One control connection to a receiver. Blocking by design -- callers run it on
 * an IO dispatcher. Kept as an interface so the protocol layer can be unit
 * tested against a scripted peer with no sockets involved.
 */
interface AirPlayConnection : Closeable {
    fun exchange(request: AirPlayRequest): AirPlayResponse
}

class SocketAirPlayConnection private constructor(
    private val socket: Socket,
) : AirPlayConnection {

    private var input: InputStream = BufferedInputStream(socket.getInputStream())
    private var output: OutputStream = BufferedOutputStream(socket.getOutputStream())

    /**
     * Switches this connection to the HAP-encrypted framing that follows a
     * successful pair-verify. Everything after this call, in both directions,
     * is ChaCha20-Poly1305 framed; the RTSP text inside is unchanged.
     *
     * The existing buffered streams are wrapped rather than the raw socket ones,
     * so nothing already buffered is lost. (The receiver sends nothing unasked,
     * so in practice that buffer is empty here.)
     */
    @Synchronized
    fun enableEncryption(writeKey: ByteArray, readKey: ByteArray) {
        output = HapFrameOutputStream(output, writeKey)
        input = BufferedInputStream(HapFrameInputStream(input, readKey))
    }

    /**
     * Set once a request/response exchange fails part way through. The stream
     * position is then unknown -- a subsequent exchange would read the tail of
     * the previous response as its status line and report nonsense. Reusing a
     * connection after a read failure is never safe, so we latch it closed.
     */
    private var poisoned = false

    /**
     * Writes bytes with no HTTP framing.
     *
     * Needed by the legacy mirroring path: after `POST /stream` the same socket
     * stops being an HTTP connection and carries the raw packetised video
     * stream. Flushes, because these writes are the media itself and buffering
     * them would add latency to every frame.
     */
    @Synchronized
    fun writeRaw(bytes: ByteArray) {
        if (poisoned) throw IOException("connection is no longer usable after an earlier failure")
        try {
            output.write(bytes)
            output.flush()
        } catch (t: Throwable) {
            poisoned = true
            close()
            throw t
        }
    }

    /** Writes a request without reading a reply. Used to open the legacy stream. */
    @Synchronized
    fun write(request: AirPlayRequest) {
        if (poisoned) throw IOException("connection is no longer usable after an earlier failure")
        try {
            AirPlayHttp.write(output, request)
        } catch (t: Throwable) {
            poisoned = true
            close()
            throw t
        }
    }

    @Synchronized
    override fun exchange(request: AirPlayRequest): AirPlayResponse {
        if (poisoned) throw IOException("connection is no longer usable after an earlier failure")

        try {
            AirPlayHttp.write(output, request)
            return AirPlayHttp.readResponse(input)
        } catch (t: Throwable) {
            poisoned = true
            close()
            throw t
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000

        /**
         * Connects, or throws without leaking the socket. Building the streams in
         * a property initialiser would leak the descriptor if getInputStream()
         * threw, because the constructor never completes and nothing holds a
         * reference to close.
         */
        operator fun invoke(
            endpoint: Endpoint,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        ): SocketAirPlayConnection {
            val socket = Socket()
            try {
                // Nagle would delay these small control messages behind each other.
                socket.tcpNoDelay = true
                socket.soTimeout = readTimeoutMs
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
                return SocketAirPlayConnection(socket)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }
}
