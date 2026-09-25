package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conformance tests for [Phase2Rounds] -- the WB-MD5 round machinery.
 *
 * The strongest check here is
 * [plain rounds match the full white-box round on random inputs]: it compares
 * the ported plain loop against a verbatim transcription of upstream's *full
 * white-box* round (`hash.go roundC_withPermutedHidden`), which carries the
 * affine-bijection encode/decode explicitly and folds the sub-round-17 anomaly
 * from `OutBiases[aEnc]` rather than from `plainAddConsts`. The two share no
 * code and no constant folding, so agreeing on 300 random inputs pins the
 * rotate direction, all four F functions, the anomaly folding, and the
 * Group 1->2 shuffle's encoded-state inputs at once.
 *
 * [round 19 reproduces the golden response tail] anchors the port to the
 * published corpus: upstream's captured `HiddenWordsG0/G2[19]` through
 * [Phase2Rounds.roundCMd5Plain] must yield exactly the last 16 bytes of the
 * all-zero golden response.
 */
class Phase2RoundsTest {

    private fun hex(s: String): ByteArray {
        val t = s.trim()
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The fixed local SAP the published corpus was generated with. */
    private val corpusLocalSap = hex(
        "0001e4e3dd688293e6fa66b95ba41768e587c65f750218ff1be21543d573cefb" +
            "087bd36e0c6363c3c8242f4abcfa6d660b801032015405eb4ab04dda7aeff38f" +
            "fb36f4cfa48f0b5d92ae363f68b45925bbe6413ab6bdc4968f548d21e67d20f1" +
            "912b6820e53f1013cde29df7350a9b9fa7c51320aea62d2949786c87642e34ba"
    )

    // ==================================================================
    // The full white-box round, as an independent oracle
    // ==================================================================

    /**
     * hash.go `roundC_withPermutedHidden`, transcribed verbatim from the Go.
     *
     * The FULL white-box round: the affine-bijection encode/decode is carried
     * explicitly, the sub-round-17 anomaly is added inline from
     * `OutBiases[aEnc]` (NOT from `plainAddConsts`), and the final words are
     * decoded before the feed-forward.
     */
    private fun roundCWithPermutedHidden(state: IntArray, hiddenG0: IntArray, hiddenG2: IntArray) {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]

        var aEnc = -1
        var bEnc = -1
        var cEnc = -1
        var dEnc = -1

        for (i in 0 until 64) {
            val aDec = if (aEnc >= 0) a xor Phase2Tables.OutBiases[aEnc] else a
            val bDec = if (bEnc >= 0) b xor Phase2Tables.OutBiases[bEnc] else b
            val cDec = if (cEnc >= 0) c xor Phase2Tables.OutBiases[cEnc] else c
            val dDec = if (dEnc >= 0) d xor Phase2Tables.OutBiases[dEnc] else d

            val f = when (i shr 4) {
                0 -> dDec xor (bDec and (cDec xor dDec))
                1 -> cDec xor (dDec and (bDec xor cDec))
                2 -> bDec xor cDec xor dDec
                else -> cDec xor (bDec or dDec.inv())
            }

            val msg = if (i < 32) hiddenG0[Phase2Tables.MsgSchedule[i]]
            else hiddenG2[Phase2Tables.MsgSchedule[i]]

            var aFull = aDec + msg
            if (i == 17 && aEnc >= 0) aFull += Phase2Tables.OutBiases[aEnc]
            var tmp = aFull + f + Phase2Tables.AddConsts[i]
            tmp = Integer.rotateRight(tmp, Phase2Tables.RorAmounts[i])

            val postAddB = tmp + bDec
            // Affine bijection: postAddB - (ModConsts[i] & (postAddB << 1)) + OutBiases[i]
            val newB = postAddB - (Phase2Tables.ModConsts[i] and (postAddB shl 1)) +
                Phase2Tables.OutBiases[i]

            // Go: `a, b, c, d = d, newB, b, c` and
            //     `aEnc, bEnc, cEnc, dEnc = dEnc, i, bEnc, cEnc`.
            // Every right-hand side reads OLD values.
            val nA = d
            val nB = newB
            val nC = b
            val nD = c
            val nAEnc = dEnc
            val nBEnc = i
            val nCEnc = bEnc
            val nDEnc = cEnc
            a = nA; b = nB; c = nC; d = nD
            aEnc = nAEnc; bEnc = nBEnc; cEnc = nCEnc; dEnc = nDEnc
        }

        // Decode the final words before the standard MD5 feed-forward.
        if (aEnc >= 0) a = a xor Phase2Tables.OutBiases[aEnc]
        if (bEnc >= 0) b = b xor Phase2Tables.OutBiases[bEnc]
        if (cEnc >= 0) c = c xor Phase2Tables.OutBiases[cEnc]
        if (dEnc >= 0) d = d xor Phase2Tables.OutBiases[dEnc]

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    @Test
    fun `plain rounds match the full white-box round on random inputs`() {
        val rnd = java.util.Random(0xf00d)
        repeat(300) {
            val g0 = IntArray(16) { rnd.nextInt() }
            val g2 = IntArray(16) { rnd.nextInt() }
            val init = IntArray(4) { rnd.nextInt() }

            val ref = init.copyOf()
            roundCWithPermutedHidden(ref, g0, g2)

            val got = init.copyOf()
            Phase2Rounds.roundCMd5Plain(got, g0, g2)
            assertEquals(ref.toList(), got.toList(), "unrolled (explicit G2) != white-box")

            val gotRef = init.copyOf()
            Phase2Rounds.roundCMd5PlainReference(gotRef, g0, g2)
            assertEquals(ref.toList(), gotRef.toList(), "reference (explicit G2) != white-box")
        }
    }

    // ==================================================================
    // Required checks
    // ==================================================================

    @Test
    fun `computeHiddenWords is deterministic`() {
        val ns = Phase2Spn.bridgeNeonState(hex("4a70650a06daeb96be81d1af4aac1adb911ae77e"))
        for (r in 0 until 20) {
            val a = Phase2Rounds.computeHiddenWords(ns, ByteArray(20), r)
            val b = Phase2Rounds.computeHiddenWords(ns, ByteArray(20), r)
            assertEquals(16, a.size)
            assertEquals(a.toList(), b.toList(), "round $r not deterministic")
        }
    }

    @Test
    fun `roundCMd5Plain runs on a zero hidden set without an index error`() {
        val st = intArrayOf(0, 0, 0, 0)
        Phase2Rounds.roundCMd5Plain(st, IntArray(16), null)
        val st2 = intArrayOf(0, 0, 0, 0)
        Phase2Rounds.roundCMd5PlainReference(st2, IntArray(16), null)
        assertEquals(st2.toList(), st.toList(), "unrolled != reference on zero hidden set")
    }

    @Test
    fun `unrolled matches reference across random hidden sets`() {
        val rnd = java.util.Random(0x5eed)
        repeat(200) {
            val g0 = IntArray(16) { rnd.nextInt() }
            val init = IntArray(4) { rnd.nextInt() }
            val explicit = IntArray(16) { rnd.nextInt() }

            val a1 = init.copyOf(); Phase2Rounds.roundCMd5Plain(a1, g0, null)
            val a2 = init.copyOf(); Phase2Rounds.roundCMd5PlainReference(a2, g0, null)
            assertEquals(a2.toList(), a1.toList(), "shuffle path diverged")

            val b1 = init.copyOf(); Phase2Rounds.roundCMd5Plain(b1, g0, explicit)
            val b2 = init.copyOf(); Phase2Rounds.roundCMd5PlainReference(b2, g0, explicit)
            assertEquals(b2.toList(), b1.toList(), "explicit-G2 path diverged")
        }
    }

    @Test
    fun `shuffleHiddenG2 matches the reference loop and is a permutation`() {
        val rnd = java.util.Random(0xbeef)
        repeat(500) {
            val g0 = IntArray(16) { rnd.nextInt() }
            val ae = rnd.nextInt(); val be = rnd.nextInt()
            val ce = rnd.nextInt(); val de = rnd.nextInt()
            val got = Phase2Rounds.shuffleHiddenG2(g0, ae, be, ce, de)

            // prologue.go ShuffleHiddenG2Reference.
            val exp = g0.copyOf()
            val regs = intArrayOf(ae, be, ce, de)
            for (i in 0 until 8) {
                val reg = regs[i % 4]
                val nibble = if (i < 4) reg and 0xf else (reg ushr 4) and 0xf
                val j = nibble xor Phase2Tables.g2ShuffleXORConsts[i]
                val t = exp[i]; exp[i] = exp[j]; exp[j] = t
            }
            assertEquals(exp.toList(), got.toList(), "shuffle mismatch")
            assertTrue(got.sorted() == g0.sorted(), "shuffle changed the multiset")
        }
    }

    @Test
    fun `neonBlock is 32-bit lane-wise`() {
        // All-ones input, zero xor mask, all-ones and mask, zero bias:
        //   xor = 0xFFFFFFFF, shl1 = 0xFFFFFFFE, and = 0xFFFFFFFE
        //   add = 0xFFFFFFFF + 0xFFFFFFFE = 0xFFFFFFFD (mod 2^32), no cross-lane carry
        val ones = Phase2Rounds.dup32(-1)
        val w = Phase2Rounds.neonBlock(-1L, -1L, LongArray(2), ones, LongArray(2))
        for (lane in 0 until 4) {
            assertEquals(0xFFFFFFFD.toInt(), w[lane], "lane $lane: a carry crossed the 32-bit lane")
        }
        // shl32Lanes must not let bit 31 of the low lane reach the high lane.
        val expectedShl = 0xFFFFFFFEL or (0xFFFFFFFEL shl 32)
        assertEquals(expectedShl, Phase2Rounds.shl32Lanes(-1L, 1), "shl32Lanes leaked across lanes")
    }

    @Test
    fun `bswapWords16 and staging shapes`() {
        val in16 = ByteArray(16) { it.toByte() }
        assertEquals(
            listOf<Byte>(3, 2, 1, 0, 7, 6, 5, 4, 11, 10, 9, 8, 15, 14, 13, 12),
            Phase2Rounds.bswapWords16(in16).toList(),
        )
        val r8 = Phase2Rounds.r8Staging(in16)
        assertEquals(64, r8.size)
        assertEquals(Phase2Tables.r8Suffix.toList(), r8.copyOfRange(16, 64).toList())

        val r19 = Phase2Rounds.r19Staging(intArrayOf(0x01020304, 0, 0, 0), in16)
        assertEquals(64, r19.size)
        assertEquals(listOf<Byte>(4, 3, 2, 1), r19.copyOfRange(0, 4).toList())
        assertEquals(Phase2Tables.mdPadLen.toList(), r19.copyOfRange(32, 64).toList())
    }

    /**
     * Structural checks on [Phase2Rounds.computeHiddenWords]:
     *
     *  - round 0 stores `vreg0`'s four lanes as `hidden[0..3]`;
     *  - rounds 1+ compute `hidden[0..3]` from `x9[0x00..0x0F]` with the
     *    hardcoded masks;
     *  - `hidden[4..15]` are identical in every round (only `hidden[5]` moves);
     *  - the counter reaches ONLY the MSB of `hidden[5]`: `round` for 0..7,
     *    `round - 10` for 10..18, and nothing for 8, 9 and 19.
     */
    @Test
    fun `computeHiddenWords structure and counter injection`() {
        val gp = FairPlayWhiteBox.phase1(ByteArray(128))
        val x9 = FairPlaySapCore.bridgeX9HeadForSap(corpusLocalSap, gp)
        val ns = Phase2Spn.bridgeNeonState(x9)

        // Round 0 block 0 is vreg0 verbatim.
        val r0 = Phase2Rounds.computeHiddenWords(ns, x9, 0)
        assertEquals(ns.vreg0[0].toInt(), r0[0], "hidden[0] != lo(vreg0[0])")
        assertEquals((ns.vreg0[0] ushr 32).toInt(), r0[1], "hidden[1] != hi(vreg0[0])")
        assertEquals(ns.vreg0[1].toInt(), r0[2], "hidden[2] != lo(vreg0[1])")
        assertEquals((ns.vreg0[1] ushr 32).toInt(), r0[3], "hidden[3] != hi(vreg0[1])")

        // Rounds 1+ block 0 comes from x9[0..16] through the hardcoded masks.
        val cXor = Phase2Rounds.dup32(Phase2Tables.NeonXORConst)
        val cAnd = Phase2Rounds.dup32(Phase2Tables.NeonANDConst)
        val cAdd = Phase2Rounds.dup32(Phase2Tables.NeonADDConst)
        fun le64(off: Int): Long {
            var acc = 0L
            for (i in 0 until 8) acc = acc or ((x9[off + i].toLong() and 0xFFL) shl (8 * i))
            return acc
        }
        val expect1 = Phase2Rounds.neonBlock(le64(0), le64(8), cXor, cAnd, cAdd)
        val r1 = Phase2Rounds.computeHiddenWords(ns, x9, 1)
        for (i in 0 until 4) {
            assertEquals(expect1[i], r1[i], "round 1 hidden[$i] != prologue(x9[0:16])")
        }

        val all = Array(20) { Phase2Rounds.computeHiddenWords(ns, x9, it) }

        // hidden[4..15] identical everywhere except hidden[5] (checked below);
        // hidden[0..3] identical for rounds 1+.
        for (r in 1 until 20) {
            for (i in 4 until 16) {
                if (i == 5) continue
                assertEquals(all[0][i], all[r][i], "round $r hidden[$i] moved unexpectedly")
            }
            for (i in 0 until 4) {
                assertEquals(all[1][i], all[r][i], "round $r hidden[$i] != round 1's")
            }
        }

        // Only the MSB of hidden[5] carries the counter.
        val base5 = all[0][5]
        for (r in 0 until 20) {
            val counter = when {
                r <= 7 -> r
                r in 10..18 -> r - 10
                else -> 0
            }
            assertEquals(
                base5 + (counter shl 24),
                all[r][5],
                "round $r hidden[5] counter wrong (expected counter $counter)",
            )
        }
        assertEquals(base5, all[8][5], "round 8 must have no counter")
        assertEquals(base5, all[9][5], "round 9 must have no counter")
        assertEquals(base5, all[19][5], "round 19 must have no counter")
        assertEquals(base5, all[10][5], "round 10 counter must be 0")
        assertEquals(base5 + (8 shl 24), all[18][5], "round 18 counter must be 8")
    }

    /**
     * The port anchored to the published corpus.
     *
     * Upstream's `HiddenWordsG0[19]` / `HiddenWordsG2[19]` were captured from
     * the real ARM64 bytecode for the all-zero payload. Feeding them through
     * [Phase2Rounds.roundCMd5Plain] from `round8InitialState` must reproduce
     * exactly the last 16 bytes of the all-zero golden response
     * (`6f627565f3e77f5b5ede91beee7baf92e4241e0b`), big-endian.
     *
     * This is a direct, corpus-anchored check of the round machinery that needs
     * no prologue, no SPN and no `x9`.
     */
    @Test
    fun `round 19 reproduces the golden response tail from the captured tables`() {
        val st = Phase2Tables.round8InitialState.copyOf()
        Phase2Rounds.roundCMd5Plain(st, Phase2Tables.HiddenWordsG0[19], Phase2Tables.HiddenWordsG2[19])

        val be = ByteArray(16)
        for (w in 0 until 4) {
            be[w * 4] = (st[w] ushr 24).toByte()
            be[w * 4 + 1] = (st[w] ushr 16).toByte()
            be[w * 4 + 2] = (st[w] ushr 8).toByte()
            be[w * 4 + 3] = st[w].toByte()
        }
        assertEquals(
            "f3e77f5b5ede91beee7baf92e4241e0b",
            toHex(be),
            "round 19's BE words are the last 16 bytes of the all-zero golden response",
        )
        // And the same through the reference loop.
        val st2 = Phase2Tables.round8InitialState.copyOf()
        Phase2Rounds.roundCMd5PlainReference(st2, Phase2Tables.HiddenWordsG0[19], Phase2Tables.HiddenWordsG2[19])
        assertEquals(st.toList(), st2.toList(), "unrolled != reference on the captured round-19 inputs")
    }

    /** Round 8's captured output, likewise from the bytecode. */
    @Test
    fun `round 8 reproduces the captured bytecode output`() {
        val st = Phase2Tables.round8InitialState.copyOf()
        Phase2Rounds.roundCMd5Plain(st, Phase2Tables.HiddenWordsG0[8], Phase2Tables.HiddenWordsG2[8])
        assertEquals(
            listOf(0xebba92d7.toInt(), 0x13990193, 0xa2efb1fc.toInt(), 0x2d73fb1f),
            st.toList(),
            "round 8's captured bytecode output",
        )
    }
}
