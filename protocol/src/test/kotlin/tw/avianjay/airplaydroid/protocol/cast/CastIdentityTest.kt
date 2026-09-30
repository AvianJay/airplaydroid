package tw.avianjay.airplaydroid.protocol.cast

import java.security.Signature
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun survivesEncodeAndDecode() {
        val restored = assertNotNull(CastIdentity.decode(identity.encode()))
        assertContentEquals(identity.certificateDer, restored.certificateDer)
        assertContentEquals(identity.privateKey.encoded, restored.privateKey.encoded)
    }

    @Test
    fun decodeRefusesGarbage() {
        assertEquals(null, CastIdentity.decode(""))
        assertEquals(null, CastIdentity.decode("not\nbase64 at all\n"))
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
        val server = identity.sslContext().serverSocketFactory.createServerSocket(0)
        val accepted = thread {
            server.accept().use { socket ->
                (socket as SSLSocket).startHandshake()
                socket.outputStream.write(42)
                socket.outputStream.flush()
            }
        }
        // A sender trusts any certificate, as every Cast sender does for TLS.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val client = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
        client.socketFactory.createSocket("127.0.0.1", server.localPort).use { socket ->
            (socket as SSLSocket).startHandshake()
            assertContentEquals(identity.certificateDer, socket.session.peerCertificates[0].encoded)
            assertEquals(42, socket.inputStream.read())
        }
        accepted.join()
        server.close()
    }
}
