package tw.avianjay.airplaydroid.protocol.pairing

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Tlv8Test {

    @Test
    fun `round-trips simple entries`() {
        val encoded = Tlv8.encode(
            Tlv8.METHOD to Tlv8.byte(0),
            Tlv8.STATE to Tlv8.byte(1),
            Tlv8.FLAGS to Tlv8.byte(Tlv8.FLAG_TRANSIENT),
        )

        // Byte-exact against the M1 a real iOS sender sends.
        assertContentEquals(
            byteArrayOf(0x00, 0x01, 0x00, 0x06, 0x01, 0x01, 0x13, 0x01, 0x10),
            encoded,
        )
        assertEquals(9, encoded.size)

        val decoded = Tlv8.decode(encoded)
        assertEquals(1, Tlv8.stateOf(decoded))
        assertEquals(0x10, decoded[Tlv8.FLAGS]!![0].toInt())
    }

    @Test
    fun `a 384-byte key is fragmented as 255 plus 129 and reassembled`() {
        val key = ByteArray(384) { (it % 251).toByte() }

        val encoded = Tlv8.encode(Tlv8.PUBLIC_KEY to key)

        // Two entries of the same type: 2+255 and 2+129.
        assertEquals(2 + 255 + 2 + 129, encoded.size)
        assertEquals(255, encoded[1].toInt() and 0xFF)
        assertEquals(129, encoded[2 + 255 + 1].toInt() and 0xFF)

        assertContentEquals(key, Tlv8.decode(encoded)[Tlv8.PUBLIC_KEY])
    }

    @Test
    fun `an M3 body is the size the receiver expects`() {
        // State(3) + PublicKey(388 fragmented) + Proof(66) = 457, as captured.
        val body = Tlv8.encode(
            Tlv8.STATE to Tlv8.byte(3),
            Tlv8.PUBLIC_KEY to ByteArray(384),
            Tlv8.PROOF to ByteArray(64),
        )

        assertEquals(457, body.size)
    }

    @Test
    fun `errors decode to named values`() {
        val tlv = Tlv8.decode(Tlv8.encode(Tlv8.ERROR to Tlv8.byte(3)))

        assertEquals(Tlv8.PairError.BACKOFF, Tlv8.errorOf(tlv))
    }

    @Test
    fun `an empty value encodes as a zero-length entry`() {
        assertContentEquals(byteArrayOf(0x06, 0x00), Tlv8.encode(Tlv8.STATE to ByteArray(0)))
    }

    @Test
    fun `truncated input does not throw`() {
        Tlv8.decode(byteArrayOf(0x06, 0x05, 0x01))   // claims 5 bytes, has 1
        Tlv8.decode(byteArrayOf(0x06))
    }
}

/**
 * Verifies the client against a matching SRP-6a *server*, so a wrong proof
 * cannot pass by agreeing with itself. The server half here follows RFC 5054
 * independently of the client implementation.
 */
class Srp6aClientTest {

    private val N = BigInteger(
        1,
        ("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
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
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    )
    private val g = BigInteger.valueOf(5)
    private val width = 384

    private fun h(vararg c: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-512")
        c.forEach { d.update(it) }
        return d.digest()
    }

    private fun pad(v: BigInteger): ByteArray {
        var b = v.toByteArray()
        if (b.size > width && b[0] == 0.toByte()) b = b.copyOfRange(b.size - width, b.size)
        return if (b.size == width) b else ByteArray(width - b.size) + b
    }

    @Test
    fun `the client proof verifies against an independent server, and the server proof round-trips`() {
        val username = "Pair-Setup"
        val password = "5535"
        val salt = ByteArray(16) { it.toByte() }

        // --- server side ---
        val x = BigInteger(1, h(salt, h("$username:$password".toByteArray())))
        val v = g.modPow(x, N)
        val k = BigInteger(1, h(pad(N), pad(g)))
        val b = BigInteger(1, ByteArray(32).also { SecureRandom().nextBytes(it) })
        val B = k.multiply(v).add(g.modPow(b, N)).mod(N)

        // --- client side, the code under test ---
        val session = Srp6aClient(username).start(password, salt, pad(B))

        // --- server verifies ---
        val A = BigInteger(1, session.publicKey)
        val u = BigInteger(1, h(pad(A), pad(B)))
        val serverS = A.multiply(v.modPow(u, N)).mod(N).modPow(b, N)
        val serverK = h(pad(serverS))

        val hN = h(pad(N)); val hg = h(pad(g))
        val xor = ByteArray(hN.size) { (hN[it].toInt() xor hg[it].toInt()).toByte() }
        val expectedM1 = h(xor, h(username.toByteArray()), salt, pad(A), pad(B), serverK)

        assertContentEquals(expectedM1, session.clientProof)
        assertContentEquals(serverK, session.sharedSecret)

        // and the server's reply is what the client is prepared to accept
        assertContentEquals(h(pad(A), expectedM1, serverK), session.expectedServerProof)
    }

    @Test
    fun `a wrong password produces a different proof`() {
        val salt = ByteArray(16) { 7 }
        val serverKey = ByteArray(384) { ((it * 31) % 251 + 1).toByte() }

        val right = Srp6aClient().start("5535", salt, serverKey)
        val wrong = Srp6aClient().start("5536", salt, serverKey)

        assertTrue(!right.clientProof.contentEquals(wrong.clientProof))
    }

    @Test
    fun `sizes match the wire format`() {
        val session = Srp6aClient().start(
            "3939", ByteArray(16), ByteArray(384) { 1 },
        )

        assertEquals(384, session.publicKey.size)       // A
        assertEquals(64, session.clientProof.size)      // SHA-512
        assertEquals(64, session.expectedServerProof.size)
        assertEquals(64, session.sharedSecret.size)
    }

    @Test
    fun `a zero server key is rejected rather than used`() {
        assertFailsWith<IllegalArgumentException> {
            Srp6aClient().start("3939", ByteArray(16), ByteArray(384))
        }
    }
}
