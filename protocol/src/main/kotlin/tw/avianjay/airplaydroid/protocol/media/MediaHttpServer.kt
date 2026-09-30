package tw.avianjay.airplaydroid.protocol.media

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The phone's own media server: what an AirPlay receiver fetches when the URL
 * a Cast sender asked for cannot be handed over as it is.
 *
 * It serves two kinds of path, each under an unguessable token:
 *
 *  - `/hls/<token>/index.m3u8` and its segments: an [HlsStream] being
 *    converted on the phone.
 *  - `/proxy/<token>/...`: a pass-through to a URL only this phone can reach,
 *    typically an app on the phone serving its own files on `127.0.0.1`. Range
 *    requests are forwarded, so the receiver can seek.
 *
 * It must listen on the network, because the receiver is another device; the
 * token is what keeps anyone else out. Only what was published is served, and
 * a proxy token reaches only the directory of the URL it was made for.
 *
 * HTTP/1.1 with keep-alive, one thread per connection. Receivers open one or
 * two connections per stream, so nothing more elaborate is called for.
 */
class MediaHttpServer(
    private val log: (String) -> Unit = {},
) : Closeable {

    private sealed interface Published {
        class Hls(val stream: HlsStream) : Published
        /** [base] ends in `/`; a request's path below the token is appended to it. */
        class Proxy(val base: String) : Published
    }

    private val published = ConcurrentHashMap<String, Published>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null
    @Volatile private var closed = false
    private val random = SecureRandom()

    val port: Int get() = server?.localPort ?: -1

    fun start(port: Int = 0): Int {
        check(server == null) { "already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        server = socket
        thread(name = "media-accept", isDaemon = true) {
            while (!closed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                sockets += client
                thread(name = "media-client", isDaemon = true) { serve(client) }
            }
        }
        return socket.localPort
    }

    /** Publishes [stream]; returns its playlist path. */
    fun publishHls(stream: HlsStream): String {
        val token = newToken()
        published[token] = Published.Hls(stream)
        return "/hls/$token/index.m3u8"
    }

    /**
     * Publishes a pass-through to [upstream], an http URL. Returns the path that
     * fetches it; relative references inside it (an HLS playlist's segments)
     * resolve below the same token.
     */
    fun publishProxy(upstream: String): String {
        val uri = URI(upstream)
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val directory = path.substringBeforeLast('/') + "/"
        val file = path.substringAfterLast('/') + (uri.rawQuery?.let { "?$it" } ?: "")
        val base = URI(uri.scheme, uri.rawAuthority, null, null, null).toString() + directory
        val token = newToken()
        published[token] = Published.Proxy(base)
        return "/proxy/$token/$file"
    }

    /** Stops serving the path [publishHls] or [publishProxy] returned. */
    fun unpublish(path: String) {
        tokenOf(path)?.let(published::remove)
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        published.clear()
    }

    // ------------------------------------------------------------ connection

    private class Request(val method: String, val target: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
        val keepAlive: Boolean get() = header("connection")?.equals("close", ignoreCase = true) != true
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (!closed) {
                val request = readRequest(input) ?: break
                val keepOpen = handle(request, output) && request.keepAlive
                output.flush()
                if (!keepOpen) break
            }
        } catch (_: SocketTimeoutException) {
            // An idle keep-alive connection; the receiver opens a new one when it needs to.
        } catch (e: IOException) {
            if (!closed) log("media client ${socket.inetAddress?.hostAddress}: $e")
        } finally {
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
        }
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    /** One CRLF-terminated line, or null at end of stream. Bounded, so a hostile client cannot exhaust memory. */
    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length >= MAX_LINE) throw IOException("request line too long")
            out.append(c.toChar())
        }
    }

    /** Returns whether the connection may be kept open. */
    private fun handle(request: Request, output: OutputStream): Boolean {
        if (request.method != "GET" && request.method != "HEAD") {
            return respond(output, request, 405, "Method Not Allowed")
        }
        val path = request.target.substringBefore('?')
        val segments = path.trim('/').split('/')
        val route = segments.getOrNull(0)
        val token = segments.getOrNull(1)
        val target = token?.let(published::get)
        return when {
            route == "hls" && target is Published.Hls -> serveHls(request, output, target.stream, segments.drop(2).joinToString("/"))
            route == "proxy" && target is Published.Proxy -> serveProxy(request, output, target, token)
            else -> respond(output, request, 404, "Not Found")
        }
    }

    private fun serveHls(request: Request, output: OutputStream, stream: HlsStream, name: String): Boolean {
        if (name == "index.m3u8") {
            val playlist = stream.playlist(PLAYLIST_WAIT_MS)
                ?: return respond(output, request, 503, "Service Unavailable", retryAfter = true)
            return respond(
                output, request, 200, "OK",
                body = playlist.toByteArray(Charsets.UTF_8),
                contentType = "application/vnd.apple.mpegurl",
                extra = listOf("Cache-Control" to "no-cache"),
            )
        }
        val sequence = name.removeSuffix(".ts").toIntOrNull()
            ?: return respond(output, request, 404, "Not Found")
        val segment = stream.segment(sequence, SEGMENT_WAIT_MS)
            ?: return respond(output, request, 404, "Not Found")
        return respond(output, request, 200, "OK", body = segment.bytes, contentType = "video/mp2t")
    }

    private fun serveProxy(request: Request, output: OutputStream, proxy: Published.Proxy, token: String): Boolean {
        val rest = request.target.substringAfter("/proxy/$token/", "")
        // The token reaches its own directory and below, nothing else on that host.
        if (rest.split('?')[0].split('/').any { it == ".." }) return respond(output, request, 403, "Forbidden")
        val upstream = URL(proxy.base + rest)
        val connection = (upstream.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = UPSTREAM_TIMEOUT_MS
            readTimeout = UPSTREAM_TIMEOUT_MS
            instanceFollowRedirects = true
            listOf("range", "user-agent", "accept", "if-range").forEach { name ->
                request.header(name)?.let { setRequestProperty(name, it) }
            }
        }
        try {
            val status = connection.responseCode
            val type = connection.contentType
            val body = if (status >= 400) connection.errorStream else connection.inputStream
            val isPlaylist = type?.contains("mpegurl", ignoreCase = true) == true ||
                upstream.path.endsWith(".m3u8", ignoreCase = true)
            if (isPlaylist && request.method == "GET" && status == 200) {
                // Absolute references back to the same origin would bypass the
                // proxy, and the receiver cannot reach that origin itself.
                val text = body?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val rewritten = text.lines().joinToString("\n") { line ->
                    if (line.startsWith(proxy.base)) "/proxy/$token/" + line.removePrefix(proxy.base) else line
                }
                return respond(output, request, 200, "OK", body = rewritten.toByteArray(Charsets.UTF_8), contentType = type)
            }
            val headers = ArrayList<Pair<String, String>>()
            listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")
                .forEach { name -> connection.getHeaderField(name)?.let { headers += name to it } }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
            writeHead(output, status, connection.responseMessage ?: "", headers, keepAlive = length != null)
            if (request.method == "GET") body?.use { it.copyTo(output, COPY_BUFFER) }
            // Without a length, the end of the body is the end of the connection.
            return length != null
        } catch (e: IOException) {
            log("proxy $upstream: $e")
            return respond(output, request, 502, "Bad Gateway")
        } finally {
            connection.disconnect()
        }
    }

    private fun respond(
        output: OutputStream,
        request: Request,
        status: Int,
        reason: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        extra: List<Pair<String, String>> = emptyList(),
        retryAfter: Boolean = false,
    ): Boolean {
        val headers = ArrayList<Pair<String, String>>()
        contentType?.let { headers += "Content-Type" to it }
        headers += "Content-Length" to body.size.toString()
        if (retryAfter) headers += "Retry-After" to "1"
        headers += extra
        writeHead(output, status, reason, headers, keepAlive = true)
        if (request.method == "GET") output.write(body)
        return true
    }

    private fun writeHead(output: OutputStream, status: Int, reason: String, headers: List<Pair<String, String>>, keepAlive: Boolean) {
        val head = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        head.append("Server: AirPlayDroid\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun newToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun tokenOf(path: String): String? = path.trim('/').split('/').getOrNull(1)

    private companion object {
        const val MAX_LINE = 8 * 1024
        const val IDLE_TIMEOUT_MS = 30_000
        const val UPSTREAM_TIMEOUT_MS = 15_000
        /** Long enough for a phone to convert the first segment, short enough for a player not to give up. */
        const val PLAYLIST_WAIT_MS = 20_000L
        const val SEGMENT_WAIT_MS = 20_000L
        const val COPY_BUFFER = 64 * 1024
    }
}
