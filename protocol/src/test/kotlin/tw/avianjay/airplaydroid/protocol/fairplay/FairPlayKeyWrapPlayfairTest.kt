package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [FairPlayKeyWrap] against records a **receiver** has unwrapped.
 *
 * Each row of `keywrap_playfair_verified.csv` is an m3, the `ekey` this code
 * wrapped for it, and the raw key. The rows were fed to `playfair_decrypt` -- the
 * receiver half of FairPlay that RPiPlay, UxPlay and the dongles built on them
 * run, here in airplay2-receiver's Python port -- and every `ekey` unwrapped to
 * its raw key (8/8). That is the only independent oracle for the wrap offline;
 * the layout tests in [FairPlayKeyWrapTest] cannot tell a right key from a wrong
 * one.
 *
 * The oracle is not vendored (6,000 lines of tables), so this test pins the
 * verified output instead: it rebuilds each record from the same inputs and
 * requires the same bytes. The inputs are recoverable from the row itself -- the
 * local SAP from the m3 body, the receiver SAP from the captured m2, the mask
 * from `ekey[16:32]` -- so nothing else had to be recorded.
 */
class FairPlayKeyWrapPlayfairTest {

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/fairplay/$name")!!.use { it.readBytes() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A "random" source that yields [mask], so the wrap is reproducible. */
    private fun fixedMask(mask: ByteArray) = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            mask.copyInto(bytes, endIndex = bytes.size)
        }
    }

    @Test
    fun `the wrap reproduces every record playfair_decrypt unwrapped`() {
        val challenge = FairPlayRecords.parseM2(resource("appletv32_fpsetup_m2_body.bin")).getOrThrow()
        val receiverSap = FairPlayMessageCipher.decryptBody(challenge.mode, challenge.challenge)

        val rows = String(resource("keywrap_playfair_verified.csv"))
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(8, rows.size, "the fixture holds 8 oracle-verified rows")

        rows.forEachIndexed { i, row ->
            val (m3Hex, ekeyHex, keyHex) = row.split(',')
            val m3 = FairPlayRecords.parseM3(hex(m3Hex)).getOrThrow()
            val ekey = hex(ekeyHex)
            val localSap = FairPlayMessageCipher.decryptBody(m3.mode, m3.body)

            val rebuilt = FairPlayKeyWrap.wrap(receiverSap, localSap, hex(keyHex), fixedMask(ekey.copyOfRange(16, 32)))
            assertContentEquals(ekey, rebuilt, "row $i: the wrap no longer produces the record the receiver unwrapped")
        }
    }
}
