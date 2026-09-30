package tw.avianjay.airplaydroid.protocol.cast

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.security.Signature
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The listener over a real TLS socket, checked the way a sender checks it: the
 * certificate's validity first, then device auth against that same certificate.
 */
class CastServerTest {

    private var now = System.currentTimeMillis()
    private val identities = CastIdentitySource("test") { now }
    private val logged = ArrayList<String>()
    private var allow = true

    private val receiver = CastReceiver(identities.prepare(), NoPlayer)
    private val server = CastServer(receiver, identities::current, accept = { allow }, log = { synchronized(logged) { logged += it } })
    private val port = server.start(0)

    @AfterTest
    fun stop() = server.close()

    private class Session(val certificate: X509Certificate, val authCertificate: ByteArray, val signatureOk: Boolean)

    /** Connects, applies the sender's validity rule, and runs one device-auth exchange. */
    private fun connectAndAuthenticate(): Session {
        CastIdentityTest.client().socketFactory.createSocket("127.0.0.1", port).use { raw ->
            val socket = raw as SSLSocket
            socket.soTimeout = 5_000
            socket.startHandshake()
            val certificate = socket.session.peerCertificates[0] as X509Certificate
            // On the test's clock, which the sender and the receiver share here.
            certificate.checkValidity(java.util.Date(now))
            // Chromium's kMaxSelfSignedCertLifetimeInDays.
            assertTrue(certificate.notAfter.time - certificate.notBefore.time <= 4L * 24 * 60 * 60 * 1000)

            val nonce = ByteArray(16) { (it + 1).toByte() }
            val challenge = ProtoWriter().apply {
                message(1) {
                    varint(1, 1)
                    bytes(2, nonce)
                    varint(3, DeviceAuth.HashAlgorithm.SHA256.wire)
                }
            }.toByteArray()
            CastMessage.write(
                socket.outputStream,
                CastMessage("sender-0", CastReceiver.RECEIVER_ID, CastReceiver.NS_DEVICE_AUTH, payloadBinary = challenge),
            )
            val reply = CastMessage.read(BufferedInputStream(socket.inputStream))
            var response: ByteArray? = null
            ProtoReader(assertNotNull(reply.payloadBinary)).forEachField { field, value -> if (field == 2) response = value.bytes() }
            var signature = ByteArray(0)
            var authCertificate = ByteArray(0)
            ProtoReader(assertNotNull(response)).forEachField { field, value ->
                when (field) {
                    1 -> signature = value.bytes()
                    2 -> authCertificate = value.bytes()
                }
            }
            val ok = Signature.getInstance("SHA256withRSA").run {
                initVerify(certificate.publicKey)
                update(nonce + certificate.encoded)
                verify(signature)
            }
            return Session(certificate, authCertificate, ok)
        }
    }

    @Test
    fun deviceAuthSignsTheCertificateThisConnectionPresented() {
        val session = connectAndAuthenticate()
        assertContentEquals(session.certificate.encoded, session.authCertificate)
        assertTrue(session.signatureOk)
    }

    @Test
    fun aRotatedIdentityIsUsedForNewConnectionsAndSignsItsOwnCertificate() {
        val first = connectAndAuthenticate()
        now += CastIdentitySource.ROTATE_AFTER_MS + 60_000
        val second = connectAndAuthenticate()
        assertFalse(first.certificate.encoded.contentEquals(second.certificate.encoded), "rotated")
        // Not the receiver's constructor identity: the connection's own.
        assertContentEquals(second.certificate.encoded, second.authCertificate)
        assertTrue(second.signatureOk)
    }

    @Test
    fun aRefusedSenderNeverGetsAHandshake() {
        allow = false
        assertFailsWith<IOException> {
            CastIdentityTest.client().socketFactory.createSocket("127.0.0.1", port).use { raw ->
                (raw as SSLSocket).soTimeout = 5_000
                raw.startHandshake()
            }
        }
    }

    @Test
    fun repeatedRefusalsFromOneAddressAreLoggedOnce() {
        allow = false
        repeat(5) {
            runCatching {
                CastIdentityTest.client().socketFactory.createSocket("127.0.0.1", port).use { raw ->
                    (raw as SSLSocket).soTimeout = 2_000
                    raw.startHandshake()
                }
            }
        }
        Thread.sleep(200)
        val refusals = synchronized(logged) { logged.count { it.startsWith("refused a sender at") } }
        assertEquals(1, refusals, synchronized(logged) { logged.toString() })
    }

    @Test
    fun ownAddressesPassTheLocalOnlyCheck() {
        assertTrue(LocalAddresses.isOwnAddress(InetAddress.getLoopbackAddress()))
    }

    private object NoPlayer : CastPlayer {
        override fun onSessionStarted(appId: String) = Unit
        override fun onLoad(request: CastLoadRequest) = Unit
        override fun onPlay() = Unit
        override fun onPause() = Unit
        override fun onSeek(positionSeconds: Double) = Unit
        override fun onStop() = Unit
        override fun onSessionEnded() = Unit
    }
}
