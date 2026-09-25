package tw.avianjay.airplaydroid.protocol.mirror

import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.http.DigestAuth
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import tw.avianjay.airplaydroid.protocol.plist.Plists
import java.io.Closeable

/**
 * The legacy (AirPlay 1) mirroring control surface: the port-7100 HTTP endpoint
 * a receiver exposes for screen mirroring.
 *
 * This is a different protocol from the AirPlay 2 mirroring in [MirrorSession],
 * not a variant of it. A legacy receiver has:
 *
 * ```
 * GET  /stream.xml   -> a plist describing the display
 * POST /stream       -> start; the body is a plist, then the socket carries the
 *                       raw packetised video stream (no longer HTTP)
 * ```
 *
 * ### What a caller has to supply
 *
 * `/stream` carries the FairPlay-wrapped AES key (`param1`) and its IV
 * (`param2`). Producing `param1` needs the FairPlay session, so this class takes
 * it as a parameter rather than deriving it: the two are separate concerns, and
 * keeping them apart is what lets this be tested without the crypto.
 *
 * ### The port is not 7000
 *
 * Legacy mirroring listens on its own port, conventionally 7100. The dongle this
 * was developed against puts RTSP on 5000 and answers `/stream.xml` on **7100**.
 * The caller passes whichever the device advertises; nothing here assumes a
 * value.
 */
class LegacyMirrorSession(
    private val endpoint: Endpoint,
    /**
     * The receiver's AirPlay password, when it has one.
     *
     * A password-protected AirPlay 1 receiver answers an unauthenticated request
     * with `401` plus a `WWW-Authenticate: Digest realm="AirPlay"` challenge, and
     * expects the standard Digest response. This is **not** SRP pairing: the nto
     * spec's "Password Protection" section is explicit that AirPlay 1 passwords
     * are plain HTTP Digest, realm `AirPlay`, username `AirPlay`.
     *
     * Null means no password, which is the common case for a dongle.
     */
    private val password: String? = null,
    private val connectTimeoutMs: Int = SocketAirPlayConnection.DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = SocketAirPlayConnection.DEFAULT_READ_TIMEOUT_MS,
) : Closeable {

    /** The display description from `GET /stream.xml`. */
    data class StreamInfo(
        val width: Int,
        val height: Int,
        val refreshRate: Double,
        val overscanned: Boolean,
        val version: String?,
    ) {
        /** The pixel count, used to cap the encoder canvas. */
        val pixels: Int get() = width * height
    }

    /**
     * Everything `POST /stream` needs.
     *
     * [param1] is the 72-byte FairPlay `FPLY` record wrapping the stream AES key;
     * [param2] is its 16-byte IV. Both are produced by the FairPlay session.
     */
    data class StreamRequest(
        val deviceId: Long,
        val sessionId: Int,
        val version: String,
        val param1: ByteArray,
        val param2: ByteArray,
        val latencyMs: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is StreamRequest && deviceId == other.deviceId &&
                    sessionId == other.sessionId && version == other.version &&
                    latencyMs == other.latencyMs &&
                    param1.contentEquals(other.param1) && param2.contentEquals(other.param2)
                )

        override fun hashCode(): Int {
            var r = deviceId.hashCode()
            r = 31 * r + sessionId
            r = 31 * r + version.hashCode()
            r = 31 * r + latencyMs
            r = 31 * r + param1.contentHashCode()
            r = 31 * r + param2.contentHashCode()
            return r
        }
    }

    /** A receiver that would not answer the mirroring endpoint. */
    class Failure(message: String) : Exception(message)

    /**
     * `GET /stream.xml` -- the receiver's display description.
     *
     * Worth calling before opening the stream: the answer sets the encoder
     * canvas, and a mismatch there shows up as a stretched or cropped picture
     * rather than as an error.
     */
    fun streamInfo(): StreamInfo {
        val body = exchange("GET", "/stream.xml").let { response ->
            if (!response.isSuccess) {
                throw Failure("GET /stream.xml -> ${response.status} ${response.reason}")
            }
            response.body
        }

        val plist = runCatching { Plists.decode(body) }.getOrElse {
            throw Failure("GET /stream.xml returned a body that is not a plist: ${it.message}")
        }
        val dict = plist as? PlistValue.PDict
            ?: throw Failure("GET /stream.xml did not return a dictionary")

        return StreamInfo(
            width = dict.int("width") ?: throw Failure("/stream.xml has no width"),
            height = dict.int("height") ?: throw Failure("/stream.xml has no height"),
            refreshRate = dict.number("refreshRate") ?: DEFAULT_REFRESH_RATE,
            overscanned = dict.bool("overscanned") ?: false,
            version = dict.string("version"),
        )
    }

    /**
     * `POST /stream` -- starts the stream.
     *
     * Returns an [OpenStream] that owns the socket. The caller writes packets to
     * it with [LegacyStreamPackets] and closes it to stop.
     *
     * The connection is **not** returned to a pool: after this call the socket is
     * no longer speaking HTTP, so it cannot be reused for control requests.
     */
    fun startStream(request: StreamRequest): OpenStream {
        val body = BinaryPlist.encode(
            PlistValue.PDict(
                linkedMapOf(
                    "deviceID" to PlistValue.PInt(request.deviceId),
                    "sessionID" to PlistValue.PInt(request.sessionId.toLong()),
                    "version" to PlistValue.PString(request.version),
                    "param1" to PlistValue.PData(request.param1),
                    "param2" to PlistValue.PData(request.param2),
                    "latencyMs" to PlistValue.PInt(request.latencyMs.toLong()),
                )
            )
        )

        val connection = SocketAirPlayConnection(endpoint, connectTimeoutMs, readTimeoutMs)
        try {
            if (password == null) {
                // No password: write and go. The receiver does not answer before
                // the stream starts, and reading here would block.
                connection.write(
                    AirPlayRequest(
                        method = "POST",
                        uri = "/stream",
                        protocol = AirPlayRequest.HTTP_1_1,
                        headers = listOf(
                            "User-Agent" to USER_AGENT,
                            "Content-Type" to CONTENT_TYPE_BPLIST,
                        ),
                        body = body,
                    )
                )
            } else {
                // With a password the receiver may answer `401` first, and that
                // answer has to be read before the socket can become a stream.
                val response = exchangeWithDigest(
                    connection,
                    method = "POST",
                    uri = "/stream",
                    body = body,
                    contentType = CONTENT_TYPE_BPLIST,
                )
                if (!response.isSuccess) {
                    throw Failure("POST /stream -> ${response.status} ${response.reason}")
                }
            }
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw Failure("POST /stream failed: ${t.message}")
        }
        return OpenStream(connection)
    }

    /** A started stream. Writing packets is the caller's job. */
    class OpenStream internal constructor(
        private val connection: SocketAirPlayConnection,
    ) : Closeable {
        fun write(bytes: ByteArray) = connection.writeRaw(bytes)

        /**
         * The socket as a plain stream, for [LegacyVideoStream].
         *
         * Exposed so the packet writer does not need to know how the socket was
         * opened. Writes go straight out -- after `POST /stream` this is no longer
         * an HTTP connection, so there is no framing to add.
         */
        fun outputStream(): java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) =
                connection.writeRaw(b.copyOfRange(off, off + len))
            override fun write(b: ByteArray) = connection.writeRaw(b)
            override fun flush() = Unit
            override fun close() = connection.close()
        }

        override fun close() = connection.close()
    }

    override fun close() = Unit

    /**
     * One request, retried once with Digest if the receiver asks for it.
     *
     * A password-protected AirPlay 1 receiver answers the first request with
     * `401` and a `WWW-Authenticate: Digest realm="AirPlay"` challenge. The
     * standard response is then sent on the **same connection**, which is why
     * this is a single `use` block rather than two calls.
     *
     * When [password] is null the challenge is not answered, and the 401 surfaces
     * to the caller -- which is correct: silently retrying without credentials
     * would just fail twice.
     */
    private fun exchangeWithDigest(
        connection: SocketAirPlayConnection,
        method: String,
        uri: String,
        body: ByteArray?,
        contentType: String? = null,
    ): AirPlayResponse {
        fun request(authorization: String?) = AirPlayRequest(
            method = method,
            uri = uri,
            protocol = AirPlayRequest.HTTP_1_1,
            headers = buildList {
                add("User-Agent" to USER_AGENT)
                contentType?.let { add("Content-Type" to it) }
                authorization?.let { add("Authorization" to it) }
            },
            body = body,
        )

        val first = connection.exchange(request(null))
        if (first.status != HTTP_UNAUTHORIZED || password == null) return first

        val challenge = DigestAuth.Challenge.parse(first.header("WWW-Authenticate"))
            ?: return first
        val authorization = DigestAuth.authorization(
            challenge,
            method,
            uri,
            password,
            username = DIGEST_USERNAME,
        )
        return connection.exchange(request(authorization))
    }

    private fun exchange(method: String, uri: String) =
        SocketAirPlayConnection(endpoint, connectTimeoutMs, readTimeoutMs).use { connection ->
            exchangeWithDigest(connection, method, uri, body = null)
        }

    companion object {
        /**
         * The Digest username a legacy receiver expects.
         *
         * The nto spec's Password Protection table lists realm `AirPlay` and
         * username `AirPlay` for the AirPlay service (the AirTunes service uses
         * realm `raop`, username `iTunes`). A receiver derives HA1 from whatever
         * we send, so this must match what it stored.
         */
        const val DIGEST_USERNAME = "AirPlay"

        /** The status that carries a Digest challenge. */
        const val HTTP_UNAUTHORIZED = 401

        /** The version a legacy receiver reports; matches the captures. */
        const val USER_AGENT = "AirPlay/220.68"

        const val CONTENT_TYPE_BPLIST = "application/x-apple-binary-plist"

        /** The spec's example display runs at 60 Hz; used when the plist omits it. */
        const val DEFAULT_REFRESH_RATE = 60.0

        /** The conventional legacy mirroring port. */
        const val DEFAULT_PORT = 7100
    }
}
