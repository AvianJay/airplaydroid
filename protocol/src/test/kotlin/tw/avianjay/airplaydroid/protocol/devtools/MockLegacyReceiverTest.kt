package tw.avianjay.airplaydroid.protocol.devtools

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayRecords
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [MockLegacyReceiver] over a real socket with the project's own RTSP
 * codec, so the mock is exercised the same way a device would be.
 *
 * This is the offline stand-in for a dongle on the network: it proves the
 * sender-side framing, request shape and failure handling are right without any
 * hardware. What it cannot prove is that a *correct* FairPlay response is
 * accepted -- the mock rejects every m3, exactly as the real receiver rejected a
 * corrupted one, because computing the correct response needs the white-box core
 * that is not vendored here.
 */
class MockLegacyReceiverTest {

    companion object {
        private lateinit var server: ServerSocket
        private var port = 0

        @BeforeAll
        @JvmStatic
        fun start() {
            server = ServerSocket(0)
            port = server.localPort
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
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            server.close()
        }
    }

    private fun connect() = SocketAirPlayConnection(Endpoint("127.0.0.1", port))

    private fun request(
        method: String,
        uri: String,
        body: ByteArray? = null,
        cseq: Int = 1,
    ) = AirPlayRequest(
        method = method,
        uri = uri,
        protocol = AirPlayRequest.RTSP_1_0,
        headers = listOf("CSeq" to "$cseq", "User-Agent" to "AirPlay/220.68"),
        body = body,
    )

    @Test
    fun `GET info returns the captured plist`() {
        connect().use { connection ->
            val response = connection.exchange(request("GET", "/info"))
            assertEquals(200, response.status)
            assertTrue(response.isSuccess)
            val body = String(response.body, Charsets.UTF_8)
            assertTrue(body.contains("<key>model</key>"), "info body should carry a model key")
            assertTrue(body.contains(MockLegacyReceiver.MODEL), "model should be ${MockLegacyReceiver.MODEL}")
            assertEquals("text/x-apple-plist+xml", response.contentType)
        }
    }

    @Test
    fun `OPTIONS advertises the legacy method list`() {
        connect().use { connection ->
            val response = connection.exchange(request("OPTIONS", "*"))
            assertEquals(200, response.status)
            val public = response.header("Public")
            assertTrue(public != null && public.contains("ANNOUNCE"), "Public was: $public")
        }
    }

    @Test
    fun `fp-setup answers m1 with the captured 142-byte m2`() {
        connect().use { connection ->
            val response = connection.exchange(request("POST", "/fp-setup", FairPlayRecords.m1()))

            assertEquals(200, response.status)
            assertEquals(142, response.body.size, "m2 must be 142 bytes")
            assertContentEquals(FairPlayRecords.MAGIC, response.body.copyOfRange(0, 4))

            val challenge = FairPlayRecords.parseM2(response.body).getOrThrow()
            assertEquals(FairPlayRecords.Mode.MODE_3, challenge.mode)
            assertEquals(128, challenge.challenge.size)
        }
    }

    @Test
    fun `fp-setup refuses an m3 with the captured 12-byte error frame`() {
        connect().use { connection ->
            // Open the exchange properly, then answer with an all-zero response
            // -- i.e. the wrong key. This is the same shape as the hardware test
            // that produced the captured refusal.
            connection.exchange(request("POST", "/fp-setup", FairPlayRecords.m1(), cseq = 1))

            val m3 = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, ByteArray(128), ByteArray(20))
            val response = connection.exchange(request("POST", "/fp-setup", m3, cseq = 2))

            // Note: the refusal rides in the *body* under a 200 status, which is
            // exactly what the real receiver does. A sender that only checks the
            // status line will not notice it was refused.
            assertEquals(200, response.status)
            assertEquals(12, response.body.size)
            assertTrue(FairPlayRecords.isErrorFrame(response.body), "expected the refusal frame")
            assertEquals(
                FairPlayRecords.ERROR_STATUS_M3_REJECTED,
                FairPlayRecords.errorStatus(response.body),
            )
        }
    }

    @Test
    fun `unknown paths return the receiver's 404`() {
        connect().use { connection ->
            val response = connection.exchange(request("GET", "/not-a-real-endpoint"))
            assertEquals(404, response.status)
        }
    }
}
