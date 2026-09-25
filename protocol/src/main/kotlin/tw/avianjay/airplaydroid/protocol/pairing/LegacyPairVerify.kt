package tw.avianjay.airplaydroid.protocol.pairing

import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing a legacy (non-HAP) receiver runs before FairPlay: a bare Ed25519
 * `/pair-setup`, then a two-round `/pair-verify`.
 *
 * Not HomeKit pairing ([HomeKitPairing]) and not PIN pairing ([LegacyPairing]).
 * No SRP, no TLV8, no password -- the bodies are raw bytes:
 *
 * ```
 * pair-setup     our Ed25519 pk (32)                     -> their Ed25519 pk (32)
 * pair-verify 1  01000000 | our X25519 pk | our Ed pk    -> their X25519 pk | enc(their sig) (96)
 * pair-verify 2  00000000 | enc(our sig)                 -> 200, empty
 * ```
 *
 * Both signatures cover the two X25519 keys, signer's first. They travel
 * AES-128-CTR encrypted under `SHA-512("Pair-Verify-AES-Key" || secret)[0:16]`
 * and the matching `-IV`, as **one** keystream: the receiver's signature takes
 * the first 64 bytes and ours the next 64. RPiPlay's `pairing_session_finish`
 * spends the first 64 on a dummy block for exactly that reason.
 *
 * The point of all this for mirroring is [Result.sharedSecret]: an RPiPlay-family
 * receiver hashes it into the FairPlay stream key, so a session that skipped
 * pair-verify would be decrypting with a different key than we encrypt with.
 */
class LegacyPairVerify(
    private val connection: AirPlayConnection,
    private val identity: HapCrypto.Ed25519KeyPair = HapCrypto.Ed25519KeyPair.generate(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private var cseq: Int = 1,
) {

    /** A completed pair-verify. */
    class Result(
        /** The X25519 secret; feeds the stream key. */
        val sharedSecret: ByteArray,
        /** The receiver's Ed25519 key, from `/pair-setup`. */
        val receiverPublicKey: ByteArray,
        /** The next CSeq to use on this connection. */
        val nextCseq: Int,
    )

    sealed class Failure(message: String) : Exception(message) {
        /** The receiver answered a pairing request with a non-200 status. */
        class Refused(val step: String, val status: Int) : Failure("receiver refused $step with $status")

        class Malformed(detail: String) : Failure("malformed pairing reply: $detail")

        /** The receiver's signature did not verify against the key it gave in pair-setup. */
        class BadSignature : Failure("the receiver's pair-verify signature did not verify")
    }

    fun run(): Result {
        // ---- pair-setup: swap long-term Ed25519 keys.
        val setup = connection.exchange(request("/pair-setup", identity.publicKey))
        if (!setup.isSuccess) throw Failure.Refused("pair-setup", setup.status)
        if (setup.body.size != KEY_BYTES) throw Failure.Malformed("pair-setup returned ${setup.body.size} bytes")
        val theirEd = setup.body

        // ---- pair-verify 1: swap ephemeral X25519 keys.
        val ephemeral = HapCrypto.X25519KeyPair.generate()
        val first = connection.exchange(
            request("/pair-verify", byteArrayOf(1, 0, 0, 0) + ephemeral.publicKey + identity.publicKey)
        )
        if (!first.isSuccess) throw Failure.Refused("pair-verify 1", first.status)
        if (first.body.size != KEY_BYTES + SIGNATURE_BYTES) {
            throw Failure.Malformed("pair-verify 1 returned ${first.body.size} bytes")
        }
        val theirX = first.body.copyOfRange(0, KEY_BYTES)
        val secret = ephemeral.agree(theirX)

        val keystream = keystream(secret)
        val theirSignature = keystream.update(first.body.copyOfRange(KEY_BYTES, KEY_BYTES + SIGNATURE_BYTES))
        if (!HapCrypto.ed25519Verify(theirEd, theirX + ephemeral.publicKey, theirSignature)) {
            throw Failure.BadSignature()
        }

        // ---- pair-verify 2: our signature, on the same keystream.
        val ours = keystream.update(identity.sign(ephemeral.publicKey + theirX))
        val second = connection.exchange(request("/pair-verify", byteArrayOf(0, 0, 0, 0) + ours))
        if (!second.isSuccess) throw Failure.Refused("pair-verify 2", second.status)

        return Result(secret, theirEd, cseq)
    }

    private fun keystream(secret: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(sha512(KEY_SALT, secret).copyOf(16), "AES"),
                IvParameterSpec(sha512(IV_SALT, secret).copyOf(16)),
            )
        }

    private fun request(uri: String, body: ByteArray) = AirPlayRequest(
        method = "POST",
        uri = uri,
        protocol = AirPlayRequest.RTSP_1_0,
        headers = listOf(
            "CSeq" to "${cseq++}",
            "User-Agent" to userAgent,
            "Content-Type" to "application/octet-stream",
        ),
        body = body,
    )

    companion object {
        const val DEFAULT_USER_AGENT = "AirPlay/220.68"
        private const val KEY_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private val KEY_SALT = "Pair-Verify-AES-Key".toByteArray()
        private val IV_SALT = "Pair-Verify-AES-IV".toByteArray()

        internal fun sha512(vararg chunks: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            chunks.forEach { digest.update(it) }
            return digest.digest()
        }
    }
}
