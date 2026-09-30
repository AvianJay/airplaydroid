package tw.avianjay.airplaydroid.protocol.cast

import java.security.Signature
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CastIdentityTest {

    private val identity = CastIdentity.generate("Living room")

    @Test
    fun theCertificateIsSelfSignedAndValid() {
        val certificate = identity.certificate
        certificate.checkValidity()
        // Verifies against its own key: issuer and subject are the same.
        certificate.verify(certificate.publicKey)
        assertEquals(certificate.issuerX500Principal, certificate.subjectX500Principal)
        assertTrue("Living room" in certificate.subjectX500Principal.name)
    }

    /**
     * The rule senders apply, and the one the first version broke: a self-signed
     * TLS certificate valid for more than four days (Chromium's
     * `kMaxSelfSignedCertLifetimeInDays`) is refused by closing the connection
     * mid-handshake. That version's twenty-year certificate was refused by every
     * sender on a phone, silently, every three seconds.
     */
    @Test
    fun theValidityIsShortEnoughForSenders() {
        val now = System.currentTimeMillis()
        val fresh = CastIdentity.generate("x", now)
        val notBefore = fresh.certificate.notBefore.time
        val notAfter = fresh.certificate.notAfter.time
        assertTrue(notAfter - notBefore <= 48L * 60 * 60 * 1000, "at most 48 hours, as a Chromecast issues")
        assertTrue(notAfter - notBefore <= 4L * 24 * 60 * 60 * 1000, "within Chromium's four-day limit")
        assertTrue(notBefore <= now, "not from the future")
        assertTrue(now - notBefore <= 2L * 60 * 60 * 1000, "made just now, not backdated by days")
        assertTrue(notAfter - now >= 24L * 60 * 60 * 1000, "outlives the rotation interval")
    }

    @Test
    fun theSourceRotatesBeforeTheCertificateGetsOld() {
        var now = 1_000_000_000_000L
        val source = CastIdentitySource("x") { now }
        val first = source.prepare()
        now += 60_000
        assertSame(first, source.current(), "a minute later: the same one")
        now += CastIdentitySource.ROTATE_AFTER_MS
        val second = source.current()
        assertNotSame(first, second)
        // The new one's validity starts from the time it was made.
        assertTrue(second.certificate.notAfter.time > first.certificate.notAfter.time)
        assertTrue(second.certificate.notAfter.time - now >= 24L * 60 * 60 * 1000)
    }

    @Test
    fun aClockThatJumpsBackAlsoGetsAFreshOne() {
        var now = 1_000_000_000_000L
        val source = CastIdentitySource("x") { now }
        val first = source.prepare()
        now -= 3L * 24 * 60 * 60 * 1000
        assertNotSame(first, source.current(), "a certificate from the future would be refused")
    }

    @Test
    fun theAuthResponseSignatureVerifiesWithTheCertificate() {
        val challenge = DeviceAuth.Challenge(ByteArray(16) { (it * 7).toByte() }, DeviceAuth.HashAlgorithm.SHA256)
        val signed = DeviceAuth.signedData(challenge, identity.certificateDer)
        val signature = identity.sign(signed, challenge.hashAlgorithm)

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(identity.certificate.publicKey)
        verifier.update(challenge.senderNonce + identity.certificateDer)
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun namesThatWouldBreakTheDistinguishedNameAreSanitised() {
        val odd = CastIdentity.generate("a,b=c\"d")
        odd.certificate.checkValidity()
    }

    @Test
    fun servesTls() {
        val server = identity.sslContext.serverSocketFactory.createServerSocket(0)
        val accepted = thread {
            server.accept().use { socket ->
                (socket as SSLSocket).startHandshake()
                socket.outputStream.write(42)
                socket.outputStream.flush()
            }
        }
        client().socketFactory.createSocket("127.0.0.1", server.localPort).use { socket ->
            (socket as SSLSocket).startHandshake()
            assertContentEquals(identity.certificateDer, socket.session.peerCertificates[0].encoded)
            assertEquals(42, socket.inputStream.read())
        }
        accepted.join()
        server.close()
    }

    companion object {
        /** A sender trusts any certificate for TLS itself, as every Cast sender does. */
        fun client(): SSLContext {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
        }
    }
}
