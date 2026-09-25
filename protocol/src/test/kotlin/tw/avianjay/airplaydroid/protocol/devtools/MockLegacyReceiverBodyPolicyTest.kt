package tw.avianjay.airplaydroid.protocol.devtools

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayRecords
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlayResponder
import tw.avianjay.airplaydroid.protocol.fairplay.FairPlaySapSession
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mock's [MockLegacyReceiver.SessionPolicy.ACCEPT_DECRYPTABLE_BODY] policy:
 * a regression guard for the bug that blocked hardware.
 *
 * A real receiver decrypts the m3 body and folds the resulting local SAP into the
 * response it checks. A sender that writes the SAP **in the clear** therefore
 * produces a frame that looks perfect -- correct framing, correct label, correct
 * 20-byte response -- and that every receiver rejects.
 *
 * That was a real bug in this project. Nothing offline caught it: the m3 body
 * cipher was implemented and unit-tested, but was never actually called on the
 * send path. This test would have caught it.
 */
class MockLegacyReceiverBodyPolicyTest {

    private fun withMock(
        body: (port: Int) -> Unit,
    ) {
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
                        MockLegacyReceiver.serveForTestWithPolicy(
                            socket,
                            MockLegacyReceiver.SessionPolicy.ACCEPT_DECRYPTABLE_BODY,
                            seen,
                            MockLegacyReceiver.StreamRecorder(),
                        )
                    }
                }
            }
            body(server.localPort)
        } finally {
            server.close()
        }
    }

    /** A responder that stands in for the real FairPlay core. */
    private val stubResponder = FairPlayResponder { _, challenge -> ByteArray(20) { challenge[it] } }

    @Test
    fun `an m3 with an encrypted body is accepted`() {
        withMock { port ->
            val outcome = runCatching {
                SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { control ->
                    FairPlaySapSession(control, stubResponder, SecureRandom()).handshake()
                }
            }
            assertTrue(
                outcome.isSuccess,
                "an m3 built by FairPlayRecords.m3 must decrypt to a well-formed SAP, but got: " +
                    "${outcome.exceptionOrNull()?.message}",
            )
        }
    }

    /**
     * The regression itself: a raw (unencrypted) body must be **refused**.
     *
     * This builds the m3 by hand with the local SAP in the clear, exactly as the
     * buggy sender did, and asserts the mock rejects it. If this ever starts
     * passing as "accepted", the mock has stopped checking and the guard is gone.
     */
    @Test
    fun `an m3 with a raw unencrypted body is refused`() {
        withMock { port ->
            val localSap = ByteArray(128).also { it[1] = 0x01; it[5] = 0x42 }
            val response = ByteArray(20)

            // Hand-build the frame the buggy way: plaintext SAP at [16:144].
            val raw = FairPlayRecords.m3(FairPlayRecords.Mode.MODE_3, localSap, response)
            val buggy = raw.copyOf()
            localSap.copyInto(buggy, 16)

            // Sanity: the two differ, so this really is the bug being modelled.
            assertFalse(
                raw.copyOfRange(16, 144).contentEquals(buggy.copyOfRange(16, 144)),
                "the fixed and buggy frames should differ in the body",
            )

            val accepted = try {
                SocketAirPlayConnection(Endpoint("127.0.0.1", port)).use { control ->
                    // Send m1 first so the mock has answered an m2.
                    control.exchange(
                        AirPlayRequest(
                            method = "POST",
                            uri = "/fp-setup",
                            protocol = AirPlayRequest.RTSP_1_0,
                            headers = listOf(
                                "CSeq" to "1",
                                "User-Agent" to FairPlaySapSession.USER_AGENT,
                                "Content-Type" to "application/octet-stream",
                            ),
                            body = FairPlayRecords.m1(),
                        )
                    )
                    val reply = control.exchange(
                        AirPlayRequest(
                            method = "POST",
                            uri = "/fp-setup",
                            protocol = AirPlayRequest.RTSP_1_0,
                            headers = listOf(
                                "CSeq" to "2",
                                "User-Agent" to FairPlaySapSession.USER_AGENT,
                                "Content-Type" to "application/octet-stream",
                            ),
                            body = buggy,
                        )
                    )
                    !FairPlayRecords.isErrorFrame(reply.body)
                }
            } catch (e: Exception) {
                // A close instead of a refusal is still a rejection.
                false
            }

            assertFalse(
                accepted,
                "the mock ACCEPTED an m3 whose body was not encrypted -- the guard is not working, " +
                    "and this is exactly the bug that hardware found",
            )
        }
    }
}
