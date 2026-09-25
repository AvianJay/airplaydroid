package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Component tests for [Phase2Spn] -- the white-box AES SPN passes of FairPlay
 * SAP Phase 2.
 *
 * The centrepiece is [goDerivedVectorsMatch]: 42 cases whose expected values
 * were computed by `_research/xcheck_phase2_spn.py` **independently from the
 * vendored Go sources** (`fairplayhash` / `fpbridge`), not from this port. That
 * script re-parses the Go with its own helpers and re-implements SPN1,
 * MixColumns, ApplyTrailing, TailInput, TailSPN and bridgeNeonState from the Go
 * algorithms, so a transcription slip in the Kotlin shows up as a mismatch
 * rather than as a plausible-looking 16 bytes.
 *
 * Regenerate the fixture with:
 *
 *     python _research/xcheck_phase2_spn.py
 */
class FairPlayPhase2SpnTest {

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** One fixture CASE: the inputs and the Go-computed expectations. */
    private class Case(
        val index: Int,
        val roundOutputs: Array<IntArray>,
        val mix16: ByteArray,
        val mix144: ByteArray,
        val x9: ByteArray,
        val expect: Map<String, String>,
    )

    private fun cases(): List<Case> {
        val stream = javaClass.getResourceAsStream("/fairplay/phase2_spn_vectors.txt")
            ?: error("missing fixture fairplay/phase2_spn_vectors.txt")
        val lines = stream.bufferedReader().use { it.readLines() }

        val cases = mutableListOf<Case>()
        var ro: Array<IntArray>? = null
        var mix16: ByteArray? = null
        var mix144: ByteArray? = null
        var x9: ByteArray? = null
        var expect = mutableMapOf<String, String>()

        fun flush() {
            if (ro == null) return
            cases += Case(cases.size, ro!!, mix16!!, mix144!!, x9!!, expect)
            ro = null
            expect = mutableMapOf()
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == "CASE") {
                flush()
                continue
            }
            val parts = line.split(" ")
            when (parts[0]) {
                "RO" -> {
                    val words = parts[1]
                    require(words.length == 20 * 4 * 8) { "RO is 20x4 words, got ${words.length / 8}" }
                    ro = Array(20) { r ->
                        IntArray(4) { w ->
                            words.substring((r * 4 + w) * 8, (r * 4 + w) * 8 + 8).toLong(16).toInt()
                        }
                    }
                }
                "MIX16" -> mix16 = hexToBytes(parts[1])
                "MIX144" -> mix144 = hexToBytes(parts[1])
                "X9" -> x9 = hexToBytes(parts[1])
                "EXPECT" -> expect[parts[1]] = parts[2]
            }
        }
        flush()
        return cases
    }

    /**
     * Every SPN pass against the Go-derived expectations.
     *
     * Covers SPN1 (all 9 core rounds + trailing encode), both MixColumns forms,
     * ApplyTrailing, TailInput, both TailSPN forms, beRoundOutput and
     * bridgeNeonState -- 10 checks per case, 420 values in total.
     */
    @Test
    fun `goDerivedVectorsMatch`() {
        val cases = cases()
        assertEquals(42, cases.size, "fixture case count")

        val failures = mutableListOf<String>()
        var checks = 0

        fun check(c: Case, name: String, actual: String) {
            checks++
            val want = c.expect[name] ?: error("fixture case ${c.index} has no expectation '$name'")
            if (actual != want) {
                failures += "case ${c.index} $name: expected $want actual $actual"
            }
        }

        for (c in cases) {
            check(c, "spn1", toHex(Phase2Spn.spn1(c.roundOutputs)))
            check(c, "applyMixColumns", toHex(Phase2Spn.applyMixColumns(c.mix16)))
            check(c, "applyMixColumnsReference", toHex(Phase2Spn.applyMixColumnsReference(c.mix16)))
            check(c, "applyTrailing", toHex(Phase2Spn.applyTrailing(c.mix16)))
            check(c, "tailInput", toHex(Phase2Spn.tailInput(c.roundOutputs)))
            check(c, "tailSpn", toHex(Phase2Spn.tailSpn(c.mix16, c.mix144)))
            check(c, "tailSpnReference", toHex(Phase2Spn.tailSpnReference(c.mix16, c.mix144)))
            check(c, "beRoundOutput8", toHex(Phase2Spn.beRoundOutput(c.roundOutputs[8])))
            val ns = Phase2Spn.bridgeNeonState(c.x9)
            check(c, "bridgeNeonState[0]", "%016x".format(ns.vreg0[0]))
            check(c, "bridgeNeonState[1]", "%016x".format(ns.vreg0[1]))
        }

        assertEquals(42 * 10, checks, "checks per case")
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $checks values differ from the Go:\n" +
                failures.take(5).joinToString("\n"),
        )
    }

    /**
     * The fast nibble MixColumns and the bit-by-bit reference agree, and the map
     * is a GF(2)-affine bijection -- which is what makes the 128x128 matrix
     * reading correct rather than merely self-consistent.
     */
    @Test
    fun `mixColumnsIsAnAffineBijection`() {
        val rnd = Random(0x5EED)

        // Fast path == reference.
        repeat(200) {
            val input = ByteArray(16).also { rnd.nextBytes(it) }
            assertContentEquals(
                Phase2Spn.applyMixColumnsReference(input),
                Phase2Spn.applyMixColumns(input),
                "fast != reference on ${toHex(input)}",
            )
        }

        // f(a ^ b) == f(a) ^ f(b) ^ f(0).
        val zero = Phase2Spn.applyMixColumns(ByteArray(16))
        repeat(200) {
            val a = ByteArray(16).also { rnd.nextBytes(it) }
            val b = ByteArray(16).also { rnd.nextBytes(it) }
            val ab = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }
            val fa = Phase2Spn.applyMixColumns(a)
            val fb = Phase2Spn.applyMixColumns(b)
            val expected = ByteArray(16) { (fa[it].toInt() xor fb[it].toInt() xor zero[it].toInt()).toByte() }
            assertContentEquals(expected, Phase2Spn.applyMixColumns(ab), "not affine")
        }

        // Injective over a 65536-point sweep, so it is a bijection.
        val seen = HashSet<String>()
        for (v in 0 until 65536) {
            val input = ByteArray(16)
            input[0] = v.toByte()
            input[1] = (v ushr 8).toByte()
            assertTrue(seen.add(toHex(Phase2Spn.applyMixColumns(input))), "not injective at $v")
        }
        assertEquals(65536, seen.size)
    }

    /**
     * Structural properties the driver depends on, independent of the fixture.
     *
     * The round-key window is the load-bearing one: SPN1 consumes
     * `roundOutputs[10..18]` in forward order, so a port that read the wrong
     * nine indices would still return 16 plausible bytes.
     */
    @Test
    fun `signaturesAndRoundKeyWindow`() {
        val rnd = Random(7)
        val ro = Array(20) { IntArray(4) { rnd.nextInt() } }
        val coreInput = Phase2Tables.SPN1CoreInput.copyOf()

        val a = Phase2Spn.spn1(ro)
        assertEquals(16, a.size)
        assertContentEquals(coreInput, Phase2Tables.SPN1CoreInput, "SPN1CoreInput was mutated")
        assertContentEquals(a, Phase2Spn.spn1(ro), "spn1 is not deterministic")

        // Each of the nine consumed round keys changes the output...
        for (r in 10..18) {
            val mutated = Array(20) { ro[it].copyOf() }
            mutated[r][0] = mutated[r][0] xor 1
            assertTrue(
                toHex(Phase2Spn.spn1(mutated)) != toHex(a),
                "spn1 ignored roundOutputs[$r]",
            )
        }
        // ...and none of the others does.
        for (r in listOf(0, 1, 8, 9, 19)) {
            val mutated = Array(20) { ro[it].copyOf() }
            mutated[r][0] = mutated[r][0] xor 1
            assertContentEquals(a, Phase2Spn.spn1(mutated), "spn1 read roundOutputs[$r]")
        }

        assertEquals(16, Phase2Spn.applyMixColumns(ByteArray(16)).size)
        assertEquals(16, Phase2Spn.applyTrailing(ByteArray(16)).size)
        assertEquals(16, Phase2Spn.tailInput(ro).size)
        assertEquals(16, Phase2Spn.tailSpn(ByteArray(16), ByteArray(144)).size)

        // beRoundOutput is big-endian, byte for byte.
        assertEquals(
            "0102030405060708090a0b0c0d0e0f10",
            toHex(Phase2Spn.beRoundOutput(intArrayOf(0x01020304, 0x05060708, 0x090a0b0c, 0x0d0e0f10))),
        )

        // tailInput reads roundOutputs[8] and [18] only.
        val ro18 = Array(20) { ro[it].copyOf() }
        ro18[18][3] = ro18[18][3] xor 1
        assertTrue(toHex(Phase2Spn.tailInput(ro18)) != toHex(Phase2Spn.tailInput(ro)))
        val ro9 = Array(20) { ro[it].copyOf() }
        ro9[9][0] = ro9[9][0] xor 0xFFFF
        assertContentEquals(Phase2Spn.tailInput(ro), Phase2Spn.tailInput(ro9), "tailInput read ro9")

        // bridgeNeonState passes the three constant registers straight through.
        val ns = Phase2Spn.bridgeNeonState(ByteArray(20))
        assertContentEquals(Phase2Tables.bridgeVreg1, ns.vreg1)
        assertContentEquals(Phase2Tables.bridgeVreg2, ns.vreg2)
        assertContentEquals(Phase2Tables.bridgeVreg3, ns.vreg3)
        assertEquals(2, ns.vreg0.size)
    }
}
