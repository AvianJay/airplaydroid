package tw.avianjay.airplaydroid.protocol.pairing

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SRP for the legacy AirPlay PIN flow, faithful to what a working client does.
 *
 * This is NOT textbook SRP-6a, and the deviations are the whole reason a
 * by-the-book implementation is answered `500`. Three of them matter:
 *
 *  1. **The hash is SHA-1**, so proofs are 20 bytes, not 64.
 *  2. **`K` is doubled**: `K = H(S ‖ 00000000) ‖ H(S ‖ 00000001)`, giving 40
 *     bytes rather than the usual `H(S)`. This is an Apple-specific override.
 *  3. **Integers are hashed at minimal length, not padded to the modulus** --
 *     except inside `u`, which does pad. So a salt or a public key with a
 *     leading zero byte is hashed one byte shorter, and getting that wrong
 *     fails roughly one time in 256 with no other symptom.
 *
 * `a` is not random either: it is the client's own long-term private key, so
 * the same client always presents the same `A`.
 */
object LegacyAirPlaySrp {

    class Session(
        /** `A`, minimal-length, as sent in the `pk` field. */
        val publicKey: ByteArray,
        /** `M`, 20 bytes, as sent in the `proof` field. */
        val proof: ByteArray,
        /** `K`, 40 bytes, the input to the step-three AES keys. */
        val sessionKey: ByteArray,
    )

    private val N = BigInteger(PRIME_2048_HEX, 16)
    private val g = BigInteger.TWO
    private const val WIDTH = 256

    fun compute(
        username: String,
        pin: String,
        salt: ByteArray,
        serverPublicKey: ByteArray,
        clientPrivateKey: ByteArray,
    ): Session {
        val B = BigInteger(1, serverPublicKey)
        require(B.mod(N).signum() != 0) { "server public key is zero mod N" }

        val saltInt = BigInteger(1, salt)
        val a = BigInteger(1, clientPrivateKey)
        val A = g.modPow(a, N)

        val k = BigInteger(1, sha1(minimal(N), pad(g)))
        val x = BigInteger(1, sha1(minimal(saltInt), sha1("$username:$pin".toByteArray())))
        val u = BigInteger(1, sha1(pad(A), pad(B)))

        val v = g.modPow(x, N)
        val S = B.subtract(k.multiply(v)).modPow(a.add(u.multiply(x)), N)

        // Apple's doubled session key.
        val sBytes = minimal(S)
        val sessionKey = sha1(sBytes, byteArrayOf(0, 0, 0, 0)) +
            sha1(sBytes, byteArrayOf(0, 0, 0, 1))

        // H(N) and H(g) are XORed as INTEGERS, then hashed at minimal length.
        val hN = BigInteger(1, sha1(minimal(N)))
        val hg = BigInteger(1, sha1(minimal(g)))
        val hUser = BigInteger(1, sha1(username.toByteArray()))

        val proof = sha1(
            minimal(hN.xor(hg)),
            minimal(hUser),
            minimal(saltInt),
            minimal(A),
            minimal(B),
            sessionKey,
        )

        return Session(minimal(A), proof, sessionKey)
    }

    /** Big-endian with no leading zero bytes, never empty -- Python's `int_to_bytes`. */
    private fun minimal(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
        return bytes.copyOfRange(start, bytes.size)
    }

    /** Left-padded to the modulus width, used only inside `u` and `k`. */
    private fun pad(value: BigInteger): ByteArray {
        val bytes = minimal(value)
        if (bytes.size >= WIDTH) return bytes
        return ByteArray(WIDTH - bytes.size) + bytes
    }

    private fun sha1(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    /** RFC 5054 2048-bit group, g = 2. */
    private const val PRIME_2048_HEX =
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
        "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
        "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
        "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
        "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
        "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
        "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
        "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"
}
