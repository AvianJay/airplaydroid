package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * FairPlay SAP **Phase 1**: the white-box AES that turns an m2 challenge into the
 * 128-byte "GP buffer".
 *
 * This is the irreducible part of FairPlay. The m3 *body* cipher
 * ([FairPlayMessageCipher]) is ordinary AES with baked round keys, but Phase 1 is
 * a genuine white-box construction: its Type-I tables are neither the AES S-box
 * nor any XOR-affine image of it, so it cannot be reduced to a key schedule. The
 * tables are carried as base tables plus a spec and unpacked at init
 * ([FairPlayWhiteBoxTables]).
 *
 * ### The shape of it
 *
 * ```
 * for each of 8 blocks:
 *     raw = wbaesBlockCore(block)          // 9 TypeI->TypeII rounds + round 10
 *     blockOut = block == 0 ? outputDecode(raw) : raw xor 0x0F
 *     gp[block] = block == 0 ? blockOut : blockOut xor payload[block-1]
 * ```
 *
 * Three details are load-bearing and each is silent when wrong:
 *
 *  - **Block 0 alone** uses the 16 complex output-decoding bijections; blocks 1-7
 *    use a plain `xor 0x0F`. Using one rule for both produces a plausible buffer.
 *  - The GP buffer is written through [FairPlayWhiteBoxTables.GP_BUFFER_PERM],
 *    not in place.
 *  - Blocks 1-7 are chained against the **input payload**, not the previous
 *    block's output.
 *
 * Ported from `github.com/objevovat/fairplay-sap-core-...` (`fpbridge`,
 * LGPL-3.0-or-later). See NOTICE.
 */
internal object FairPlayWhiteBox {

    private const val BLOCK = 16
    private const val BLOCKS = 8

    /** The trivial per-byte output encoding used for blocks 1-7. */
    private const val BLOCK_OUTPUT_MASK = 0x0F

    /**
     * Computes the 128-byte GP buffer for one challenge.
     *
     * The result is Phase 1's output; Phase 2 (the SAP hash and bridge in
     * [FairPlaySapCore]) consumes it to produce the 20-byte response.
     */
    fun phase1(challenge: ByteArray): ByteArray {
        require(challenge.size == 128) { "a challenge is 128 bytes, got ${challenge.size}" }

        val tables = FairPlayWhiteBoxTables
        val gp = ByteArray(128)

        for (block in 0 until BLOCKS) {
            val input = challenge.copyOfRange(block * BLOCK, block * BLOCK + BLOCK)
            val raw = blockCore(input)

            // Output encoding differs between block 0 and the rest.
            val blockOut = ByteArray(BLOCK)
            if (block == 0) {
                for (i in 0 until BLOCK) {
                    blockOut[tables.GP_BUFFER_PERM[i]] = tables.outputDec[i][raw[i].toInt() and 0xFF].toByte()
                }
            } else {
                for (i in 0 until BLOCK) {
                    blockOut[tables.GP_BUFFER_PERM[i]] =
                        ((raw[i].toInt() and 0xFF) xor BLOCK_OUTPUT_MASK).toByte()
                }
            }
            if (block == 0) {
                blockOut.copyInto(gp, 0)
            } else {
                for (i in 0 until BLOCK) {
                    gp[block * BLOCK + i] = (blockOut[i].toInt() xor challenge[(block - 1) * BLOCK + i].toInt()).toByte()
                }
            }
        }
        return gp
    }

    /**
     * The WB-AES core: nine TypeI → σ → TypeII rounds, then a Type-I-only round 10.
     *
     * The mixing constants are calibrated from the tables themselves rather than
     * carried as data -- see [mixingConstants].
     */
    private fun blockCore(input: ByteArray): ByteArray {
        val tables = FairPlayWhiteBoxTables
        val mix = mixingConstants

        // Round 1 loads the input through sigma-inverse; later rounds enter naturally.
        val state = ByteArray(BLOCK) { input[tables.MIXING_SIGMA_INV[it]].toByte() }

        for (round in 0 until 9) {
            val orderBase = round * BLOCK
            val mixBase = round * BLOCK

            // Type-I lookups.
            val typeIOut = ByteArray(BLOCK)
            for (i in 0 until BLOCK) {
                val table = tables.TYPE_I_ORDER_FLAT[orderBase + i]
                typeIOut[i] = tables.typeI[table][state[i].toInt() and 0xFF].toByte()
            }

            // sigma, then the round's mixing constant.
            val sub = ByteArray(BLOCK)
            for (i in 0 until BLOCK) {
                sub[i] = (typeIOut[tables.MIXING_SIGMA[i]].toInt() xor mix[mixBase + i]).toByte()
            }

            // Type-II lookups combine SubBytes and MixColumns; XOR the four per column.
            val cols = IntArray(4)
            for (col in 0 until 4) {
                val p = col * 4
                var acc = 0
                for (lane in 0 until 4) {
                    val pos = tables.SHIFT_ROWS_COLS_FLAT[p + lane]
                    acc = acc xor tables.typeII[lane][sub[pos].toInt() and 0xFF]
                }
                cols[col] = acc
            }

            // Scatter the four column words back into the state.
            state[0] = cols[0].toByte()
            state[1] = cols[1].toByte()
            state[2] = cols[2].toByte()
            state[3] = cols[3].toByte()
            state[4] = (cols[2] ushr 8).toByte()
            state[5] = (cols[1] ushr 8).toByte()
            state[6] = (cols[0] ushr 8).toByte()
            state[7] = (cols[3] ushr 8).toByte()
            state[8] = (cols[2] ushr 16).toByte()
            state[9] = (cols[0] ushr 16).toByte()
            state[10] = (cols[3] ushr 16).toByte()
            state[11] = (cols[1] ushr 16).toByte()
            state[12] = (cols[1] ushr 24).toByte()
            state[13] = (cols[2] ushr 24).toByte()
            state[14] = (cols[3] ushr 24).toByte()
            state[15] = (cols[0] ushr 24).toByte()
        }

        // Round 10: Type-I only, no MixColumns, and NO output decoding.
        //
        // The output decoding belongs to phase1, not here: it differs between
        // block 0 (the 16 outputDec bijections) and blocks 1-7 (a plain xor
        // 0x0F). Applying it here as well would decode twice, which still
        // produces a full-looking 128-byte buffer and is therefore silent.
        val orderBase = 9 * BLOCK
        val raw = ByteArray(BLOCK)
        for (i in 0 until BLOCK) {
            val table = tables.TYPE_I_ORDER_FLAT[orderBase + i]
            raw[i] = tables.typeI[table][state[i].toInt() and 0xFF].toByte()
        }
        return raw
    }

    /**
     * The nine per-round mixing constants, calibrated from the tables at first use.
     *
     * The upstream source carries these as data too, but derives them from the
     * tables by running an all-zero state through each round. Deriving is
     * preferred here because it cannot drift from the tables: if the tables are
     * right, the constants are right.
     *
     * `mixConst[i] = typeIOut[sigma(i)] xor subZero[i]`, where both sides are the
     * zero-input round output.
     */
    private val mixingConstants: IntArray by lazy {
        val tables = FairPlayWhiteBoxTables
        val result = IntArray(9 * BLOCK)
        var zeroState = ByteArray(BLOCK)

        for (round in 0 until 9) {
            val orderBase = round * BLOCK
            val xorBase = round * BLOCK

            val t0 = ByteArray(BLOCK)
            for (i in 0 until BLOCK) {
                val table = tables.TYPE_I_ORDER_FLAT[orderBase + i]
                t0[i] = tables.typeI[table][zeroState[i].toInt() and 0xFF].toByte()
            }

            // The upstream constant and the table's zero output agree; the
            // constant is what makes the round correct for non-zero input.
            val subZero = ByteArray(BLOCK)
            for (i in 0 until BLOCK) {
                subZero[i] = (t0[i].toInt() xor tables.XOR_CONSTS_FLAT[xorBase + i]).toByte()
            }

            for (i in 0 until BLOCK) {
                result[round * BLOCK + i] = (t0[tables.MIXING_SIGMA[i]].toInt() xor subZero[i].toInt()) and 0xFF
            }

            // Advance the zero state through this round, for the next round.
            val cols = IntArray(4)
            for (col in 0 until 4) {
                val p = col * 4
                var acc = 0
                for (lane in 0 until 4) {
                    val pos = tables.SHIFT_ROWS_COLS_FLAT[p + lane]
                    acc = acc xor tables.typeII[lane][subZero[pos].toInt() and 0xFF]
                }
                cols[col] = acc
            }
            zeroState = byteArrayOf(
                cols[0].toByte(), cols[1].toByte(), cols[2].toByte(), cols[3].toByte(),
                (cols[2] ushr 8).toByte(), (cols[1] ushr 8).toByte(),
                (cols[0] ushr 8).toByte(), (cols[3] ushr 8).toByte(),
                (cols[2] ushr 16).toByte(), (cols[0] ushr 16).toByte(),
                (cols[3] ushr 16).toByte(), (cols[1] ushr 16).toByte(),
                (cols[1] ushr 24).toByte(), (cols[2] ushr 24).toByte(),
                (cols[3] ushr 24).toByte(), (cols[0] ushr 24).toByte(),
            )
        }
        result
    }
}
