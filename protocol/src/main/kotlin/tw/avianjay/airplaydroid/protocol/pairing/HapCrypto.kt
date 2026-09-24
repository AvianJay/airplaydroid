package tw.avianjay.airplaydroid.protocol.pairing

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * The primitives HomeKit pairing is built from, via BouncyCastle's lightweight
 * API only -- never Security.addProvider, which collides with Conscrypt on
 * Android. The JDK's own ChaCha20-Poly1305 is not an option either: minSdk 26
 * predates it.
 */
object HapCrypto {

    private val random = SecureRandom()

    /** HKDF-SHA512 with string salt and info, as every HAP key derivation uses. */
    fun hkdf(salt: String, info: String, secret: ByteArray, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(secret, salt.toByteArray(), info.toByteArray()))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    /**
     * The 12-byte nonce for a pairing message: four zero bytes, then an 8-byte
     * label such as `PS-Msg05`.
     */
    fun labelNonce(label: String): ByteArray {
        val bytes = label.toByteArray()
        require(bytes.size == 8) { "pairing nonce labels are 8 bytes: $label" }
        return ByteArray(4) + bytes
    }

    /** ChaCha20-Poly1305; returns ciphertext with the 16-byte tag appended. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(true, key, nonce, plaintext, aad)

    /** Inverse of [seal]; throws [InvalidCipherTextException] if the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(false, key, nonce, sealed, aad)

    private fun chacha(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    class Ed25519KeyPair(val seed: ByteArray) {
        val publicKey: ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

        fun sign(message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        companion object {
            fun generate() = Ed25519KeyPair(ByteArray(32).also { random.nextBytes(it) })
        }
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    class X25519KeyPair private constructor(private val privateKey: X25519PrivateKeyParameters) {
        val publicKey: ByteArray = privateKey.generatePublicKey().encoded

        fun agree(peerPublicKey: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(privateKey)
            return ByteArray(agreement.agreementSize).also {
                agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), it, 0)
            }
        }

        companion object {
            fun generate() = X25519KeyPair(X25519PrivateKeyParameters(random))
        }
    }
}
