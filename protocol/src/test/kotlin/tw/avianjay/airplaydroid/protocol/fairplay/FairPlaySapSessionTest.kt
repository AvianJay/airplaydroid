package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.devtools.MockLegacyReceiver
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sender-side SAP handshake, driven against [MockLegacyReceiver] over a real
 * socket.
 *
 * The responder is a stub on purpose. This suite verifies the **session** -- the
 * four-message exchange, per-session freshness, and how a refusal surfaces --
 * none of which needs the white-box crypto. The crypto core plugs in behind
 * [FairPlayResponder] and is verified separately; see `docs/fairplay-research.md`.
 */
class FairPlaySapSessionTest {

    private val stubResponder = FairPlayResponder { _, _ -> ByteArray(20) { 0x11 } }

    /** Runs [body] against a mock that accepts any local SAP it has not seen. */
    private fun withFreshSessionMock(body: (port: Int, seen: MutableSet<String>) -> Unit) {
        val server = ServerSocket(0)
        val seen = ConcurrentHashMap.newKeySet<String>()
        try {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    thread(isDaemon = true) {
                        MockLegacyReceiver.serveFreshSessionForTest(socket, seen)
                    }
                }
            }
            body(server.localPort, seen)
        } finally {
            server.close()
        }
    }

    /** Runs [body] against a mock that refuses every m3, like a wrong key. */
    private fun withRefusingMock(body: (port: Int) -> Unit) {
        val server = ServerSocket(0)
        try {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    thread(isDaemon = true) { MockLegacyReceiver.serveForTest(socket) }
                }
            }
            body(server.localPort)
        } finally {
            server.close()
        }
    }

    /** A receiver that answers m1 with something that is not an m2 at all. */
    private fun withGarbageReceiver(body: (port: Int) -> Unit) {
        val server = ServerSocket(0)
        try {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket: Socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    thread(isDaemon = true) {
                        socket.use { s ->
                            // Drain the request, then answer 404 with no body.
                            runCatching { s.getInputStream().read(ByteArray(4096)) }
                            s.getOutputStream().write(
                                "RTSP/1.0 404 Not Found\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n"
                                    .toByteArray()
                            )
                            s.getOutputStream().flush()
                        }
                    }
                }
            }
            body(server.localPort)
        } finally {
            server.close()
        }
    }

    @Test
    fun `completes the four-message exchange against a receiver that accepts a fresh session`() {
        withFreshSessionMock { port, _ ->
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                val result = FairPlaySapSession(connection, stubResponder).handshake()

                assertEquals(FairPlayRecords.Mode.MODE_3, result.mode, "the capture is a mode-3 receiver")
                assertEquals(128, result.challenge.size)
                assertEquals(128, result.localSap.size)
                assertEquals(20, result.response.size)
            }
        }
    }

    @Test
    fun `each handshake uses a fresh local SAP`() {
        // The anti-replay property. A sender that reuses one local SAP emits a
        // byte-identical m3 every session, which strict receivers reject with
        // `466 Key Management Error`.
        withFreshSessionMock { port, seen ->
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                val session = FairPlaySapSession(connection, stubResponder)
                val first = session.handshake()
                val second = session.handshake()

                assertNotEquals(
                    first.localSap.toHex(),
                    second.localSap.toHex(),
                    "a second handshake must not reuse the first local SAP",
                )
                assertEquals(2, seen.size, "both sessions should have been distinct to the receiver")
            }
        }
    }

    @Test
    fun `a refused response surfaces the receiver's status code`() {
        withRefusingMock { port ->
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                val failure = assertFailsWith<FairPlaySapSession.Failure.Rejected> {
                    FairPlaySapSession(connection, stubResponder).handshake()
                }
                // The real receiver's refusal byte, observed on hardware.
                assertEquals(FairPlayRecords.ERROR_STATUS_M3_REJECTED, failure.status)
            }
        }
    }

    @Test
    fun `a refusal under HTTP 200 is still detected`() {
        // The trap this guards: the refusal rides in the BODY under a 200 status.
        // A sender that only checks the status line believes it succeeded.
        withRefusingMock { port ->
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                assertFailsWith<FairPlaySapSession.Failure.Rejected> {
                    FairPlaySapSession(connection, stubResponder).handshake()
                }
            }
        }
    }

    @Test
    fun `a receiver that does not answer m1 with an m2 fails as a bad challenge`() {
        withGarbageReceiver { port ->
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                assertFailsWith<FairPlaySapSession.Failure.BadChallenge> {
                    FairPlaySapSession(connection, stubResponder).handshake()
                }
            }
        }
    }

    @Test
    fun `a responder that cannot answer the mode propagates its refusal`() {
        withFreshSessionMock { port, _ ->
            val refusing = FairPlayResponder { mode, _ ->
                throw FairPlayResponder.Companion.Unsupported("mode $mode is not implemented")
            }
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                assertFailsWith<FairPlayResponder.Companion.Unsupported> {
                    FairPlaySapSession(connection, refusing).handshake()
                }
            }
        }
    }

    @Test
    fun `a responder that returns the wrong number of bytes is rejected loudly`() {
        withFreshSessionMock { port, _ ->
            val short = FairPlayResponder { _, _ -> ByteArray(16) }
            SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
                val e = assertFailsWith<IllegalArgumentException> {
                    FairPlaySapSession(connection, short).handshake()
                }
                assertTrue(e.message!!.contains("16"), "message should name the bad size: ${e.message}")
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
