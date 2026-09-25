package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.devtools.MockLegacyReceiver
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LegacyMirrorSessionFactory.findRtspEndpoint] against the port layout AirScreen
 * 2.15.1 was measured with on 2026-09-25: `_airplay._tcp` on 57000 accepts and
 * closes without a byte, 7000 answers RTSP with HTTP, and only the `_raop._tcp`
 * port, 5000, is the RTSP server.
 */
class LegacyEndpointProbeTest {

    /** Serves every connection on [server] with [handle], until the test closes it. */
    private fun serve(server: ServerSocket, handle: (java.net.Socket) -> Unit) = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            thread(isDaemon = true) { runCatching { handle(socket) } }
        }
    }

    private fun silentCloser() = ServerSocket(0).also { server -> serve(server) { it.close() } }

    private fun httpServer() = ServerSocket(0).also { server ->
        serve(server) { socket ->
            socket.use {
                it.getInputStream().read(ByteArray(1024))
                it.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
    }

    private fun rtspReceiver() = ServerSocket(0).also { server -> serve(server) { MockLegacyReceiver.serveForTest(it) } }

    private fun ServerSocket.endpoint() = Endpoint("127.0.0.1", localPort)

    @Test
    fun `the RTSP port is found behind a port that closes silently and one that speaks HTTP`() {
        val closer = silentCloser()
        val http = httpServer()
        val rtsp = rtspReceiver()
        try {
            assertEquals(
                rtsp.endpoint(),
                LegacyMirrorSessionFactory.findRtspEndpoint(listOf(closer.endpoint(), http.endpoint(), rtsp.endpoint())),
            )
        } finally {
            listOf(closer, http, rtsp).forEach { it.close() }
        }
    }

    @Test
    fun `when both answer RTSP the first candidate wins`() {
        val first = rtspReceiver()
        val second = rtspReceiver()
        try {
            assertEquals(
                first.endpoint(),
                LegacyMirrorSessionFactory.findRtspEndpoint(listOf(first.endpoint(), second.endpoint())),
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `nothing that speaks RTSP gives null`() {
        val closer = silentCloser()
        val refused = ServerSocket(0).also { it.close() }
        try {
            assertNull(LegacyMirrorSessionFactory.findRtspEndpoint(listOf(closer.endpoint(), refused.endpoint()), timeoutMs = 1_000))
        } finally {
            closer.close()
        }
    }
}
