/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * FairPlay SAP Phase 2 -- the white-box AES SPN passes.
 *
 * Phase 2 turns the 20-byte bridge digest (`x9`) into the 20-byte m3 response.
 * Its structure is an analytical white-box MD5 whose rounds are driven by two
 * WB-AES SPN passes over the NEON-derived state:
 *
 *   ns     = bridgeNeonState(x9)                  // neon_state.go
 *   state  = HashState(mem = 16384)
 *   M3Setup(state)
 *   HashAnalytical(state, ns, x9)
 *   result = state.mem[Span7Offset .. +20]
 *
 * This file ports only the SPN sub-passes -- SPN#1 (9 core rounds + trailing
 * output encode), the tail 9-round SPN, its final-round input, and the NEON
 * prologue block. The MD5 rounds themselves live in a sibling file.
 *
 * Ported faithfully from the upstream Go (packages `fairplayhash` and
 * `fpbridge`, vendored under `_research/src/objevovat_phase2/`):
 *
 *   spn1.go             -> [spn1], [beRoundOutput], [applyMixColumnsReference]
 *   spn1_mixcol_fast.go -> [applyMixColumns]
 *   spn1_trailing.go    -> [applyTrailing]
 *   tail_spn.go         -> [tailSpnReference]
 *   tail_spn_fast.go    -> [tailSpn]
 *   spn1_ground.go      -> [tailInput]
 *   neon_state.go       -> [bridgeNeonState]
 *
 * Every constant lives in [Phase2Tables]; nothing is re-declared here.
 */
package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * The two analytical WB-AES SPN passes of FairPlay SAP Phase 2, plus the NEON
 * prologue block that seeds them.
 *
 * All of these are pure functions of their inputs: no scratch memory is read or
 * written, and no table is mutated after init. That matters, because the whole
 * point of the analytical form is that the 16 KB interpreter window is never
 * touched -- upstream records the input surface as `x9Data[0:16]` alone.
 */
internal object Phase2Spn {

    // =====================================================================
    // Little/big-endian helpers (encoding/binary in Go)
    // =====================================================================

    /** `binary.LittleEndian.Uint64(b[off:])`. */
    private fun leU64(b: ByteArray, off: Int): Long {
        var acc = 0L
        for (i in 0 until 8) {
            acc = acc or ((b[off + i].toLong() and 0xFFL) shl (8 * i))
        }
        return acc
    }

    /** `binary.BigEndian.Uint32(b[off:])`. */
    private fun beU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** `binary.BigEndian.PutUint32(b[off:], v)`. */
    private fun putBeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    /** spn1.go `beRoundOutput`: the four round words as 16 big-endian bytes. */
    fun beRoundOutput(s: IntArray): ByteArray {
        val o = ByteArray(16)
        for (w in 0 until 4) {
            putBeU32(o, w * 4, s[w])
        }
        return o
    }

    // =====================================================================
    // spn1.go -- SPN#1: 9 core rounds + trailing output encode
    // =====================================================================

    /**
     * spn1.go `SPN1`.
     *
     * SPN#1's plaintext is the payload-independent constant [Phase2Tables.SPN1CoreInput];
     * the only payload-dependent inputs are the nine round keys, which are
     * `BigEndian(roundOutputs[10+r])` for `r = 0..8` -- the same mixing table that
     * feeds [tailSpn], consumed here in **forward** round order.
     *
     * ```
     * state = SPN1CoreInput
     * for r in 0..8:
     *   stage1[out] = SPN1CoreTables[r][out][ state[ShiftRows[out]] ]  // SubBytes+ShiftRows
     *   state       = ApplyMixColumns(stage1) ^ BE(roundOutputs[10+r]) // MixColumns+AddRoundKey
     * out = ApplyTrailing(state)
     * ```
     *
     * [roundOutputs] is `[20][4]`; the 16-byte result is SPN#1's output, which is
     * byte-reversed and staged into round 8's WB-MD5 hidden words.
     */
    fun spn1(roundOutputs: Array<IntArray>): ByteArray {
        require(roundOutputs.size >= 20) { "roundOutputs is [20][4], got ${roundOutputs.size} rows" }

        // Go's `state := SPN1CoreInput` copies the array; the constant must not be
        // mutated in place, so copy it here too.
        val state = Phase2Tables.SPN1CoreInput.copyOf()

        for (r in 0 until 9) {
            val core = Phase2Tables.SPN1CoreTables[r]
            val stage1 = ByteArray(16)
            for (out in 0 until 16) {
                stage1[out] = core[out][state[Phase2Tables.spn1ShiftRows[out]].toInt() and 0xFF]
            }

            // The round output is XORed in big-endian byte order, so it goes in a
            // word at a time rather than through a sixteen-byte intermediate.
            val mixed = applyMixColumns(stage1)
            val ro = roundOutputs[10 + r]
            for (w in 0 until 4) {
                putBeU32(state, w * 4, beU32(mixed, w * 4) xor ro[w])
            }
        }

        return applyTrailing(state)
    }

    /**
     * spn1.go `applyMixColumnsReference`: the direct bit-by-bit reading of the
     * 128x128 GF(2)-affine MixColumns matrix, kept as the oracle for
     * [applyMixColumns].
     *
     * `out[ob] = parity(row_ob AND in) XOR const_bit_ob`, with bit `b` of a byte
     * addressed as `byte[b ushr 3] >> (b and 7)`.
     */
    internal fun applyMixColumnsReference(input: ByteArray): ByteArray {
        require(input.size == 16) { "MixColumns input is 16 bytes, got ${input.size}" }
        val out = ByteArray(16)
        for (ob in 0 until 128) {
            var acc = 0
            val row = Phase2Tables.SPN1MixColRows[ob]
            for (ib in 0 until 128) {
                if ((((row[ib ushr 3].toInt() and 0xFF) ushr (ib and 7)) and 1) != 0) {
                    acc = acc xor (((input[ib ushr 3].toInt() and 0xFF) ushr (ib and 7)) and 1)
                }
            }
            acc = acc xor (((Phase2Tables.SPN1MixColConst[ob ushr 3].toInt() and 0xFF) ushr (ob and 7)) and 1)
            if (acc != 0) {
                out[ob ushr 3] = (out[ob ushr 3].toInt() or (1 shl (ob and 7))).toByte()
            }
        }
        return out
    }

    /**
     * spn1_mixcol_fast.go `ApplyMixColumns`: SPN#1's full GF(2)-affine
     * MixColumns, all four columns.
     *
     * Reading the 128x128 matrix by column makes the map a sum: each input bit
     * contributes a fixed 128-bit column, so a nibble of input contributes the
     * XOR of up to four columns. [Phase2Tables.spn1MixNib] precomputes those
     * 32x16 combinations, turning the whole map into 32 table lookups.
     *
     * Equivalent to [applyMixColumnsReference] -- that is checked in tests.
     */
    fun applyMixColumns(input: ByteArray): ByteArray {
        require(input.size == 16) { "MixColumns input is 16 bytes, got ${input.size}" }

        var o0 = Phase2Tables.spn1MixConst[0]
        var o1 = Phase2Tables.spn1MixConst[1]
        for (i in 0 until 16) {
            val b = input[i].toInt() and 0xFF
            val lo = Phase2Tables.spn1MixNib[i * 2][b and 0xF]
            val hi = Phase2Tables.spn1MixNib[i * 2 + 1][b ushr 4]
            o0 = o0 xor lo[0] xor hi[0]
            o1 = o1 xor lo[1] xor hi[1]
        }

        // binary.LittleEndian.PutUint64(out[0:8], o0) / out[8:16], o1.
        val out = ByteArray(16)
        for (j in 0 until 8) {
            out[j] = (o0 ushr (8 * j)).toByte()
            out[8 + j] = (o1 ushr (8 * j)).toByte()
        }
        return out
    }

    // =====================================================================
    // spn1_trailing.go -- SPN#1's final output encoding
    // =====================================================================

    /**
     * spn1_trailing.go `ApplyTrailing`: SPN#1's trailing output-encoding stage,
     * an AES FINAL round (SubBytes + ShiftRows + AddRoundKey, no MixColumns)
     * folded into 16 byte->byte tables.
     *
     * ```
     * out[p] = SPN1TrailTables[p][ in[spn1TrailShiftRows[p]] ]
     * ```
     */
    fun applyTrailing(state: ByteArray): ByteArray {
        require(state.size == 16) { "ApplyTrailing input is 16 bytes, got ${state.size}" }
        val out = ByteArray(16)
        for (p in 0 until 16) {
            out[p] = Phase2Tables.SPN1TrailTables[p][state[Phase2Tables.spn1TrailShiftRows[p]].toInt() and 0xFF]
        }
        return out
    }

    // =====================================================================
    // spn1_ground.go -- the tail SPN's round-0 input
    // =====================================================================

    /**
     * spn1_ground.go `TailInput`: the input to [tailSpn], computed analytically.
     *
     * A fixed final AES round applied to `BE(roundOutputs[8])`: per output
     * position a single S-box lookup (fan-in 1, ShiftRows-style permutation)
     * XORed with the 9th mixTable block, which is `BE(roundOutputs[18])`.
     *
     * ```
     * tail_input[p] = gSbox[p][ BE(ro8)[gPerm[p]] ] ^ BE(ro18)[p]
     * ```
     */
    fun tailInput(roundOutputs: Array<IntArray>): ByteArray {
        require(roundOutputs.size >= 20) { "roundOutputs is [20][4], got ${roundOutputs.size} rows" }
        val be8 = beRoundOutput(roundOutputs[8])
        val be18 = beRoundOutput(roundOutputs[18])
        val out = ByteArray(16)
        for (p in 0 until 16) {
            out[p] = (Phase2Tables.gSbox[p][be8[Phase2Tables.gPerm[p]].toInt() and 0xFF].toInt() xor
                (be18[p].toInt() and 0xFF)).toByte()
        }
        return out
    }

    // =====================================================================
    // tail_spn.go / tail_spn_fast.go -- the 9-round tail WB-AES SPN
    // =====================================================================

    /**
     * tail_spn.go `TailSPNReference`: the tail 9-round WB-AES SPN as first
     * written, kept as the oracle for [tailSpn].
     *
     * ```
     * for round in 0..8:
     *   if round > 0:                                            // inter-round encoding
     *     state[pos] = TypeI[tailSPNTypeI[round][15-pos]][state[InvShiftRows[pos]]]
     *                  ^ mixTable[(8-round)*16 + pos]
     *   cols[c] = TypeII[0][state[c*4+0]] ^ TypeII[1][state[c*4+1]]
     *           ^ TypeII[2][state[c*4+2]] ^ TypeII[3][state[c*4+3]]   // SubBytes+MixColumns+AddRoundKey
     *   state[c*4+k] = byte(cols[c] >> (8*k))                        // LE scatter
     * encoded[P] = TypeI[144+P][state[InvShiftRows[P]]]
     * result[P]  = encoded[P] ^ tailSPNXORMask[P]
     * ```
     *
     * [mixTable] is the 144-byte mixing table from Block A's output.
     */
    internal fun tailSpnReference(input: ByteArray, mixTable: ByteArray): ByteArray {
        require(input.size == 16) { "TailSPN input is 16 bytes, got ${input.size}" }
        require(mixTable.size >= 144) { "TailSPN mixTable is 144 bytes, got ${mixTable.size}" }

        val state = input.copyOf()

        for (round in 0 until 9) {
            // Inter-round encoding (identity for round 0).
            if (round > 0) {
                val newState = ByteArray(16)
                for (pos in 0 until 16) {
                    val srcPos = Phase2Tables.tailSPNInvShiftRows[pos]
                    val typeIIdx = Phase2Tables.tailSPNTypeI[round][15 - pos]
                    newState[pos] = (FairPlayWhiteBoxTables.typeI[typeIIdx][state[srcPos].toInt() and 0xFF].toInt() xor
                        (mixTable[(8 - round) * 16 + pos].toInt() and 0xFF)).toByte()
                }
                newState.copyInto(state)
            }

            // TypeII T-box (SubBytes + MixColumns + AddRoundKey).
            val cols = IntArray(4)
            for (col in 0 until 4) {
                cols[col] = FairPlayWhiteBoxTables.typeII[0][state[col * 4].toInt() and 0xFF] xor
                    FairPlayWhiteBoxTables.typeII[1][state[col * 4 + 1].toInt() and 0xFF] xor
                    FairPlayWhiteBoxTables.typeII[2][state[col * 4 + 2].toInt() and 0xFF] xor
                    FairPlayWhiteBoxTables.typeII[3][state[col * 4 + 3].toInt() and 0xFF]
            }

            // Scatter output (LE byte order within each column).
            for (col in 0 until 4) {
                val word = cols[col]
                state[col * 4] = word.toByte()
                state[col * 4 + 1] = (word ushr 8).toByte()
                state[col * 4 + 2] = (word ushr 16).toByte()
                state[col * 4 + 3] = (word ushr 24).toByte()
            }
        }

        // Final output encoding, then the constant post-encoding XOR mask.
        val encoded = ByteArray(16)
        for (p in 0 until 16) {
            encoded[p] = (FairPlayWhiteBoxTables.typeI[144 + p][state[Phase2Tables.tailSPNInvShiftRows[p]].toInt() and 0xFF].toInt() xor
                (Phase2Tables.tailSPNXORMask[p].toInt() and 0xFF)).toByte()
        }
        return encoded
    }

    /**
     * tail_spn_fast.go `TailSPN`: the 9-round WB-AES SPN, analytically.
     *
     * Identical to [tailSpnReference]; the only difference is that the final
     * output encoding and the constant XOR mask are folded into one table
     * ([Phase2Tables.tailSPNFinalI]) and the column scatter is a word write.
     *
     * [mixTable] is the 144-byte mixing table from Block A's output.
     */
    fun tailSpn(tailIn: ByteArray, mixTable: ByteArray): ByteArray {
        require(tailIn.size == 16) { "TailSPN input is 16 bytes, got ${tailIn.size}" }
        require(mixTable.size >= 144) { "TailSPN mixTable is 144 bytes, got ${mixTable.size}" }

        val state = tailIn.copyOf()
        val typeII = FairPlayWhiteBoxTables.typeII

        for (round in 0 until 9) {
            // Inter-round encoding (identity for round 0).
            if (round > 0) {
                val typeI = Phase2Tables.tailSPNTypeI[round]
                val mixBase = (8 - round) * 16
                val newState = ByteArray(16)
                for (pos in 0 until 16) {
                    val srcPos = Phase2Tables.tailSPNInvShiftRows[pos]
                    newState[pos] = (FairPlayWhiteBoxTables.typeI[typeI[15 - pos]][state[srcPos].toInt() and 0xFF].toInt() xor
                        (mixTable[mixBase + pos].toInt() and 0xFF)).toByte()
                }
                newState.copyInto(state)
            }

            // TypeII T-box, then scatter. The four bytes of a column go to
            // consecutive positions in little-endian order, so the scatter is
            // just the word write. The word is computed before any of its four
            // bytes are stored, so the read-then-write order is preserved.
            for (col in 0 until 4) {
                val p = col * 4
                val word = typeII[0][state[p].toInt() and 0xFF] xor
                    typeII[1][state[p + 1].toInt() and 0xFF] xor
                    typeII[2][state[p + 2].toInt() and 0xFF] xor
                    typeII[3][state[p + 3].toInt() and 0xFF]
                state[p] = word.toByte()
                state[p + 1] = (word ushr 8).toByte()
                state[p + 2] = (word ushr 16).toByte()
                state[p + 3] = (word ushr 24).toByte()
            }
        }

        val encoded = ByteArray(16)
        for (p in 0 until 16) {
            encoded[p] = Phase2Tables.tailSPNFinalI[p][state[Phase2Tables.tailSPNInvShiftRows[p]].toInt() and 0xFF]
        }
        return encoded
    }

    // =====================================================================
    // prologue.go / neon_state.go -- the NEON prologue block
    // =====================================================================

    /** prologue.go `shl32Lanes`: 32-bit lane-wise shift left on a 64-bit value. */
    private fun shl32Lanes(v: Long, shift: Int): Long {
        val lo = ((v and 0xFFFFFFFFL) shl shift) and 0xFFFFFFFFL
        val hi = (((v ushr 32) shl shift) and 0xFFFFFFFFL) shl 32
        return lo or hi
    }

    /** prologue.go `add32Lanes`: 32-bit lane-wise addition on two 32-bit lanes. */
    private fun add32Lanes(a: Long, b: Long): Long {
        val lo = ((a and 0xFFFFFFFFL) + (b and 0xFFFFFFFFL)) and 0xFFFFFFFFL
        val hi = (((a ushr 32) + (b ushr 32)) and 0xFFFFFFFFL) shl 32
        return lo or hi
    }

    /**
     * prologue.go `neonBlock` / `NeonBlockExport`: the ARM64 NEON prologue
     * transformation on one 128-bit block, all operations 32-bit lane-wise.
     *
     * ```
     * temp   = data ^ xorMask          // XOR with constant mask
     * data   = (data << 1) & andMask   // SHL by 1, AND with constant
     * data   = temp + data             // 32-bit lane-wise ADD
     * data   = data + addBias          // 32-bit lane-wise ADD with bias
     * ```
     *
     * Returns the four 32-bit words `w0..w3` in lane order.
     */
    private fun neonBlock(
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
     * neon_state.go `bridgeNeonState`: builds Phase 2's vector inputs.
     *
     * Only Vreg0 is payload-dependent: it is `x9Data[0:16]` put through the NEON
     * prologue transform, with the three constant vector registers as its masks.
     *
     * Note the argument order -- `xor = bridgeVreg1`, `and = bridgeVreg3`,
     * `add = bridgeVreg2`:
     *
     * ```
     * w0..w3 = NeonBlockExport(LE64(x9[0:8]), LE64(x9[8:16]),
     *                          bridgeVreg1, bridgeVreg3, bridgeVreg2)
     * ```
     *
     * [x9Data] is [FairPlaySapCore.bridgeX9HeadForSap]'s 20-byte output; only
     * the first 16 bytes are read.
     */
    fun bridgeNeonState(x9Data: ByteArray): NeonState {
        require(x9Data.size >= 16) { "x9Data carries 16 bytes into the NEON prologue, got ${x9Data.size}" }

        val v0lo = leU64(x9Data, 0)
        val v0hi = leU64(x9Data, 8)
        val w = neonBlock(v0lo, v0hi, Phase2Tables.bridgeVreg1, Phase2Tables.bridgeVreg3, Phase2Tables.bridgeVreg2)

        val vreg0 = longArrayOf(
            (w[0].toLong() and 0xFFFFFFFFL) or ((w[1].toLong() and 0xFFFFFFFFL) shl 32),
            (w[2].toLong() and 0xFFFFFFFFL) or ((w[3].toLong() and 0xFFFFFFFFL) shl 32),
        )
        // Go's bridgeVregN are [2]uint64 *values*, so they are copied into the
        // struct; copy them here too rather than aliasing the shared tables.
        return NeonState(
            vreg0 = vreg0,
            vreg1 = Phase2Tables.bridgeVreg1.copyOf(),
            vreg2 = Phase2Tables.bridgeVreg2.copyOf(),
            vreg3 = Phase2Tables.bridgeVreg3.copyOf(),
        )
    }
}
