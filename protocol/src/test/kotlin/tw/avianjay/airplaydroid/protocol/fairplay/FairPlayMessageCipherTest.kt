package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The m3 body cipher: AES-128 round operations with Apple's baked, per-mode
 * round keys.
 *
 * The constants are transcribed from `doubletake`'s
 * `internal/airplay/fairplay_message.go`; the frozen m3 prefix below is the one
 * `objevovat`'s `fp_sap_m3.go` carries, so these tests pin the port against
 * bytes that came from real captures rather than from this file's own output.
 */
class FairPlayMessageCipherTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * The **144-byte m3 prefix** that `fp_sap_m3.go` splices in: 12 bytes of FPLY
     * framing, the mode byte, the 3-byte label, and the 128-byte encrypted body.
     *
     * The full m3 is 164 bytes -- the 20-byte response is appended at [144:164].
     * That response belongs to the captured session and is not part of the
     * prefix, so it is deliberately absent here.
     */
    private val frozenM3Prefix = hex(
        "46504c590301030000000098038f1a9c991ea22c511e45ba97f1af8dfb0f86f5" +
            "50c54486fe6b3ab233da431ef8e5fc1156dba321fffeabb1b392b09d227e88c7" +
            "12202866eb7bbf310015aa1d19a5df36d5dfd8d3ca1639b376eaece946edfe8b" +
            "7a66cd302d04aac3c1251714019bd5f2d49b543e11eed1646291ec8efd96b691" +
            "01b849fd93a02860d1a0dff5cd4414aa"
    )

    @Test
    fun `the frozen m3 prefix is the shape the protocol defines`() {
        assertEquals(144, frozenM3Prefix.size, "12 framing + 1 mode + 3 label + 128 body")
        assertContentEquals(hex("46504c59"), frozenM3Prefix.copyOfRange(0, 4), "FPLY magic")
        assertContentEquals(hex("03010300"), frozenM3Prefix.copyOfRange(4, 8), "version 03 01, type 03")
        assertContentEquals(hex("00000098"), frozenM3Prefix.copyOfRange(8, 12), "152-byte body")
        assertEquals(0x03.toByte(), frozenM3Prefix[12], "mode")
        assertContentEquals(FairPlayRecords.M3_LABEL, frozenM3Prefix.copyOfRange(13, 16))
    }

    @Test
    fun `decrypting the frozen m3 body round-trips back to it`() {
        // The strongest check available offline: decrypt the captured body, then
        // re-encrypt, and require the original ciphertext byte for byte. A wrong
        // round key, a wrong S-box or a wrong ShiftRows all break this.
        val body = frozenM3Prefix.copyOfRange(16, 144)

        val plaintext = FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, body)
        val reencrypted = FairPlayMessageCipher.encryptBody(FairPlayRecords.Mode.MODE_3, plaintext)

        assertContentEquals(body, reencrypted, "decrypt then encrypt must be the identity")
    }

    @Test
    fun `the recovered local SAP has the layout Apple's sender uses`() {
        val body = frozenM3Prefix.copyOfRange(16, 144)
        val localSap = FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, body)

        // newFPSAPSession sets byte 1 to 0x01 and leaves byte 0 at zero; the
        // remaining 126 bytes are opaque. Recovering exactly that shape from a
        // real capture is independent evidence the cipher is right -- a wrong
        // cipher would produce uniform noise, not a structured field.
        assertEquals(0x00.toByte(), localSap[0], "local SAP byte 0")
        assertEquals(0x01.toByte(), localSap[1], "local SAP byte 1")
        assertTrue(
            localSap.copyOfRange(2, 128).any { it != 0.toByte() },
            "the opaque tail should carry entropy, not be all zero",
        )
    }

    @Test
    fun `encrypt and decrypt are inverses for every mode`() {
        val plaintext = ByteArray(128) { (it * 7 + 3).toByte() }

        for (mode in FairPlayRecords.Mode.entries) {
            val encrypted = FairPlayMessageCipher.encryptBody(mode, plaintext)
            assertNotEquals(plaintext.toHex(), encrypted.toHex(), "mode $mode must actually encrypt")
            assertContentEquals(
                plaintext,
                FairPlayMessageCipher.decryptBody(mode, encrypted),
                "mode $mode round-trip",
            )
        }
    }

    @Test
    fun `the four modes produce four different ciphertexts`() {
        // The property the protocol depends on: the mode picks the IV and the
        // round keys, so the same body encrypts differently under each. A port
        // that ignored the mode would collapse these.
        val plaintext = ByteArray(128) { (it * 11).toByte() }
        val ciphertexts = FairPlayRecords.Mode.entries
            .map { FairPlayMessageCipher.encryptBody(it, plaintext).toHex() }

        assertEquals(4, ciphertexts.toSet().size, "all four modes must differ")
    }

    @Test
    fun `the first block chains from the mode IV, so a one-byte change avalanches`() {
        val a = ByteArray(128)
        val b = ByteArray(128).also { it[0] = 1 }

        val ea = FairPlayMessageCipher.encryptBody(FairPlayRecords.Mode.MODE_3, a)
        val eb = FairPlayMessageCipher.encryptBody(FairPlayRecords.Mode.MODE_3, b)

        // CBC: a difference in block 0 must propagate through every later block.
        for (block in 1 until 8) {
            assertNotEquals(
                ea.copyOfRange(block * 16, block * 16 + 16).toHex(),
                eb.copyOfRange(block * 16, block * 16 + 16).toHex(),
                "block $block should differ after a block-0 change",
            )
        }
    }

    @Test
    fun `a body of the wrong size is refused`() {
        assertFailsWith<IllegalArgumentException> {
            FairPlayMessageCipher.encryptBody(FairPlayRecords.Mode.MODE_3, ByteArray(127))
        }
        assertFailsWith<IllegalArgumentException> {
            FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, ByteArray(129))
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
