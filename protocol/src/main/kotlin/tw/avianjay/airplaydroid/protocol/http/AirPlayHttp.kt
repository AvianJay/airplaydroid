package tw.avianjay.airplaydroid.protocol.http

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Request/response codec for the AirPlay control channel.
 *
 * Deliberately hand-rolled rather than layered on an HTTP library. The same
 * connection has to carry three dialects over its life: HTTP/1.1 for the
 * video-URL handoff, RTSP/1.0 for audio, and -- after pair-verify -- the same
 * messages wrapped in ChaCha20-Poly1305 frames. Only a codec we own can do that.
 */
data class AirPlayRequest(
    val method: String,
    val uri: String,
    val protocol: String = HTTP_1_1,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        val head = StringBuilder()
        head.append(method).append(' ').append(uri).append(' ').append(protocol).append(CRLF)
        headers.forEach { (name, value) ->
            head.append(name).append(": ").append(value).append(CRLF)
        }
        if (body != null && headers.none { it.first.equals("Content-Length", true) }) {
            head.append("Content-Length: ").append(body.size).append(CRLF)
        }
        head.append(CRLF)
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        body?.let { out.write(it) }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is AirPlayRequest &&
                method == other.method && uri == other.uri && protocol == other.protocol &&
                headers == other.headers &&
                bodyEquals(body, other.body)
            )

    private fun bodyEquals(a: ByteArray?, b: ByteArray?): Boolean =
        if (a == null || b == null) a == null && b == null else a.contentEquals(b)

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val HTTP_1_1 = "HTTP/1.1"
        const val RTSP_1_0 = "RTSP/1.0"
        private const val CRLF = "\r\n"
    }
}

class AirPlayResponse(
    val protocol: String,
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    val isSuccess: Boolean get() = status in 200..299

    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val contentType: String? get() = header("Content-Type")

    override fun toString(): String =
        protocol + " " + status + " " + reason + " (" + body.size + " byte body)"
}

/** Thrown when the peer sends something that is not a parseable response. */
class AirPlayProtocolException(message: String) : Exception(message)

object AirPlayHttp {

    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024

    fun write(out: OutputStream, request: AirPlayRequest) {
        out.write(request.encode())
        out.flush()
    }

    /**
     * Reads one response. Handles both `HTTP/1.1 200 OK` and `RTSP/1.0 200 OK`
     * status lines, and reads the body strictly by Content-Length -- AirPlay
     * receivers do not use chunked transfer encoding.
     */
    fun readResponse(input: InputStream): AirPlayResponse {
        val statusLine = readLine(input)
            ?: throw EOFException("connection closed before a status line arrived")

        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2) throw AirPlayProtocolException("malformed status line: " + statusLine)
        val protocol = parts[0]
        if (!protocol.startsWith("HTTP/") && !protocol.startsWith("RTSP/")) {
            throw AirPlayProtocolException("unexpected protocol token: " + protocol)
        }
        val status = parts[1].toIntOrNull()
            ?: throw AirPlayProtocolException("non-numeric status: " + parts[1])
        val reason = parts.getOrNull(2).orEmpty()

        val headers = mutableListOf<Pair<String, String>>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: throw EOFException("connection closed inside headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                throw AirPlayProtocolException("header block too large")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw AirPlayProtocolException("malformed header line: " + line)
            headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }

        val declared = headers
            .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
            ?.second
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (declared < 0 || declared > MAX_BODY_BYTES) {
            throw AirPlayProtocolException("implausible Content-Length: " + declared)
        }

        val body = ByteArray(declared)
        var read = 0
        while (read < declared) {
            val n = input.read(body, read, declared - read)
            if (n < 0) throw EOFException("connection closed inside body")
            read += n
        }

        return AirPlayResponse(protocol, status, reason, headers, body)
    }

    /** Reads a CRLF- (or bare LF-) terminated line. Returns null at clean EOF. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            buffer.write(b)
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw AirPlayProtocolException("header line too long")
            }
        }
    }
}
