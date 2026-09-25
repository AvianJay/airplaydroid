package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Hardware-attested vectors**: challenge + local SAP -> a response a real
 * receiver *accepted*.
 *
 * This is the strongest evidence available without a receiver in the room, and it
 * is stronger than the golden corpus in one specific way. `golden_vectors.csv`
 * pins the *fixed-SAP* variant (`FPExchangeBlobless` -> `bridgeX9DataClosed`),
 * whereas a real sender generates a fresh local SAP per session and calls
 * `bridgeX9DataClosedForSAP`. These 12 rows exercise that second path, which is
 * the one [FairPlayResponderImpl] actually uses.
 *
 * Each row's `verdict` column is `accepted`, so reproducing these responses means
 * reproducing bytes that shipping receivers accepted -- not merely bytes that
 * agree with another implementation.
 *
 * Fixture: `testdata/hardware_attested.csv` from the `objevovat` project.
 * Columns: `challenge_hex,local_sap_hex,response_hex,verdict`.
 */
class FairPlayHardwareAttestedTest {

    private fun hex(s: String): ByteArray {
        val t = s.trim()
        require(t.length % 2 == 0) { "odd-length hex: ${t.length}" }
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private data class Row(
        val challenge: ByteArray,
        val localSap: ByteArray,
        val response: ByteArray,
        val verdict: String,
    )

    private fun rows(): List<Row> {
        val stream = javaClass.getResourceAsStream("/fairplay/hardware_attested.csv")
            ?: error("missing fixture fairplay/hardware_attested.csv")
        return stream.bufferedReader().use { it.readLines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .drop(1) // header
            .map { line ->
                val c = line.split(",")
                require(c.size >= 4) { "unexpected row: $line" }
                Row(hex(c[0]), hex(c[1]), hex(c[2]), c[3])
            }
    }

    @Test
    fun `the fixture has the expected shape`() {
        val rows = rows()
        assertEquals(12, rows.size, "the attested corpus has 12 rows")
        rows.forEachIndexed { i, r ->
            assertEquals(128, r.challenge.size, "row $i challenge")
            assertEquals(128, r.localSap.size, "row $i local SAP")
            assertEquals(20, r.response.size, "row $i response")
            assertEquals("accepted", r.verdict, "row $i verdict")
        }
    }

    /**
     * The session-aware path, every row.
     *
     * Passing this means our responder produces responses that real receivers
     * accepted, for real per-session SAPs.
     */
    @Test
    fun `every attested challenge reproduces the response a receiver accepted`() {
        val rows = rows()
        var passed = 0
        val failures = mutableListOf<String>()

        for ((i, row) in rows.withIndex()) {
            val got = runCatching {
                val gp = FairPlayWhiteBox.phase1(row.challenge)
                val x9 = FairPlaySapCore.bridgeX9HeadForSap(row.localSap, gp)
                FairPlayPhase2.exchangeFromX9(x9)
            }.getOrNull()

            if (got != null && got.contentEquals(row.response)) {
                passed++
            } else {
                failures += "row $i: expected ${toHex(row.response)} actual ${got?.let(::toHex)}"
            }
        }

        println("hardware-attested PASS COUNT: $passed/${rows.size}")
        failures.take(3).forEach { println("  e.g. $it") }

        assertTrue(
            failures.isEmpty(),
            "hardware-attested ${passed}/${rows.size} passed; ${failures.size} failed:\n" +
                failures.take(3).joinToString("\n"),
        )
        assertEquals(rows.size, passed)
    }

    /**
     * The attested responses are all distinct, so this cannot pass degenerately.
     *
     * A payload-independent implementation (or one returning a constant) would
     * score at most 1/12 here, which is what makes the pass count meaningful.
     */
    @Test
    fun `the attested responses are distinct and challenge-dependent`() {
        val rows = rows()
        assertEquals(
            rows.size,
            rows.map { toHex(it.response) }.distinct().size,
            "a constant or payload-independent output must not be able to pass",
        )
        assertEquals(
            rows.size,
            rows.map { toHex(it.challenge) }.distinct().size,
            "every challenge should be distinct",
        )
        assertEquals(
            rows.size,
            rows.map { toHex(it.localSap) }.distinct().size,
            "every local SAP should be distinct -- these are per-session values",
        )
    }

    /**
     * The responder itself, end to end, on a real attested challenge.
     *
     * The test above drives the three stages directly; this drives the public
     * entry point a session calls, so a wiring mistake in the responder is caught
     * too.
     */
    @Test
    fun `the responder reproduces an attested response`() {
        val row = rows().first()
        val response = FairPlayResponderImpl.respondWithSap(
            FairPlayRecords.Mode.MODE_3,
            row.challenge,
            row.localSap,
        )
        assertEquals(toHex(row.response), toHex(response))
    }
}
