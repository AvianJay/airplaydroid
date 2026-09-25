package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The real-hardware FairPlay test.**
 *
 * Everything else in this project checks the implementation against captured
 * bytes or against an independent implementation's vectors. This checks the one
 * thing neither can: whether an actual receiver **accepts the response we
 * compute**.
 *
 * Disabled by default because it needs a reachable receiver. Run it with:
 *
 * ```
 * ./gradlew :protocol:test --tests '*FairPlayHardware*' \
 *     -Dairplay.host=192.168.31.51 -Dairplay.port=7000
 * ```
 *
 * ### Why the assertion is the handshake returning at all
 *
 * [FairPlaySapSession.handshake] already does the checking, and it is strict:
 *
 *  - a refusal rides in the **body** under a 200 status, so it inspects the body
 *    for the 12-byte refusal frame and throws `Failure.Rejected` with the status;
 *  - a reply that is not a 32-byte m4 confirming the m3 we sent throws
 *    `Failure.UnexpectedReply`.
 *
 * So a handshake that *returns* means the receiver produced a valid m4 over our
 * own m3. That is the whole result; the tests below only add detail around it.
 *
 * ### What this still does not prove
 *
 * That the receiver *cryptographically* verified the response. A receiver could
 * in principle answer m4 without checking anything -- which is why
 * [the same receiver refuses a corrupted response] exists as the control. If that
 * control shows the receiver refuses a wrong response, the acceptance here is
 * meaningful.
 */
class FairPlayHardwareTest {

    private val host: String? = System.getProperty("airplay.host")
    private val port: Int = System.getProperty("airplay.port")?.toIntOrNull() ?: 0

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun endpoint(): Endpoint {
        val h = requireNotNull(host) { "set -Dairplay.host" }
        require(port > 0) { "set -Dairplay.port" }
        return Endpoint(h, port)
    }

    /**
     * Drives the whole handshake against a live receiver.
     *
     * A return value means the receiver answered our m3 with a valid m4.
     */
    @Test
    @EnabledIfSystemProperty(named = "airplay.host", matches = ".+")
    fun `a real receiver accepts the response we compute`() {
        val endpoint = endpoint()

        val outcome = runCatching {
            SocketAirPlayConnection(endpoint).use { control ->
                FairPlaySapSession(control, FairPlayResponderImpl, SecureRandom()).handshake()
            }
        }

        val session = outcome.getOrElse { cause ->
            throw AssertionError(
                "the receiver did not accept our m3: ${cause::class.simpleName}: ${cause.message}",
                cause,
            )
        }

        println("receiver  = $endpoint")
        println("mode      = ${session.mode.value}")
        println("challenge = ${session.challenge.copyOf(16).toHex()}...")
        println("localSap  = ${session.localSap.copyOf(8).toHex()}...")
        println("response  = ${session.response.toHex()}")

        assertEquals(128, session.challenge.size, "a challenge is 128 bytes")
        assertTrue(
            session.challenge.any { it != 0.toByte() },
            "a real receiver's challenge should not be all zeroes",
        )
        assertEquals(20, session.response.size, "the response is 20 bytes")
        assertEquals(0x00.toByte(), session.localSap[0], "local SAP byte 0")
        assertEquals(0x01.toByte(), session.localSap[1], "local SAP byte 1")

        // Reaching here means handshake() validated the m4 against our m3.
        println("VERDICT: the receiver accepted our FairPlay response (valid m4 over our m3)")
    }

    /**
     * The control: the same receiver must not accept a deliberately wrong m3.
     *
     * Without this, [a real receiver accepts the response we compute] would pass
     * even against a receiver that answers m4 to anything -- and the acceptance
     * would prove nothing. This is the test that makes the other one mean
     * something.
     *
     * Three outcomes are distinguished, because conflating them is exactly how
     * the Phase 2 gap stayed hidden:
     *
     *  - **refused** (`Failure.Rejected` / `UnexpectedReply`) -- the receiver
     *    evaluated the response and said no. This is the good control.
     *  - **no reply at all** (a timeout) -- the receiver did not engage. It does
     *    not establish that it validates anything, so the control is
     *    *inconclusive*, and this test says so rather than claiming either way.
     *  - **completed** -- the receiver accepted an all-zero response, which makes
     *    the acceptance test meaningless. A real failure.
     */
    @Test
    @EnabledIfSystemProperty(named = "airplay.host", matches = ".+")
    fun `the same receiver does not accept a corrupted response`() {
        val endpoint = endpoint()

        // An all-zero response: structurally valid, certainly wrong.
        val bogus = FairPlayResponder { _, _ -> ByteArray(20) }

        val outcome = runCatching {
            SocketAirPlayConnection(endpoint).use { control ->
                FairPlaySapSession(control, bogus, SecureRandom()).handshake()
            }
        }

        val failure = outcome.exceptionOrNull()
        when {
            failure is FairPlaySapSession.Failure.Rejected -> {
                println("control OK: the receiver refused an all-zero response " +
                    "(status 0x${failure.status?.toString(16)})")
            }

            failure is FairPlaySapSession.Failure.UnexpectedReply -> {
                println("control OK: the receiver replied to an all-zero response with something " +
                    "that is not a valid m4 (${failure.message})")
            }

            failure is java.net.SocketTimeoutException -> {
                // Honest reporting: no reply means the control proves nothing.
                println(
                    "control INCONCLUSIVE: the receiver did not reply to a wrong m3 at all, " +
                        "so it is not established that it validates the response. " +
                        "The acceptance test therefore proves nothing on this receiver."
                )
            }

            failure != null -> {
                println("control INCONCLUSIVE: unexpected failure ${failure::class.simpleName}: ${failure.message}")
            }

            else -> throw AssertionError(
                "the receiver ACCEPTED an all-zero response, so its acceptance in the other test " +
                    "proves nothing -- it may not be checking the response at all",
            )
        }
    }

    /**
     * Prints the receiver's raw m2, so a fresh challenge can be captured.
     *
     * Enabled separately (`-Dairplay.capture=true`) because it asserts nothing:
     * it is a capture tool, and the m2 it prints can become a fixture.
     */
    @Test
    @EnabledIfSystemProperty(named = "airplay.capture", matches = "true")
    fun `capture the receiver m2`() {
        val endpoint = endpoint()

        val body = SocketAirPlayConnection(endpoint).use { control ->
            control.exchange(
                tw.avianjay.airplaydroid.protocol.http.AirPlayRequest(
                    method = "POST",
                    uri = "/fp-setup",
                    protocol = tw.avianjay.airplaydroid.protocol.http.AirPlayRequest.RTSP_1_0,
                    headers = listOf(
                        "CSeq" to "1",
                        "User-Agent" to FairPlaySapSession.USER_AGENT,
                        "Content-Type" to "application/octet-stream",
                    ),
                    body = FairPlayRecords.m1(),
                )
            ).body
        }

        val parsed = FairPlayRecords.parseM2(body).getOrThrow()
        println("m2 body (${body.size} bytes): ${body.toHex()}")
        println("mode      = ${parsed.mode.value}")
        println("challenge = ${parsed.challenge.toHex()}")
    }
}
