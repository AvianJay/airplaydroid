package tw.avianjay.airplaydroid.protocol.pairing

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a client for HomeKit pair-setup.
 *
 * Parameters were read off the wire from a real Apple TV rather than taken on
 * trust: the 384-byte server public key fixes the group at RFC 5054 **3072-bit**,
 * the 16-byte salt and 64-byte proofs fix the hash at **SHA-512**.
 *
 * Every value hashed into `u`, `M1` and `K` is left-padded to the modulus width.
 * That only differs from the unpadded form when a value happens to have leading
 * zero bytes, which is exactly the kind of once-in-256 bug that is impossible to
 * reproduce on demand, so it is done consistently here.
 */
class Srp6aClient(
    private val username: String = "Pair-Setup",
    private val random: SecureRandom = SecureRandom(),
    /**
     * HomeKit pair-setup uses the 3072-bit group with SHA-512. The older AirPlay
     * PIN flow on /pair-setup-pin uses the **2048-bit** group with SHA-1 -- the
     * 256-byte public key it returns is what gives that away.
     */
    private val group: Group = Group.RFC5054_3072_SHA512,
    /**
     * How the private key `x` is derived. RFC 5054 folds the username in;
     * Apple's older AirPlay PIN flow is reported to hash the PIN alone. The wire
     * does not reveal which, so it is selectable.
     */
    private val xMode: XMode = XMode.WITH_USERNAME,
) {

    enum class XMode { WITH_USERNAME, PIN_ONLY }

    enum class Group(val modulusHex: String, val generator: Int, val hash: String) {
        RFC5054_3072_SHA512(N_HEX_3072, 5, "SHA-512"),
        RFC5054_2048_SHA1(N_HEX_2048, 2, "SHA-1"),
        RFC5054_2048_SHA512(N_HEX_2048, 2, "SHA-512"),
    }

    private val N = BigInteger(1, hexToBytes(group.modulusHex))
    private val g = BigInteger.valueOf(group.generator.toLong())
    private val width = (N.bitLength() + 7) / 8

    /** Result of the client half: what to send, and what the server must prove back. */
    class Session(
        val publicKey: ByteArray,      // A
        val clientProof: ByteArray,    // M1
        val expectedServerProof: ByteArray, // M2, to verify the receiver's reply
        val sharedSecret: ByteArray,   // K, the session key everything else derives from
    )

    fun start(password: String, salt: ByteArray, serverPublicKey: ByteArray): Session {
        val B = BigInteger(1, serverPublicKey)
        require(B.mod(N) != BigInteger.ZERO) { "server public key is zero mod N" }

        val a = BigInteger(1, ByteArray(32).also { random.nextBytes(it) })
        val A = g.modPow(a, N)

        val k = BigInteger(1, sha512(pad(N), pad(g)))
        val inner = when (xMode) {
            XMode.WITH_USERNAME -> sha512((username + ":" + password).toByteArray())
            XMode.PIN_ONLY -> sha512(password.toByteArray())
        }
        val x = BigInteger(1, sha512(salt, inner))
        val u = BigInteger(1, sha512(pad(A), pad(B)))

        val S = B.subtract(k.multiply(g.modPow(x, N))).mod(N).modPow(a.add(u.multiply(x)), N)
        val K = sha512(pad(S))

        val hN = sha512(pad(N))
        val hg = sha512(pad(g))
        val xor = ByteArray(hN.size) { (hN[it].toInt() xor hg[it].toInt()).toByte() }

        val m1 = sha512(xor, sha512(username.toByteArray()), salt, pad(A), pad(B), K)
        val m2 = sha512(pad(A), m1, K)

        return Session(pad(A), m1, m2, K)
    }

    private fun pad(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        // BigInteger.toByteArray() prepends a sign byte for values with the high
        // bit set; strip it, then left-pad to the modulus width.
        val trimmed = if (bytes.size > width && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(bytes.size - width, bytes.size)
        } else {
            bytes
        }
        if (trimmed.size == width) return trimmed
        return ByteArray(width - trimmed.size) + trimmed
    }

    private fun sha512(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(group.hash)
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    companion object {
        /** RFC 5054 3072-bit group, the one HomeKit uses. */
        /** RFC 5054 2048-bit group, g = 2, used by the legacy AirPlay PIN flow. */
        private const val N_HEX_2048 =
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"

        private const val N_HEX_3072 =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
            "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
            "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or
                    Character.digit(hex[it * 2 + 1], 16)).toByte()
            }
    }
}
