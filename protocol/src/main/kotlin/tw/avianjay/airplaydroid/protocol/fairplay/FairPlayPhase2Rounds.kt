/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * FairPlay SAP Phase 2 -- the WB-MD5 round machinery.
 *
 * Phase 2 turns the 20-byte bridge digest (`x9`) into the 20-byte m3 response.
 * This file carries the part of that pipeline that is neither a constant table
 * nor a WB-AES SPN pass:
 *
 *   prologue.go       -> [neonBlock], [shl32Lanes], [add32Lanes], [dup32],
 *                        [computeHiddenWords], [shuffleHiddenG2],
 *                        [shuffleHiddenG2Into]
 *   roundc_plain.go   -> [roundCMd5PlainReference]
 *   roundc_unrolled.go-> [roundCMd5Plain]
 *   spn1_r8r19.go     -> [hiddenFromStaging], [bswapWords16], [r8Staging],
 *                        [r8HiddenWords], [r19Staging]
 *
 * Ported faithfully from the upstream Go (packages `fairplayhash` and
 * `fpbridge`, vendored under `_research/src/objevovat_phase2/`). Every constant
 * lives in [Phase2Tables]; nothing is re-declared here. Statement order is
 * load-bearing in every round function and has been preserved exactly.
 *
 * Kotlin porting notes that matter:
 *  - 32-bit words are `Int` (wraps at 32 bits exactly like Go's `uint32`).
 *  - Go's `bits.RotateLeft32(x, -n)` is `Integer.rotateRight(x, n)`.
 *  - `ushr` (logical) everywhere a Go `uint32` shift was logical; `shr` would
 *    sign-extend and is wrong.
 *  - `a &^ b` (AND-NOT) is `a and b.inv()`.
 *  - Go's `^a` (unary complement) is `a.inv()`.
 */
package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * The WB-MD5 round machinery of FairPlay SAP Phase 2.
 *
 * Two hidden-word sources feed a 64-sub-round MD5 core: the NEON prologue
 * ([computeHiddenWords], driven by `x9Data`), and the R8/R19 staging messages
 * ([r8Staging] / [r19Staging], driven by the WB-AES SPN passes in `Phase2Spn`).
 *
 * The white-box "affine bijection" encoding of the real bytecode is
 * algebraically the identity -- upstream's `roundc_plain.go` proves it and
 * [roundCMd5PlainReference] is that proof made executable. Only two places in
 * the whole 64 sub-rounds can still observe the encoding:
 *
 *  - sub-round 17 adds `OutBiases[13]` to the accumulator. That is already
 *    folded into [Phase2Tables.plainAddConsts]`[17]`, so it must **not** be
 *    added again here; and
 *  - the Group 1->2 shuffle at sub-round 32 reads the *encoded* state words, so
 *    they are re-encoded at that one point.
 */
internal object Phase2Rounds {

    // =====================================================================
    // encoding/binary helpers
    // =====================================================================

    /** `binary.LittleEndian.Uint64(b[off:])`. */
    private fun leU64(b: ByteArray, off: Int): Long {
        var acc = 0L
        for (i in 0 until 8) {
            acc = acc or ((b[off + i].toLong() and 0xFFL) shl (8 * i))
        }
        return acc
    }

    /** `binary.LittleEndian.PutUint32(b[off:], v)`. */
    private fun putLeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    /**
     * The 64-byte `x9Data` window the prologue reads.
     *
     * Upstream's `x9Data` is the 20-byte bridge digest followed by the 44-byte
     * payload-independent [Phase2Tables.bridgeX9Tail]. The prologue only ever
     * reads `[0:64]`, and blocks 1-3 sit entirely inside the constant tail plus
     * the digest's last four bytes.
     *
     * A caller that already has the full 64 bytes may pass them through; a
     * caller holding only the 20-byte head (the shape
     * [FairPlayPhase2.exchangeFromX9] takes) gets the constant tail appended,
     * which is exactly the buffer upstream would have handed over.
     */
    private fun x9Window(x9Data: ByteArray): ByteArray = when {
        x9Data.size >= X9_HEAD_BYTES + Phase2Tables.bridgeX9Tail.size -> x9Data
        x9Data.size == X9_HEAD_BYTES -> x9Data + Phase2Tables.bridgeX9Tail
        else -> throw IllegalArgumentException(
            "x9Data is ${x9Data.size} bytes; expected $X9_HEAD_BYTES (head) or " +
                "${X9_HEAD_BYTES + Phase2Tables.bridgeX9Tail.size} (head + constant tail)"
        )
    }

    /** The bridge digest length; mirrors `fairplayhash.HashOutputSize`. */
    private const val X9_HEAD_BYTES = 20

    // =====================================================================
    // prologue.go -- NEON prologue arithmetic
    // =====================================================================

    /** prologue.go `dup32`: a 128-bit register with a 32-bit value in all four lanes. */
    fun dup32(v: Int): LongArray {
        val u = v.toLong() and 0xFFFFFFFFL
        val lane = u or (u shl 32)
        return longArrayOf(lane, lane)
    }

    /** prologue.go `shl32Lanes`: 32-bit lane-wise shift left on a 64-bit value. */
    fun shl32Lanes(v: Long, shift: Int): Long {
        val lo = ((v and 0xFFFFFFFFL) shl shift) and 0xFFFFFFFFL
        val hi = (((v ushr 32) shl shift) and 0xFFFFFFFFL) shl 32
        return lo or hi
    }

    /** prologue.go `add32Lanes`: 32-bit lane-wise addition on two 32-bit lanes. */
    fun add32Lanes(a: Long, b: Long): Long {
        val lo = ((a and 0xFFFFFFFFL) + (b and 0xFFFFFFFFL)) and 0xFFFFFFFFL
        val hi = (((a ushr 32) + (b ushr 32)) and 0xFFFFFFFFL) shl 32
        return lo or hi
    }

    /**
     * prologue.go `neonBlock` (exported upstream as `NeonBlockExport`): the ARM64
     * NEON prologue transformation on one 128-bit block.
     *
     * ```
     * temp   = data ^ xorMask          // XOR with constant mask
     * data   = (data << 1) & andMask   // SHL by 1, AND with constant
     * data   = temp + data             // 32-bit lane-wise ADD
     * data   = data + addBias          // 32-bit lane-wise ADD with bias
     * ```
     *
     * Every step is **32-bit lane-wise on a 4-lane vector**, so the shifts and
     * adds must be applied per 32-bit lane -- doing them on the whole 64-bit
     * value would let a carry or a shift cross the lane boundary.
     *
     * Returns the four 32-bit words `w0..w3` in lane order.
     */
    fun neonBlock(
        v0lo: Long,
        v0hi: Long,
        xorMask: LongArray,
        andMask: LongArray,
        addBias: LongArray,
    ): IntArray {
        // Step 1: XOR with constant.
        val xorLo = v0lo xor xorMask[0]
        val xorHi = v0hi xor xorMask[1]

        // Step 2: SHL by 1 (lane-wise 32-bit), AND with constant.
        val shlLo = shl32Lanes(v0lo, 1)
        val shlHi = shl32Lanes(v0hi, 1)
        val andLo = shlLo and andMask[0]
        val andHi = shlHi and andMask[1]

        // Step 3: ADD lane-wise 32-bit (xor result + and result).
        val addLo = add32Lanes(xorLo, andLo)
        val addHi = add32Lanes(xorHi, andHi)

        // Step 4: ADD bias.
        val resultLo = add32Lanes(addLo, addBias[0])
        val resultHi = add32Lanes(addHi, addBias[1])

        return intArrayOf(
            resultLo.toInt(),
            (resultLo ushr 32).toInt(),
            resultHi.toInt(),
            (resultHi ushr 32).toInt(),
        )
    }

    /**
     * prologue.go `ComputeHiddenWords`: the 16 hidden words for one WB-MD5 round.
     *
     * For round 0, block 0 stores `vreg0` (Phase 1's payload-dependent output)
     * directly and the three vector registers come from Phase 1. For every other
     * round all four blocks load from `x9Data` and the masks are the hardcoded
     * constants [Phase2Tables.NeonXORConst], [Phase2Tables.NeonANDConst] and
     * [Phase2Tables.NeonADDConst].
     *
     * A per-round counter is then injected into the MSB of `hidden[5]`, which is
     * the only thing that distinguishes the normal rounds:
     *
     * ```
     * rounds 0..7   -> counter = round        (0..7)
     * rounds 10..18 -> counter = round - 10   (0..8)
     * rounds 8, 9, 19 -> NO counter           (special hidden words, see r8/r19)
     * ```
     *
     * [x9Data] may be the 20-byte bridge digest or the full 64-byte window; see
     * [x9Window].
     */
    fun computeHiddenWords(ns: NeonState, x9Data: ByteArray, round: Int): IntArray {
        val hidden = IntArray(16)
        val src = x9Window(x9Data)

        // Select the vector constants. Round 0 uses the Phase 1 vreg state;
        // rounds 1+ reload them from immediates.
        val xorMask: LongArray
        val andMask: LongArray
        val addBias: LongArray
        if (round == 0) {
            xorMask = ns.vreg1
            andMask = ns.vreg3
            addBias = ns.vreg2
        } else {
            xorMask = dup32(Phase2Tables.NeonXORConst)
            andMask = dup32(Phase2Tables.NeonANDConst)
            addBias = dup32(Phase2Tables.NeonADDConst)
        }

        if (round == 0) {
            // Block 0: store vreg0 directly as hidden[0..3].
            hidden[0] = ns.vreg0[0].toInt()
            hidden[1] = (ns.vreg0[0] ushr 32).toInt()
            hidden[2] = ns.vreg0[1].toInt()
            hidden[3] = (ns.vreg0[1] ushr 32).toInt()
        } else {
            // Block 0: load x9Data[0x00..0x0F], transform, store as hidden[0..3].
            val w = neonBlock(leU64(src, 0x00), leU64(src, 0x08), xorMask, andMask, addBias)
            hidden[0] = w[0]
            hidden[1] = w[1]
            hidden[2] = w[2]
            hidden[3] = w[3]
        }

        // Blocks 1-3: x9Data[0x10..0x3F] -> hidden[4..15].
        for (b in 1 until 4) {
            val off = b * 16
            val w = neonBlock(leU64(src, off), leU64(src, off + 8), xorMask, andMask, addBias)
            hidden[b * 4] = w[0]
            hidden[b * 4 + 1] = w[1]
            hidden[b * 4 + 2] = w[2]
            hidden[b * 4 + 3] = w[3]
        }

        // Inject the per-round counter into hidden[5]'s most significant byte.
        val counter = when {
            round <= 7 -> round
            round in 10..18 -> round - 10
            else -> 0
        }
        hidden[5] += counter shl 24

        return hidden
    }

    /**
     * prologue.go `g2ShuffleXORConsts`, read out of [Phase2Tables] so the two
     * cannot drift. Each is under 16, which is what lets
     * [shuffleHiddenG2Into] mask the whole word instead of a nibble.
     */
    private val g2ShuffleXORConsts: IntArray get() = Phase2Tables.g2ShuffleXORConsts

    /**
     * prologue.go `shuffleHiddenG2Into`: the eight swaps of the state-dependent
     * Fisher-Yates shuffle at the Group 1->2 boundary, run in place on a buffer
     * the caller has already filled with the Group 0-1 words.
     *
     * The swaps are sequential and order dependent. Every constant in
     * [g2ShuffleXORConsts] is under 16, so masking the XOR of the whole word
     * down to four bits gives the same index as XORing the nibble -- and unlike
     * the latter it is provably in range.
     *
     * [aEnc], [bEnc], [cEnc], [dEnc] are the **encoded** state words: the raw
     * `newB` values from sub-rounds 28-31, before any XOR decode.
     */
    fun shuffleHiddenG2Into(h: IntArray, aEnc: Int, bEnc: Int, cEnc: Int, dEnc: Int) {
        val c = g2ShuffleXORConsts

        var j = (aEnc xor c[0]) and 15
        var t = h[0]; h[0] = h[j]; h[j] = t
        j = (bEnc xor c[1]) and 15
        t = h[1]; h[1] = h[j]; h[j] = t
        j = (cEnc xor c[2]) and 15
        t = h[2]; h[2] = h[j]; h[j] = t
        j = (dEnc xor c[3]) and 15
        t = h[3]; h[3] = h[j]; h[j] = t
        j = ((aEnc ushr 4) xor c[4]) and 15
        t = h[4]; h[4] = h[j]; h[j] = t
        j = ((bEnc ushr 4) xor c[5]) and 15
        t = h[5]; h[5] = h[j]; h[j] = t
        j = ((cEnc ushr 4) xor c[6]) and 15
        t = h[6]; h[6] = h[j]; h[j] = t
        j = ((dEnc ushr 4) xor c[7]) and 15
        t = h[7]; h[7] = h[j]; h[j] = t
    }

    /**
     * prologue.go `ShuffleHiddenG2`: [shuffleHiddenG2Into] on a copy of [g0].
     *
     * This is the correct, state-dependent Group 2-3 hidden-word derivation.
     * Upstream's fixed `g2PermTable` is deprecated and wrong for general inputs;
     * [Phase2Tables.g2PermTable] is kept only for reference.
     */
    fun shuffleHiddenG2(g0: IntArray, aEnc: Int, bEnc: Int, cEnc: Int, dEnc: Int): IntArray {
        require(g0.size >= 16) { "hidden word set is 16 words, got ${g0.size}" }
        val h = g0.copyOf(16)
        shuffleHiddenG2Into(h, aEnc, bEnc, cEnc, dEnc)
        return h
    }

    // =====================================================================
    // roundc_plain.go -- the reference sub-round loop
    // =====================================================================

    /**
     * roundc_plain.go `RoundC_MD5PlainReference`: the 64 sub-rounds as first
     * written, with the white-box encoding layer removed.
     *
     * Plain MD5 over the hidden words, plus the two places the encoding is still
     * observable (see the object KDoc). [hiddenG2] `null` triggers the
     * state-dependent [shuffleHiddenG2] at sub-round 32; passing the R8/R19
     * hidden words explicitly skips it, which is what those two rounds need.
     *
     * [state] is four words and is updated in place with the usual MD5 feed
     * forward. [hiddenG0] is never mutated.
     */
    fun roundCMd5PlainReference(state: IntArray, hiddenG0: IntArray, hiddenG2: IntArray?) {
        require(state.size == 4) { "MD5 state is 4 words, got ${state.size}" }
        require(hiddenG0.size >= 16) { "hiddenG0 is 16 words, got ${hiddenG0.size}" }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]

        // Held in a local so the shuffle result does not escape to the heap.
        var g2: IntArray? = hiddenG2

        for (i in 0 until 64) {
            if (i == Phase2Tables.shuffleSubRound && g2 == null) {
                // The shuffle reads the ENCODED state words. At i == 32 the
                // encoding rounds are a:28 b:31 c:30 d:29.
                g2 = shuffleHiddenG2(
                    hiddenG0,
                    a xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 4],
                    b xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 1],
                    c xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 2],
                    d xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 3],
                )
            }

            val f = when (i ushr 4) {
                0 -> d xor (b and (c xor d))
                1 -> c xor (d and (b xor c))
                2 -> b xor c xor d
                else -> c xor (b or d.inv())
            }

            val msg = if (i < 32) hiddenG0[Phase2Tables.MsgSchedule[i]]
            else g2!![Phase2Tables.MsgSchedule[i]]

            // plainAddConsts already carries the sub-round-17 OutBiases[13]
            // anomaly; do not add it a second time.
            var tmp = a + msg + f + Phase2Tables.plainAddConsts[i]
            tmp = Integer.rotateRight(tmp, Phase2Tables.RorAmounts[i])

            // Go: a, b, c, d = d, tmp+b, b, c -- all four reads are of the old
            // values, so every new value is computed before any is stored.
            val newA = d
            val newB = tmp + b
            val newC = b
            val newD = c
            a = newA
            b = newB
            c = newC
            d = newD
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    // =====================================================================
    // roundc_unrolled.go -- the same loop with the state rotation removed
    // =====================================================================

    /**
     * roundc_unrolled.go `RoundC_MD5Plain`: bit-exact with
     * [roundCMd5PlainReference], with the per-sub-round state renumbering
     * replaced by the register naming.
     *
     * Each sub-round ends with `a, b, c, d = d, tmp+b, b, c` -- four moves that
     * exist only to renumber the registers. Unrolling by four lets that happen
     * in the naming instead, so the F-function selector and the message-source
     * branch both leave the inner loop: sub-rounds 0-15, 16-31, 32-47 and 48-63
     * each use one F, and the message comes from [hiddenG0] below 32 and from
     * the Group 2-3 set above.
     *
     * ```
     * b1 = b  + ror(a + m0 + F(b,  c,  d ) + k0)
     * b2 = b1 + ror(d + m1 + F(b1, b,  c ) + k1)
     * b3 = b2 + ror(c + m2 + F(b2, b1, b ) + k2)
     * b4 = b3 + ror(b + m3 + F(b3, b2, b1) + k3)
     * a, b, c, d = b1, b4, b3, b2
     * ```
     *
     * The Group 1->2 shuffle sits exactly on the loop boundary, so it is a step
     * between loops rather than a test inside one.
     */
    fun roundCMd5Plain(state: IntArray, hiddenG0: IntArray, hiddenG2: IntArray?) {
        require(state.size == 4) { "MD5 state is 4 words, got ${state.size}" }
        require(hiddenG0.size >= 16) { "hiddenG0 is 16 words, got ${hiddenG0.size}" }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]

        val k = Phase2Tables.plainAddConsts
        val r = Phase2Tables.RorAmounts
        val s = Phase2Tables.MsgSchedule

        // Group 0: F = d ^ (b & (c^d)), message from hiddenG0.
        var i = 0
        while (i < 16) {
            val b1 = b + Integer.rotateRight(
                a + hiddenG0[s[i]] + (d xor (b and (c xor d))) + k[i], r[i],
            )
            val b2 = b1 + Integer.rotateRight(
                d + hiddenG0[s[i + 1]] + (c xor (b1 and (b xor c))) + k[i + 1], r[i + 1],
            )
            val b3 = b2 + Integer.rotateRight(
                c + hiddenG0[s[i + 2]] + (b xor (b2 and (b1 xor b))) + k[i + 2], r[i + 2],
            )
            val b4 = b3 + Integer.rotateRight(
                b + hiddenG0[s[i + 3]] + (b1 xor (b3 and (b2 xor b1))) + k[i + 3], r[i + 3],
            )
            a = b1; b = b4; c = b3; d = b2
            i += 4
        }

        // Group 1: F = c ^ (d & (b^c)), message from hiddenG0.
        i = 16
        while (i < 32) {
            val b1 = b + Integer.rotateRight(
                a + hiddenG0[s[i]] + (c xor (d and (b xor c))) + k[i], r[i],
            )
            val b2 = b1 + Integer.rotateRight(
                d + hiddenG0[s[i + 1]] + (b xor (c and (b1 xor b))) + k[i + 1], r[i + 1],
            )
            val b3 = b2 + Integer.rotateRight(
                c + hiddenG0[s[i + 2]] + (b1 xor (b and (b2 xor b1))) + k[i + 2], r[i + 2],
            )
            val b4 = b3 + Integer.rotateRight(
                b + hiddenG0[s[i + 3]] + (b2 xor (b1 and (b3 xor b2))) + k[i + 3], r[i + 3],
            )
            a = b1; b = b4; c = b3; d = b2
            i += 4
        }

        // The Group 1->2 boundary. Reads the ENCODED state words: at sub-round
        // 32 the encoding rounds are a:28 b:31 c:30 d:29.
        var g2: IntArray? = hiddenG2
        if (g2 == null) {
            val buf = hiddenG0.copyOf(16)
            shuffleHiddenG2Into(
                buf,
                a xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 4],
                b xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 1],
                c xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 2],
                d xor Phase2Tables.OutBiases[Phase2Tables.shuffleSubRound - 3],
            )
            g2 = buf
        }
        val h2 = g2

        // Group 2: F = b ^ c ^ d, message from hiddenG2.
        i = 32
        while (i < 48) {
            val b1 = b + Integer.rotateRight(a + h2[s[i]] + (b xor c xor d) + k[i], r[i])
            val b2 = b1 + Integer.rotateRight(d + h2[s[i + 1]] + (b1 xor b xor c) + k[i + 1], r[i + 1])
            val b3 = b2 + Integer.rotateRight(c + h2[s[i + 2]] + (b2 xor b1 xor b) + k[i + 2], r[i + 2])
            val b4 = b3 + Integer.rotateRight(b + h2[s[i + 3]] + (b3 xor b2 xor b1) + k[i + 3], r[i + 3])
            a = b1; b = b4; c = b3; d = b2
            i += 4
        }

        // Group 3: F = c ^ (b | ^d), message from hiddenG2.
        i = 48
        while (i < 64) {
            val b1 = b + Integer.rotateRight(a + h2[s[i]] + (c xor (b or d.inv())) + k[i], r[i])
            val b2 = b1 + Integer.rotateRight(d + h2[s[i + 1]] + (b xor (b1 or c.inv())) + k[i + 1], r[i + 1])
            val b3 = b2 + Integer.rotateRight(c + h2[s[i + 2]] + (b1 xor (b2 or b.inv())) + k[i + 2], r[i + 2])
            val b4 = b3 + Integer.rotateRight(b + h2[s[i + 3]] + (b2 xor (b3 or b1.inv())) + k[i + 3], r[i + 3])
            a = b1; b = b4; c = b3; d = b2
            i += 4
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    // =====================================================================
    // spn1_r8r19.go -- the R8/R19 staging messages
    // =====================================================================

    /**
     * spn1_r8r19.go `bswapWords16`: byte-reverse each of the four 32-bit words
     * of a 16-byte block.
     */
    fun bswapWords16(input: ByteArray): ByteArray {
        require(input.size >= 16) { "bswapWords16 input is 16 bytes, got ${input.size}" }
        val o = ByteArray(16)
        for (w in 0 until 4) {
            o[w * 4] = input[w * 4 + 3]
            o[w * 4 + 1] = input[w * 4 + 2]
            o[w * 4 + 2] = input[w * 4 + 1]
            o[w * 4 + 3] = input[w * 4]
        }
        return o
    }

    /**
     * spn1_r8r19.go `hiddenFromStaging`: the four [neonBlock] calls over a
     * 64-byte staging message, with the hardcoded round-1+ masks and **no**
     * per-round counter. Produces the 16 Group 0-1 hidden words.
     */
    fun hiddenFromStaging(src: ByteArray): IntArray {
        require(src.size >= 64) { "staging message is 64 bytes, got ${src.size}" }
        val xorMask = dup32(Phase2Tables.NeonXORConst)
        val andMask = dup32(Phase2Tables.NeonANDConst)
        val addBias = dup32(Phase2Tables.NeonADDConst)

        val h = IntArray(16)
        for (b in 0 until 4) {
            val off = b * 16
            val w = neonBlock(leU64(src, off), leU64(src, off + 8), xorMask, andMask, addBias)
            h[b * 4] = w[0]
            h[b * 4 + 1] = w[1]
            h[b * 4 + 2] = w[2]
            h[b * 4 + 3] = w[3]
        }
        return h
    }

    /**
     * spn1_r8r19.go `R8Staging`: round 8's 64-byte MD5 message, built from
     * SPN#1's 16-byte output.
     *
     * ```
     * [ bswap(SPN1 output) (16) || r8Suffix (48) ]
     * ```
     *
     * `r8Suffix` is R8's payload-independent second message block followed by
     * the shared MD5 pad+length, so the whole staging buffer is one message
     * block plus its padding.
     */
    fun r8Staging(spn1Out: ByteArray): ByteArray {
        require(spn1Out.size >= 16) { "SPN1 output is 16 bytes, got ${spn1Out.size}" }
        val src = ByteArray(64)
        bswapWords16(spn1Out).copyInto(src, 0)
        Phase2Tables.r8Suffix.copyInto(src, 16)
        return src
    }

    /**
     * spn1_r8r19.go `R8HiddenWords`: round 8's Group 0-1 hidden words, derived
     * from SPN#1's output. This is the mechanism that makes R8 payload-dependent.
     */
    fun r8HiddenWords(spn1Out: ByteArray): IntArray = hiddenFromStaging(r8Staging(spn1Out))

    /**
     * spn1_r8r19.go `R19Staging`: round 19's 64-byte MD5 message, built from
     * `roundOutputs[8]` and the TailSPN output.
     *
     * ```
     * [ LittleEndian(ro8) (16) || bswap(tailSPNOut) (16) || mdPadLen (32) ]
     * ```
     */
    fun r19Staging(ro8: IntArray, tailSpnOut: ByteArray): ByteArray {
        require(ro8.size >= 4) { "roundOutputs[8] is 4 words, got ${ro8.size}" }
        require(tailSpnOut.size >= 16) { "TailSPN output is 16 bytes, got ${tailSpnOut.size}" }
        val src = ByteArray(64)
        for (w in 0 until 4) {
            putLeU32(src, w * 4, ro8[w])
        }
        bswapWords16(tailSpnOut).copyInto(src, 16)
        Phase2Tables.mdPadLen.copyInto(src, 32)
        return src
    }

    /**
     * spn1_r8r19.go's R19 hidden words: `hiddenFromStaging(R19Staging(ro8, tail))`.
     *
     * Upstream inlines this at the one call site in `analytical.go`; it is a
     * named function here because the driver needs it as a unit.
     */
    fun r19HiddenWords(ro8: IntArray, tailSpnOut: ByteArray): IntArray =
        hiddenFromStaging(r19Staging(ro8, tailSpnOut))
}
