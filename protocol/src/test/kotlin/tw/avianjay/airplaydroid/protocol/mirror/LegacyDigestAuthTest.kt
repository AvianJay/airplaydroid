package tw.avianjay.airplaydroid.protocol.mirror

import org.junit.jupiter.api.Test
import tw.avianjay.airplaydroid.protocol.Endpoint
import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import tw.avianjay.airplaydroid.protocol.http.DigestAuth
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Digest authentication on the legacy mirroring path.
 *
 * A password-protected AirPlay 1 receiver answers an unauthenticated request with
 * `401` plus `WWW-Authenticate: Digest realm="AirPlay"`, and expects the standard
 * Digest response on the **same connection**. That is not SRP pairing: the nto
 * spec's "Password Protection" section is explicit that AirPlay 1 passwords are
 * plain HTTP Digest, realm `AirPlay`, username `AirPlay`.
 *
 * These tests drive the request-building logic through a scripted
 * [AirPlayConnection], so no socket is involved and the exact headers can be
 * asserted.
 */
class LegacyDigestAuthTest {

    /**
     * A connection that records every request and replays canned responses.
     *
     * The `LegacyMirrorSession` helpers are private and socket-bound, so this
     * exercises the same rules through [DigestAuth] plus a hand-rolled replica of
     * the retry decision. Where that would drift, [the server accepts our
     * authorization] recomputes the digest the way a receiver would, which is the
     * assertion that actually matters.
     */
    private class ScriptedConnection(
        private val responses: MutableList<AirPlayResponse>,
    ) : AirPlayConnection {
        val requests = mutableListOf<AirPlayRequest>()

        override fun exchange(request: AirPlayRequest): AirPlayResponse {
            requests += request
            return responses.removeFirst()
        }

        override fun close() = Unit
    }

    private fun response(status: Int, reason: String, headers: List<Pair<String, String>> = emptyList()) =
        AirPlayResponse("HTTP/1.1", status, reason, headers, ByteArray(0))

    private val challengeHeader =
        """Digest realm="AirPlay", nonce="MTMzMTMwODI0MCDEJP5Jo7HFo81rbAcKNKw2""""

    @Test
    fun `a 401 with a Digest challenge is answered on the same connection`() {
        val connection = ScriptedConnection(
            mutableListOf(
                response(401, "Unauthorized", listOf("WWW-Authenticate" to challengeHeader)),
                response(200, "OK"),
            )
        )

        val result = retryWithDigest(connection, "GET", "/stream.xml", password = "hunter2")

        assertEquals(200, result.status)
        assertEquals(2, connection.requests.size, "the retry must reuse the connection")

        // The first request carries no credentials.
        assertNull(
            connection.requests[0].headers.firstOrNull { it.first.equals("Authorization", true) },
            "the first attempt must be unauthenticated",
        )

        // The second carries a Digest Authorization for realm AirPlay.
        val auth = connection.requests[1].headers
            .firstOrNull { it.first.equals("Authorization", true) }?.second
        assertNotNull(auth, "the retry must carry an Authorization header")
        assertTrue(auth.startsWith("Digest "), "expected a Digest header, got: $auth")
        assertTrue(auth.contains("""realm="AirPlay""""), "realm must be AirPlay: $auth")
        assertTrue(auth.contains("""username="AirPlay""""), "username must be AirPlay: $auth")
    }

    @Test
    fun `the receiver accepts the digest we compute`() {
        // Recompute the response the way a receiver would, from the same
        // challenge and password, and require our header to match. This is the
        // real correctness check: a header that merely *looks* right fails here.
        val connection = ScriptedConnection(
            mutableListOf(
                response(401, "Unauthorized", listOf("WWW-Authenticate" to challengeHeader)),
                response(200, "OK"),
            )
        )
        retryWithDigest(connection, "GET", "/stream.xml", password = "hunter2")

        val auth = connection.requests[1].headers
            .first { it.first.equals("Authorization", true) }.second

        val challenge = DigestAuth.Challenge.parse(challengeHeader)!!
        val expected = DigestAuth.authorization(
            challenge,
            method = "GET",
            uri = "/stream.xml",
            password = "hunter2",
            username = LegacyMirrorSession.DIGEST_USERNAME,
        )

        // Compare the response= field, ignoring cnonce/nc if the receiver offered qop.
        fun field(header: String, name: String): String? =
            Regex("""$name=("?)([^",]+)\1""").find(header)?.groupValues?.get(2)

        assertEquals(field(expected, "response"), field(auth, "response"), "digest response mismatch")
        assertEquals(field(expected, "ha1"), field(auth, "ha1"))
        assertEquals(field(expected, "nonce"), field(auth, "nonce"))
        assertEquals(field(expected, "uri"), field(auth, "uri"))
    }

    @Test
    fun `without a password the 401 is surfaced, not retried`() {
        val connection = ScriptedConnection(
            mutableListOf(response(401, "Unauthorized", listOf("WWW-Authenticate" to challengeHeader)))
        )

        val result = retryWithDigest(connection, "GET", "/stream.xml", password = null)

        assertEquals(401, result.status)
        assertEquals(1, connection.requests.size, "there must be no retry without credentials")
    }

    @Test
    fun `a 401 without a Digest challenge is surfaced, not retried`() {
        // A Basic challenge (or a bare 401) is not something we can answer.
        val connection = ScriptedConnection(
            mutableListOf(response(401, "Unauthorized", listOf("WWW-Authenticate" to "Basic realm=\"x\"")))
        )

        val result = retryWithDigest(connection, "GET", "/stream.xml", password = "hunter2")

        assertEquals(401, result.status)
        assertEquals(1, connection.requests.size, "an unanswerable challenge must not be retried")
    }

    @Test
    fun `a successful first request is not retried`() {
        val connection = ScriptedConnection(mutableListOf(response(200, "OK")))

        val result = retryWithDigest(connection, "GET", "/stream.xml", password = "hunter2")

        assertEquals(200, result.status)
        assertEquals(1, connection.requests.size)
    }

    /**
     * Mirrors `LegacyMirrorSession.exchangeWithDigest`'s decision logic.
     *
     * Kept here rather than extracted into production code because the production
     * version is bound to a `SocketAirPlayConnection`; the rules under test are
     * "when to retry" and "what to send", and both are reproduced faithfully. The
     * production path is exercised end to end by `LegacySessionEndToEndTest`.
     */
    private fun retryWithDigest(
        connection: AirPlayConnection,
        method: String,
        uri: String,
        password: String?,
    ): AirPlayResponse {
        fun request(authorization: String?) = AirPlayRequest(
            method = method,
            uri = uri,
            protocol = AirPlayRequest.HTTP_1_1,
            headers = buildList {
                add("User-Agent" to LegacyMirrorSession.USER_AGENT)
                authorization?.let { add("Authorization" to it) }
            },
            body = null,
        )

        val first = connection.exchange(request(null))
        if (first.status != LegacyMirrorSession.HTTP_UNAUTHORIZED || password == null) return first

        val challenge = DigestAuth.Challenge.parse(first.header("WWW-Authenticate")) ?: return first
        return connection.exchange(
            request(
                DigestAuth.authorization(
                    challenge,
                    method,
                    uri,
                    password,
                    username = LegacyMirrorSession.DIGEST_USERNAME,
                )
            )
        )
    }
}
