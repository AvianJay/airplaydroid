package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The 72-byte `FPLY` key record, and its layout checked against real captures.
 *
 * Two captures from the unofficial spec were decoded by hand and agree field for
 * field, which is what the layout assertions below pin:
 *
 * - a RAOP `a=fpaeskey` (ALAC audio, from `ANNOUNCE`)
 * - a mirroring `POST /stream` `param1`
 *
 * Both are 72 bytes with the `FPLY` magic, a `0x3c` body length, and a
 * big-endian 16 at offset 32. The *layout* is therefore evidenced; the produced
 * *values* are not (no receiver has accepted one), and the tests below are
 * written to make that distinction visible.
 */
class FairPlayKeyWrapTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** A RAOP `a=fpaeskey` decoded from the spec. */
    private val realRaopKey = hex(
        "46504c59010201000000003c00000000" +
            "f14e9cd7becd66f9fe7e0be4a6641360" +
            "00000010" +
            "943c7af6b7937701c5f4b68d9a18915114c06dc2" +
            "f86eb60071e024678f588ab5e6eb6378"
    )

    /** A mirroring `POST /stream` `param1` decoded from the spec. */
    private val realMirrorKey = hex(
        "46504c59010201000000003c00000000" +
            "dbcab838b376eb332f5846f48bc893e0" +
            "00000010" +
            "ba474f279270fe0181025db65997459bdba36441" +
            "8857b8e6dd90725a6e751e3a43c23618"
    )

    @Test
    fun `both real captures are 72 bytes with the same header`() {
        assertEquals(72, realRaopKey.size)
        assertEquals(72, realMirrorKey.size)

        val expectedHeader = hex("46504c59010201000000003c00000000")
        assertContentEquals(expectedHeader, realRaopKey.copyOfRange(0, 16), "RAOP header")
        assertContentEquals(expectedHeader, realMirrorKey.copyOfRange(0, 16), "mirroring header")
    }

    @Test
    fun `both real captures carry a big-endian 16 at offset 32`() {
        // The raw-key length field. RAOP audio and mirroring share it, which is
        // the evidence that the two paths use one record format.
        assertContentEquals(hex("00000010"), realRaopKey.copyOfRange(32, 36), "RAOP length field")
        assertContentEquals(hex("00000010"), realMirrorKey.copyOfRange(32, 36), "mirroring length field")
    }

    @Test
    fun `the header constant matches what the captures carry`() {
        val wrapped = FairPlayKeyWrap.wrap(
            ByteArray(128), ByteArray(128), ByteArray(16),
            SecureRandom.getInstanceStrong(),
        )
        assertContentEquals(realRaopKey.copyOfRange(0, 16), wrapped.copyOfRange(0, 16))
    }

    @Test
    fun `a wrapped record has the documented field layout`() {
        val rawKey = ByteArray(16) { (it + 1).toByte() }
        val receiverSap = ByteArray(128) { (it * 3).toByte() }
        val localSap = ByteArray(128).also { it[1] = 0x01; it[4] = 0x5a }

        val record = FairPlayKeyWrap.wrap(receiverSap, localSap, rawKey, SecureRandom.getInstanceStrong())

        assertEquals(72, record.size)
        assertContentEquals(hex("46504c59"), record.copyOfRange(0, 4), "FPLY magic")
        assertEquals(0x3c, record[11].toInt() and 0xFF, "body length 0x3c at offset 11")
        assertContentEquals(hex("00000010"), record.copyOfRange(32, 36), "raw-key length")
        assertTrue(record.copyOfRange(16, 32).any { it != 0.toByte() }, "the mask should be random")
    }

    @Test
    fun `the mask makes two wraps of the same key differ`() {
        // The mask is per-key random, so the same raw key must not produce the
        // same record twice -- otherwise the record would be replayable.
        val rawKey = ByteArray(16) { 0x42 }
        val receiverSap = ByteArray(128) { it.toByte() }
        val localSap = ByteArray(128).also { it[1] = 0x01 }

        val a = FairPlayKeyWrap.wrap(receiverSap, localSap, rawKey)
        val b = FairPlayKeyWrap.wrap(receiverSap, localSap, rawKey)

        assertNotEquals(a.copyOfRange(16, 32).toHex(), b.copyOfRange(16, 32).toHex(), "masks must differ")
        assertNotEquals(a.copyOfRange(56, 72).toHex(), b.copyOfRange(56, 72).toHex(), "wrapped keys must differ")
        // The MAC covers the mask, so it differs too.
        assertNotEquals(a.copyOfRange(36, 56).toHex(), b.copyOfRange(36, 56).toHex(), "MACs must differ")
    }

    @Test
    fun `the MAC covers the header, mask, length and raw key`() {
        // Change the raw key only; the MAC must move.
        val receiverSap = ByteArray(128) { it.toByte() }
        val localSap = ByteArray(128).also { it[1] = 0x01 }
        val random = SecureRandom.getInstanceStrong()

        val a = FairPlayKeyWrap.wrap(receiverSap, localSap, ByteArray(16) { 1 }, random)
        val b = FairPlayKeyWrap.wrap(receiverSap, localSap, ByteArray(16) { 2 }, random)
        assertNotEquals(a.copyOfRange(36, 56).toHex(), b.copyOfRange(36, 56).toHex())
    }

    @Test
    fun `the wrapping key depends on both SAPs`() {
        val receiverSap = ByteArray(128) { it.toByte() }
        val localSapA = ByteArray(128).also { it[1] = 0x01; it[2] = 0x11 }
        val localSapB = ByteArray(128).also { it[1] = 0x01; it[2] = 0x22 }

        val a = FairPlayKeyWrap.deriveWrappingKey(receiverSap, localSapA)
        val b = FairPlayKeyWrap.deriveWrappingKey(receiverSap, localSapB)
        assertEquals(16, a.size)
        assertNotEquals(a.toHex(), b.toHex(), "the wrapping key must depend on the local SAP")

        val receiverSapB = ByteArray(128) { (it + 1).toByte() }
        val c = FairPlayKeyWrap.deriveWrappingKey(receiverSapB, localSapA)
        assertNotEquals(a.toHex(), c.toHex(), "the wrapping key must depend on the receiver SAP")
    }

    @Test
    fun `the wrapping key is deterministic`() {
        val receiverSap = ByteArray(128) { it.toByte() }
        val localSap = ByteArray(128).also { it[1] = 0x01 }
        assertContentEquals(
            FairPlayKeyWrap.deriveWrappingKey(receiverSap, localSap),
            FairPlayKeyWrap.deriveWrappingKey(receiverSap, localSap),
        )
    }

    @Test
    fun `wrongly sized inputs are refused`() {
        assertFailsWith<IllegalArgumentException> {
            FairPlayKeyWrap.wrap(ByteArray(128), ByteArray(128), ByteArray(15))
        }
        assertFailsWith<IllegalArgumentException> {
            FairPlayKeyWrap.deriveWrappingKey(ByteArray(127), ByteArray(128))
        }
        assertFailsWith<IllegalArgumentException> {
            FairPlayKeyWrap.deriveWrappingKey(ByteArray(128), ByteArray(129))
        }
    }
}
