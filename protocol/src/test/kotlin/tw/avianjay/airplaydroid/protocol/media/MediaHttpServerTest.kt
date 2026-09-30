package tw.avianjay.airplaydroid.protocol.media

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaHttpServerTest {

    private val server = MediaHttpServer()
    private val port = server.start()

    /** A stand-in for an app on the phone serving its own files on loopback. */
    private val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/media/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/media/list/index.m3u8" -> "#EXTM3U\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\n" +
                    "http://127.0.0.1:${address.port}/media/list/seg1.ts\n"
                else -> "0123456789".repeat(10)
            }.toByteArray()
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null) {
                val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                val slice = body.copyOfRange(from, to + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                val type = if (exchange.requestURI.path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4"
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        start()
    }

    @AfterTest
    fun stop() {
        server.close()
        upstream.stop(0)
    }

    private fun get(path: String, range: String? = null): Pair<HttpURLConnection, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        val body = runCatching { connection.inputStream.use { String(it.readBytes()) } }.getOrDefault("")
        return connection to body
    }

    @Test
    fun servesAPublishedHlsStream() {
        val stream = HlsStream(hasVideo = false, hasAudio = true, targetSeconds = 1.0)
        val config = AacConfig(48000, 2)
        for (i in 0 until 200) stream.writeAudio(ByteArray(40), i * 1024L * 1_000_000 / 48000, config)
        stream.finish()
        val path = server.publishHls(stream)

        val (playlist, text) = get(path)
        assertEquals(200, playlist.responseCode)
        assertEquals("application/vnd.apple.mpegurl", playlist.contentType)
        assertTrue("0.ts" in text)

        val (segment, _) = get(path.replace("index.m3u8", "0.ts"))
        assertEquals(200, segment.responseCode)
        assertEquals("video/mp2t", segment.contentType)
        assertTrue(segment.contentLength > 0 && segment.contentLength % 188 == 0)
    }

    @Test
    fun anUnknownTokenIs404() {
        assertEquals(404, get("/hls/00000000000000000000000000000000/index.m3u8").first.responseCode)
        assertEquals(404, get("/proxy/nope/x.mp4").first.responseCode)
    }

    @Test
    fun proxiesALoopbackUrlWithRanges() {
        val path = server.publishProxy("http://127.0.0.1:${upstream.address.port}/media/clip.mp4")
        val (whole, body) = get(path)
        assertEquals(200, whole.responseCode)
        assertEquals(100, body.length)

        val (partial, slice) = get(path, range = "bytes=10-19")
        assertEquals(206, partial.responseCode)
        assertEquals("0123456789", slice)
        assertEquals("bytes 10-19/100", partial.getHeaderField("Content-Range"))
    }

    @Test
    fun rewritesAPlaylistsAbsoluteReferencesThroughTheProxy() {
        val path = server.publishProxy("http://127.0.0.1:${upstream.address.port}/media/list/index.m3u8")
        val (_, text) = get(path)
        val token = path.split('/')[2]
        // The relative one resolves below the token by itself; the absolute one is rewritten.
        assertTrue("\nseg0.ts\n" in text)
        assertTrue("/proxy/$token/seg1.ts" in text, text)
        assertEquals(200, get("/proxy/$token/seg1.ts").first.responseCode)
    }

    @Test
    fun aProxyTokenCannotClimbOutOfItsDirectory() {
        val path = server.publishProxy("http://127.0.0.1:${upstream.address.port}/media/list/index.m3u8")
        val token = path.split('/')[2]
        assertEquals(403, get("/proxy/$token/../other.mp4").first.responseCode)
    }

    @Test
    fun unpublishedPathsStopWorking() {
        val path = server.publishProxy("http://127.0.0.1:${upstream.address.port}/media/clip.mp4")
        server.unpublish(path)
        assertEquals(404, get(path).first.responseCode)
    }
}
