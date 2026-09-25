package tw.avianjay.airplaydroid.protocol.devtools

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayRecords
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * Proves the mock receiver can actually **discriminate** between a
 * session-aware sender and a frozen-replay one.
 *
 * This is the point of [MockLegacyReceiver.SessionPolicy.ACCEPT_FRESH_SESSION]:
 * it catches the documented sender bug -- splicing in one captured local SAP, so
 * every session emits a byte-identical m3 -- which real receivers reject with
 * `RTSP/1.0 466 Key Management Error`.
 *
 * A mock that accepted everything, or refused everything, would prove nothing.
 * The two tests below are the positive and negative control: the same mock, the
 * same request shape, and opposite verdicts driven only by whether the local SAP
 * is fresh.
 */
class MockLegacyReceiverSessionTest {

    /** Starts a mock in ACCEPT_FRESH_SESSION, sharing [seen] across connections. */
    private fun withServer(seen: MutableSet<String>, body: (port: Int) -> Unit) {
        val server = ServerSocket(0)
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
            body(server.localPort)
        } finally {
            server.close()
        }
    }

    /** Runs one full m1 -> m3 exchange and returns the m3 response body. */
    private fun exchange(port: Int, localSap: ByteArray): ByteArray =
        SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { connection ->
            connection.exchange(
                AirPlayRequest(
                    method = "POST",
                    uri = "/fp-setup",
                    protocol = AirPlayRequest.RTSP_1_0,
                    headers = listOf("CSeq" to "1"),
                    body = FairPlayRecords.m1(),
                )
            )
            val m3 = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, localSap, ByteArray(20) { 7 })
            connection.exchange(
                AirPlayRequest(
                    method = "POST",
                    uri = "/fp-setup",
                    protocol = AirPlayRequest.RTSP_1_0,
                    headers = listOf("CSeq" to "2"),
                    body = m3,
                )
            ).body
        }

    @Test
    fun `a fresh local SAP is accepted with an m4`() {
        withServer(ConcurrentHashMap.newKeySet()) { port ->
            val response = exchange(port, ByteArray(128) { it.toByte() })

            assertTrue(
                !FairPlayRecords.isErrorFrame(response),
                "a fresh session must not be refused, got ${response.size} bytes",
            )
            assertTrue(response.size == FairPlayRecords.M4_BYTES, "expected a 32-byte m4, got ${response.size}")
            assertTrue(
                FairPlayRecords.isErrorFrame(response).not() && response.size == 32,
                "expected an m4 acknowledgement",
            )
        }
    }

    @Test
    fun `the same local SAP replayed is refused`() {
        val seen = ConcurrentHashMap.newKeySet<String>()
        // One fixed SAP used for every session: exactly what a frozen-replay
        // sender does, because it splices in a captured 144-byte prefix.
        val frozen = ByteArray(128) { (it * 3).toByte() }

        withServer(seen) { port ->
            val first = exchange(port, frozen)
            assertTrue(
                first.size == FairPlayRecords.M4_BYTES,
                "the first use of a SAP is legitimately fresh",
            )

            val second = exchange(port, frozen)
            assertTrue(
                FairPlayRecords.isErrorFrame(second),
                "a byte-identical replay must be refused, got ${second.size} bytes",
            )
        }
    }

    @Test
    fun `a second distinct session is still accepted`() {
        // The negative control for the test above: refusing a replay must not
        // mean refusing every subsequent session, or the mock would be useless.
        withServer(ConcurrentHashMap.newKeySet()) { port ->
            val first = exchange(port, ByteArray(128) { 1 })
            val second = exchange(port, ByteArray(128) { 2 })

            assertTrue(first.size == FairPlayRecords.M4_BYTES, "first session should be accepted")
            assertTrue(second.size == FairPlayRecords.M4_BYTES, "a different SAP should also be accepted")
        }
    }
}
