package tw.avianjay.airplaydroid.protocol.cast

/**
 * The `urn:x-cast:com.google.cast.tp.deviceauth` exchange: the sender sends an
 * `AuthChallenge`, the receiver answers with an `AuthResponse` carrying a
 * signature over `sender_nonce || peer_certificate`.
 *
 * ```
 * message DeviceAuthMessage {
 *   optional AuthChallenge challenge = 1;
 *   optional AuthResponse response = 2;
 *   optional AuthError error = 3;
 * }
 * message AuthChallenge {
 *   optional SignatureAlgorithm signature_algorithm = 1;  // RSASSA_PKCS1v15 = 1
 *   optional bytes sender_nonce = 2;
 *   optional HashAlgorithm hash_algorithm = 3;             // SHA1 = 0, SHA256 = 1
 * }
 * message AuthResponse {
 *   required bytes signature = 1;
 *   required bytes client_auth_certificate = 2;
 *   repeated bytes intermediate_certificate = 3;
 *   optional SignatureAlgorithm signature_algorithm = 4;
 *   optional bytes sender_nonce = 5;
 *   optional HashAlgorithm hash_algorithm = 6;
 * }
 * ```
 *
 * ### What this can and cannot prove
 *
 * A real Chromecast signs with a device key whose certificate chains to
 * Google's Cast root. This receiver signs with its own self-signed key
 * ([CastIdentity]), because that is the only key it honestly has. The answer is
 * therefore well-formed but **not** Google-signed, and it has two audiences:
 *
 *  - Senders that do not verify the chain -- VLC, pychromecast and the other
 *    open implementations -- accept it and carry on.
 *  - The Google Cast SDK (Chrome, and every Android/iOS app built on it)
 *    verifies the chain and drops the connection. No app can change that; it is
 *    the SDK's decision, not the protocol's.
 */
object DeviceAuth {

    enum class HashAlgorithm(val wire: Long, val jcaSignature: String) {
        SHA1(0, "SHA1withRSA"),
        SHA256(1, "SHA256withRSA"),
    }

    class Challenge(val senderNonce: ByteArray, val hashAlgorithm: HashAlgorithm)

    /** The challenge in [payload], or null when the message is not a challenge. */
    fun parseChallenge(payload: ByteArray): Challenge? {
        var challenge: ByteArray? = null
        ProtoReader(payload).forEachField { field, value ->
            if (field == 1) challenge = value.bytes()
        }
        val body = challenge ?: return null
        var nonce = ByteArray(0)
        var hash = HashAlgorithm.SHA1
        ProtoReader(body).forEachField { field, value ->
            when (field) {
                2 -> nonce = value.bytes()
                3 -> hash = if (value.varint() == HashAlgorithm.SHA256.wire) HashAlgorithm.SHA256 else HashAlgorithm.SHA1
            }
        }
        return Challenge(nonce, hash)
    }

    /** A `DeviceAuthMessage` carrying an `AuthResponse`. */
    fun response(challenge: Challenge, signature: ByteArray, certificate: ByteArray): ByteArray =
        ProtoWriter().apply {
            message(2) {
                bytes(1, signature)
                bytes(2, certificate)
                varint(4, SIGNATURE_RSASSA_PKCS1V15)
                if (challenge.senderNonce.isNotEmpty()) bytes(5, challenge.senderNonce)
                varint(6, challenge.hashAlgorithm.wire)
            }
        }.toByteArray()

    /** What a receiver signs: the sender's nonce, then the TLS certificate it presented. */
    fun signedData(challenge: Challenge, peerCertificate: ByteArray): ByteArray =
        challenge.senderNonce + peerCertificate

    private const val SIGNATURE_RSASSA_PKCS1V15 = 1L
}
