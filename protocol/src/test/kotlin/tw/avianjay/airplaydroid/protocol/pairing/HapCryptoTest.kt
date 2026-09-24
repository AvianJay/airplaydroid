package tw.avianjay.airplaydroid.protocol.pairing

import org.bouncycastle.crypto.InvalidCipherTextException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HapCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `seal matches the RFC 8439 AEAD test vector`() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray()

        val sealed = HapCrypto.seal(key, nonce, plaintext, aad)

        assertEquals(plaintext.size + 16, sealed.size)
        assertContentEquals(hex("d31a8d34648e60db7b86afbc53ef7ec2"), sealed.copyOfRange(0, 16))
        assertContentEquals(hex("1ae10b594f09e26a7e902ecbd0600691"), sealed.copyOfRange(sealed.size - 16, sealed.size))
        assertContentEquals(plaintext, HapCrypto.open(key, nonce, sealed, aad))
    }

    @Test
    fun `open rejects a tampered message`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = HapCrypto.labelNonce("PS-Msg05")
        val sealed = HapCrypto.seal(key, nonce, "hello".toByteArray())
        sealed[0] = (sealed[0].toInt() xor 1).toByte()

        assertFailsWith<InvalidCipherTextException> { HapCrypto.open(key, nonce, sealed) }
    }

    @Test
    fun `label nonces are four zero bytes then the label`() {
        assertContentEquals(ByteArray(4) + "PV-Msg02".toByteArray(), HapCrypto.labelNonce("PV-Msg02"))
    }

    @Test
    fun `ed25519 signatures verify only for the signed message`() {
        val keys = HapCrypto.Ed25519KeyPair.generate()
        val signature = keys.sign("info".toByteArray())

        assertTrue(HapCrypto.ed25519Verify(keys.publicKey, "info".toByteArray(), signature))
        assertFalse(HapCrypto.ed25519Verify(keys.publicKey, "other".toByteArray(), signature))
    }

    @Test
    fun `x25519 agreement is symmetric`() {
        val a = HapCrypto.X25519KeyPair.generate()
        val b = HapCrypto.X25519KeyPair.generate()

        assertContentEquals(a.agree(b.publicKey), b.agree(a.publicKey))
    }
}
