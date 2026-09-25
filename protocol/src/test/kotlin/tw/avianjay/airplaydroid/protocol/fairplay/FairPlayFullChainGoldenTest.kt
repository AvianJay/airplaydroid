package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The full-chain acceptance test.** 142 vectors of
 * `challenge (128 B) -> response (20 B)`.
 *
 * This is the test that would have caught the missing Phase 2, and it is the one
 * that proves Phase 2 correct now that it is ported. Every other suite here
 * checks a *component*: `sap_hash` (40), `bridge_x9head` (30). Both were passing
 * at 100% while the responder as a whole produced wrong output, because neither
 * is the response.
 *
 * The fixture is `testdata/golden_vectors.csv` from the `objevovat` project --
 * the output of upstream `FPExchangeBlobless`, a *separate* implementation. The
 * expected values are read from the CSV and never recomputed here.
 *
 * ### Why the whole chain, and not a component
 *
 * The one bug this corpus caught that no component test could was in Phase 1's
 * Type-II T-box construction: Go's `wbaesTypeII` is `[4][256]uint32` with four
 * byte lanes packed per entry, and building it as four byte tables still yields
 * a full-looking 128-byte GP buffer and a full-looking 20-byte response. Every
 * component matched its own oracle (which shared the same bug); only the
 * end-to-end vector noticed.
 */
class FairPlayFullChainGoldenTest {

    private fun hex(s: String): ByteArray {
        val t = s.trim()
        require(t.length % 2 == 0) { "odd-length hex: ${t.length}" }
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private data class Row(val category: String, val payload: ByteArray, val hash: ByteArray)

    /**
     * The **fixed** local SAP the corpus was generated with.
     *
     * This is not arbitrary and not all-zeroes. Upstream's `TestGoldenPayloadToHash`
     * validates `FPExchangeBlobless`, which is
     * `exchangeFromX9(bridgeX9DataClosed(gp))` -- the *fixed-SAP* variant, whose
     * `localSAP` is this constant (`fpsapcore/bridge.go`). `bridgeX9DataClosed`
     * differs from `bridgeX9DataClosedForSAP` precisely in using it.
     *
     * Passing all-zeroes here instead would make every row fail for a reason
     * that has nothing to do with Phase 2.
     */
    private val corpusLocalSap = hex(
        "0001e4e3dd688293e6fa66b95ba41768e587c65f750218ff1be21543d573cefb" +
            "087bd36e0c6363c3c8242f4abcfa6d660b801032015405eb4ab04dda7aeff38f" +
            "fb36f4cfa48f0b5d92ae363f68b45925bbe6413ab6bdc4968f548d21e67d20f1" +
            "912b6820e53f1013cde29df7350a9b9fa7c51320aea62d2949786c87642e34ba"
    )

    /** Reads a classpath CSV, dropping blanks and `#` comments. */
    private fun rows(): List<Row> {
        val stream = javaClass.getResourceAsStream("/fairplay/golden_vectors.csv")
            ?: error("missing fixture fairplay/golden_vectors.csv")
        val lines = stream.bufferedReader().use { it.readLines() }
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .drop(1) // header
            .map { line ->
                val c = line.split(",")
                require(c.size >= 4) { "unexpected row: $line" }
                Row(c[0], hex(c[2]), hex(c[3]))
            }
    }

    @Test
    fun `the fixture has the expected shape`() {
        val rows = rows()
        assertEquals(142, rows.size, "the upstream corpus has 142 vectors")
        assertEquals(128, corpusLocalSap.size, "the fixed SAP is 128 bytes")
        assertEquals(0x00.toByte(), corpusLocalSap[0])
        assertEquals(0x01.toByte(), corpusLocalSap[1])
        rows.forEachIndexed { i, r ->
            assertEquals(128, r.payload.size, "row $i payload")
            assertEquals(20, r.hash.size, "row $i hash")
        }
    }

    /**
     * The whole chain, every vector.
     *
     * Fails loudly today because Phase 2 is missing; passing this is the
     * definition of done for the FairPlay response core.
     */
    @Test
    fun `every challenge maps to its golden response`() {
        val rows = rows()
        var passed = 0
        val failures = mutableListOf<String>()

        for ((i, row) in rows.withIndex()) {
            val actual = runCatching {
                val gp = FairPlayWhiteBox.phase1(row.payload)
                val x9 = FairPlaySapCore.bridgeX9HeadForSap(corpusLocalSap, gp)
                FairPlayPhase2.exchangeFromX9(x9)
            }

            val got = actual.getOrNull()
            when {
                got != null && got.contentEquals(row.hash) -> passed++
                else -> failures += "row $i (${row.category}): expected ${toHex(row.hash)} actual ${got?.let(::toHex)}"
            }
        }

        println("full-chain PASS COUNT: $passed/${rows.size}")
        failures.take(3).forEach { println("  e.g. $it") }

        assertTrue(
            failures.isEmpty(),
            "full chain ${passed}/${rows.size} passed; ${failures.size} failed:\n" +
                failures.take(5).joinToString("\n"),
        )
        assertEquals(rows.size, passed)
    }
}
