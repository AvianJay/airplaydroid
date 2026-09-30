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
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
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
 * The certificate is built from BouncyCastle's ASN.1 classes and signed through
 * the JCA, because the certificate *builder* lives in `bcpkix`, a second
 * dependency for one call. Kept for as long as the app keeps it -- see [encode]
 * -- so a sender that remembers the receiver's certificate sees the same one.
 */
class CastIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
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
     * A server-side TLS context presenting this certificate.
     *
     * A PKCS#12 key store because it is the one in-memory type both the desktop
     * JVM and Android provide; the key never touches disk through it.
     */
    fun sslContext(): SSLContext {
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }.keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    /** Two base64 lines: the PKCS#8 key, then the DER certificate. Read back with [decode]. */
    fun encode(): String {
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(privateKey.encoded) + "\n" + encoder.encodeToString(certificateDer) + "\n"
    }

    companion object {
        private const val ALIAS = "cast"
        private const val KEY_BITS = 2048

        /**
         * Long enough that an installed receiver never has to rotate it. Senders
         * that honour validity at all only look at the device-auth certificate,
         * which is this one, and they reject it for its issuer regardless.
         */
        private const val VALIDITY_MS = 20L * 365 * 24 * 60 * 60 * 1000

        fun generate(commonName: String, now: Long = System.currentTimeMillis()): CastIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, SecureRandom()) }
                .generateKeyPair()
            val signatureAlgorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=" + commonName.replace(Regex("[,+=\"\\\\<>;#]"), " ").trim().ifEmpty { "Cast" })

            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(63, SecureRandom())))
                setIssuer(name)
                setSubject(name)
                // Backdated a day: a sender whose clock is a little behind must not
                // see a certificate from the future.
                setStartDate(Time(Date(now - 24L * 60 * 60 * 1000)))
                setEndDate(Time(Date(now + VALIDITY_MS)))
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
            return CastIdentity(keys.private, parseCertificate(der))
        }

        /** The inverse of [encode]; null when the text is not a stored identity. */
        fun decode(text: String): CastIdentity? = runCatching {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size != 2) return null
            val decoder = Base64.getDecoder()
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(lines[0])))
            CastIdentity(key, parseCertificate(decoder.decode(lines[1])))
        }.getOrNull()

        private fun parseCertificate(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}
