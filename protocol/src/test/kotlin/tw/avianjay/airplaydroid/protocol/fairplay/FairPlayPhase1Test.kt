package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the relationship between Phase 1's GP buffer and the receiver's m2 SAP.
 *
 * These two are easy to conflate, and the consequence of conflating them is a
 * well-formed m3 that every receiver rejects. The chain is:
 *
 * ```
 * gp     = FairPlayWhiteBox.phase1(m2 challenge)        <- Phase 1, from the challenge
 * body   = decryptBody(mode, m2 challenge)              <- the receiver's own SAP
 * x9     = bridgeX9HeadForSap(our localSap, gp)         <- needs OUR sap, not the receiver's
 * ```
 *
 * So `gp` is **not** the receiver's SAP, and neither is it the challenge. Both
 * facts are asserted here, against the captured m2 from real hardware.
 */
class FairPlayPhase1Test {

    /** The captured m2 body: 12 bytes framing, mode, then the 128-byte challenge. */
    private val m2Body = hex(
        "46504c5903010200000000820203" +
            "9001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0" +
            "011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f48148" +
            "7e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce4431" +
            "1e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359"
    )

    private val challenge = m2Body.copyOfRange(14, 142)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `phase 1 produces a full 128-byte buffer`() {
        val gp = FairPlayWhiteBox.phase1(challenge)
        assertEquals(128, gp.size)
        assertTrue(gp.any { it != 0.toByte() }, "the buffer should not be all zero")
    }

    @Test
    fun `phase 1 is deterministic`() {
        assertContentEquals(
            FairPlayWhiteBox.phase1(challenge),
            FairPlayWhiteBox.phase1(challenge),
        )
    }

    @Test
    fun `phase 1 output is not the challenge and not the receiver's SAP`() {
        // The distinction that matters. A port that returned the challenge
        // unchanged, or the decrypted SAP, would still hand the bridge 128 bytes.
        val gp = FairPlayWhiteBox.phase1(challenge)
        val m2Sap = FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, challenge)

        assertNotEquals(challenge.toHex(), gp.toHex(), "gp must not be the raw challenge")
        assertNotEquals(m2Sap.toHex(), gp.toHex(), "gp must not be the receiver's decrypted SAP")
    }

    @Test
    fun `a one-byte challenge change alters the whole buffer`() {
        // Avalanche: a white-box cipher that ignored part of its input would
        // still produce a full-looking buffer.
        val gp = FairPlayWhiteBox.phase1(challenge)
        val flipped = challenge.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val gpFlipped = FairPlayWhiteBox.phase1(flipped)

        assertNotEquals(gp.toHex(), gpFlipped.toHex())
        val differingBlocks = (0 until 8).count { block ->
            !gp.copyOfRange(block * 16, block * 16 + 16)
                .contentEquals(gpFlipped.copyOfRange(block * 16, block * 16 + 16))
        }
        assertTrue(differingBlocks > 1, "expected more than one block to change, got $differingBlocks")
    }

    @Test
    fun `the receiver's decrypted SAP has the layout Apple's sender uses`() {
        // Independent evidence the message cipher is right: a wrong cipher would
        // yield uniform noise rather than this structured head.
        val m2Sap = FairPlayMessageCipher.decryptBody(FairPlayRecords.Mode.MODE_3, challenge)
        assertEquals(0x00.toByte(), m2Sap[0], "local SAP byte 0")
        assertEquals(0x01.toByte(), m2Sap[1], "local SAP byte 1")
    }

    @Test
    fun `a challenge of the wrong size is refused`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            FairPlayWhiteBox.phase1(ByteArray(127))
        }
    }
}
