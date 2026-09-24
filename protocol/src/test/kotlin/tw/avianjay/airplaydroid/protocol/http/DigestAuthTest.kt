package tw.avianjay.airplaydroid.protocol.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The challenge header here is the real one an Apple TV 4K (tvOS 26.6) sent in
 * response to an unauthenticated `POST /play`. It contains no secret -- the
 * nonce is public by construction.
 *
 * The password used throughout is a throwaway. The real device password must
 * never enter this repository.
 */
private const val REAL_CHALLENGE =
    """Digest realm="airplay", nonce="MTc4OTUxODg2OSB+SJNNmGHRCCjAkpBVZIG1""""

private const val REAL_NONCE = "MTc4OTUxODg2OSB+SJNNmGHRCCjAkpBVZIG1"

class DigestAuthTest {

    @Test
    fun `parses the challenge an Apple TV actually sends`() {
        val challenge = DigestAuth.Challenge.parse(REAL_CHALLENGE)

        assertTrue(challenge != null)
        assertEquals("airplay", challenge!!.realm)
        assertEquals(REAL_NONCE, challenge.nonce)
        // That receiver offers no qop, i.e. the simpler RFC 2069 form.
        assertNull(challenge.qop)
    }

    @Test
    fun `computes the RFC 2069 response for the no-qop case`() {
        // Golden value: MD5(HA1:nonce:HA2) with
        //   HA1 = MD5("AirPlay:airplay:testpw"), HA2 = MD5("POST:/play")
        val challenge = DigestAuth.Challenge.parse(REAL_CHALLENGE)!!

        val header = DigestAuth.authorization(challenge, "POST", "/play", "testpw")

        assertTrue(header.startsWith("Digest "), header)
        assertTrue(header.contains("""username="AirPlay""""), header)
        assertTrue(header.contains("""realm="airplay""""), header)
        assertTrue(header.contains("""uri="/play""""), header)
        assertTrue(header.contains("""nonce="$REAL_NONCE""""), header)
        assertTrue(header.contains("""response="6d64025567336a30da2a60bd6d13fba7""""), header)
        // No qop offered means no qop/nc/cnonce may be sent back.
        assertTrue(!header.contains("qop="), header)
        assertTrue(!header.contains("cnonce="), header)
    }

    @Test
    fun `the response changes with the nonce, the method and the uri`() {
        val base = DigestAuth.Challenge.parse(REAL_CHALLENGE)!!
        val other = base.copy(nonce = "different-nonce")

        val a = DigestAuth.authorization(base, "POST", "/play", "testpw")
        val b = DigestAuth.authorization(other, "POST", "/play", "testpw")
        val c = DigestAuth.authorization(base, "GET", "/play", "testpw")
        val d = DigestAuth.authorization(base, "POST", "/stop", "testpw")

        assertTrue(a != b, "nonce must affect the response")
        assertTrue(a != c, "method must affect the response")
        assertTrue(a != d, "uri must affect the response")
    }

    @Test
    fun `the qop branch emits nc and cnonce`() {
        val challenge = DigestAuth.Challenge.parse(
            """Digest realm="airplay", nonce="abc", qop="auth", opaque="xyz""""
        )!!

        assertEquals("auth", challenge.qop)
        assertEquals("xyz", challenge.opaque)

        val header = DigestAuth.authorization(
            challenge, "POST", "/play", "testpw", cnonce = "0011223344556677",
        )

        assertTrue(header.contains("qop=auth"), header)
        assertTrue(header.contains("nc=00000001"), header)
        assertTrue(header.contains("""cnonce="0011223344556677""""), header)
        assertTrue(header.contains("""opaque="xyz""""), header)
    }

    @Test
    fun `qop auth is selected out of a comma-separated list`() {
        val challenge = DigestAuth.Challenge.parse(
            """Digest realm="airplay", nonce="abc", qop="auth-int,auth""""
        )!!

        val header = DigestAuth.authorization(
            challenge, "POST", "/play", "testpw", cnonce = "aa",
        )

        assertTrue(header.contains("qop=auth,") || header.contains("qop=auth "), header)
    }

    @Test
    fun `a non-Digest or malformed challenge is rejected rather than guessed at`() {
        assertNull(DigestAuth.Challenge.parse(null))
        assertNull(DigestAuth.Challenge.parse(""))
        assertNull(DigestAuth.Challenge.parse("Basic realm=\"airplay\""))
        // Missing nonce: unusable.
        assertNull(DigestAuth.Challenge.parse("""Digest realm="airplay""""))
    }

    @Test
    fun `unquoted parameter values are accepted`() {
        val challenge = DigestAuth.Challenge.parse("Digest realm=airplay, nonce=abc123, algorithm=MD5")

        assertEquals("airplay", challenge?.realm)
        assertEquals("abc123", challenge?.nonce)
        assertEquals("MD5", challenge?.algorithm)
    }
}
