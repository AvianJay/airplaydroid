package tw.avianjay.airplaydroid.protocol.cast

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The receiver's TLS key and self-signed certificate.
 *
 * Cast runs over TLS, and senders do not check the TLS certificate against any
 * CA -- a real Chromecast's is self-issued too. What they may check is the
 * separate device-auth signature ([DeviceAuth]); this key signs that as well,
 * which is as far as an app without a Google-issued device key can go.
 *
 * **The certificate is short-lived on purpose.** Senders refuse a self-signed
 * TLS certificate valid for more than a few days -- Chromium's Cast code caps
 * it at four (`kMaxSelfSignedCertLifetimeInDays`), a real Chromecast issues a
 * new one every day or two -- and they refuse it by closing the connection in
 * the middle of the handshake, with no alert and no error on either side. The
 * first version of this class issued one valid for twenty years; every sender
 * on a phone did exactly that, every three seconds. [VALIDITY_MS] is therefore
 * 48 hours, and [CastIdentitySource] replaces the identity well before that.
 *
 * The certificate is built from BouncyCastle's ASN.1 classes and signed through
 * the JCA, because the certificate *builder* lives in `bcpkix`, a second
 * dependency for one call.
 */
class CastIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    /** When it was made, on the clock [generate] was given. */
    val createdAt: Long,
) {
    /** The certificate in DER, which is also what the device-auth signature covers. */
    val certificateDer: ByteArray get() = certificate.encoded

    fun sign(data: ByteArray, hash: DeviceAuth.HashAlgorithm): ByteArray =
        Signature.getInstance(hash.jcaSignature).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /**
     * A server-side TLS context presenting this certificate, built once.
     *
     * A PKCS#12 key store because it is the one in-memory type both the desktop
     * JVM and Android provide; the key never touches disk through it.
     */
    val sslContext: SSLContext by lazy {
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }.keyManagers
        SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    companion object {
        private const val ALIAS = "cast"
        private const val KEY_BITS = 2048

        /** How long a certificate is valid: what a Chromecast issues, and half the senders' limit. */
        const val VALIDITY_MS = 48L * 60 * 60 * 1000

        /**
         * How far notBefore is set back, so a sender whose clock runs a little
         * behind does not see a certificate from the future. Counted inside
         * [VALIDITY_MS], never on top of it.
         */
        const val BACKDATE_MS = 60L * 60 * 1000

        fun generate(commonName: String, now: Long = System.currentTimeMillis()): CastIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, SecureRandom()) }
                .generateKeyPair()
            val signatureAlgorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=" + commonName.replace(Regex("[,+=\"\\\\<>;#]"), " ").trim().ifEmpty { "Cast" })
            val notBefore = now - BACKDATE_MS

            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(63, SecureRandom())))
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(Date(notBefore)))
                setEndDate(Time(Date(notBefore + VALIDITY_MS)))
                setSignature(signatureAlgorithm)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keys.public.encoded))
            }.generateTBSCertificate()

            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(keys.private)
                update(tbs.encoded)
                sign()
            }
            val der = DERSequence(
                ASN1EncodableVector().apply {
                    add(tbs)
                    add(signatureAlgorithm)
                    add(DERBitString(signature))
                }
            ).encoded
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return CastIdentity(keys.private, certificate, now)
        }
    }
}

/**
 * The identity to present on the next connection, replaced before it gets old.
 *
 * [ROTATE_AFTER_MS] is 20 hours: well inside the 48-hour validity, and inside
 * the "made in the last day" that some senders are reported to expect, even for
 * a sender whose clock is a few hours ahead. A connection keeps the identity it
 * was accepted with (see [CastChannel.tlsIdentity]), so replacing it never pulls
 * a certificate out from under a session in progress.
 *
 * Generating a 2048-bit RSA key takes up to a second on a phone; that happens
 * on the thread that asks, once a day, and [prepare] does the first one ahead of
 * time.
 */
class CastIdentitySource(
    private val commonName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var identity: CastIdentity? = null

    @Synchronized
    fun current(): CastIdentity {
        val now = clock()
        identity?.takeIf { now - it.createdAt in 0 until ROTATE_AFTER_MS }?.let { return it }
        return CastIdentity.generate(commonName, now).also { identity = it }
    }

    /** Makes the first identity now, so the first sender does not wait for it. */
    fun prepare(): CastIdentity = current()

    companion object {
        const val ROTATE_AFTER_MS = 20L * 60 * 60 * 1000
    }
}
