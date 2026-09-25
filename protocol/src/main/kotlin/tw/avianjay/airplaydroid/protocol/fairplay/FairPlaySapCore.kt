/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * Transcribed verbatim from the `objevovat` project
 * fairplay-sap-core-airplay2-sender-authentication-handshake,
 * ports/kotlin/FairPlaySapCore.kt:
 *   https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake
 * The only changes made here are the `package` line and this header. No
 * constant, function or statement was renamed, reordered or "tidied".
 *
 * APPLE-DERIVED REVERSE-ENGINEERED DATA. The SAP seed, initial hash, initial
 * matrix, session key, descriptor prefix/suffix and the straight-line byte
 * circuit below are recovered from Apple's FairPlay SAP implementation. They
 * are opaque recovered constants, not portable logic -- do not derive,
 * generalise or optimise them.
 *
 * Derived from github.com/omarroth/doubletake at 8ccea5f, via
 * fpsapcore. See ../../NOTICE.md.
 *
 * FairPlaySapCore.kt - the FairPlay SAP Phase-1 bridge.
 *
 * FairPlayBridge.kt in this directory has the bridge *primitive*. This has the
 * functions that feed it, so the two together are a complete responder:
 *
 *     FairPlaySapCore.bridgeX9HeadForSap(localSap, gp) -> ByteArray(20)
 *
 * `gp` is Phase 1's 128-byte output buffer. The 20 bytes out are the only
 * payload-dependent input Phase 2 consumes. No dependencies beyond the
 * Kotlin/JVM standard library.
 *
 * --- FOUR THINGS THAT WILL BITE A PORT --------------------------------------
 * Each of the first three is silent, and each fails 30+ of the 40 vectors in
 * ../../conformance/.
 *
 *  1. The JVM has no unsigned byte. Every intermediate here is an Int holding
 *     0..255, masked with `and 0xFF`, and ByteArray appears only at the API
 *     boundary. Doing the arithmetic in Kotlin's signed `Byte` gives negative
 *     intermediates that then index arrays out of range.
 *  2. The ring index derivation underflows a 32-bit unsigned value on purpose.
 *     Plain Int arithmetic gives -155 where the answer must be 101, so it goes
 *     through `toUInt()`. See ../../conformance/README.md.
 *  3. rotateOrZero returns 0 for a zero count, not the input. It is not a
 *     rotate.
 *  4. wideSeed's index is computed wider than a byte. Masking it to 8 bits
 *     changes the answer.
 */

package tw.avianjay.airplaydroid.protocol.fairplay

object FairPlaySapCore {

    // --- byte helpers -------------------------------------------------------

    /** Rotate an 8-bit value left. `v` and the result are Ints holding 0..255. */
    private fun rotl8(v: Int, n: Int): Int {
        val k = n and 7
        if (k == 0) return v and 0xFF
        return ((v shl k) or ((v and 0xFF) ushr (8 - k))) and 0xFF
    }

    /**
     * Go's rotateOrZero: a count of 0 yields 0, NOT the input. This is the one
     * rotate in the algorithm that is not a rotate.
     */
    fun rotateOrZero(value: Int, count: Int): Int {
        val c = count and 0xFF
        if (c == 0) return 0
        return rotl8(value and 0xFF, c)
    }

    private fun majority(a: Int, b: Int, c: Int): Int =
        (a xor ((a xor b) and (a xor c))) and 0xFF

    private fun selectBits(mask: Int, ifSet: Int, ifClear: Int): Int =
        (ifClear xor ((ifSet xor ifClear) and mask)) and 0xFF

    private fun square(v: Int): Int = (v * v) and 0xFF

    private fun cube(v: Int): Int = (v * v * v) and 0xFF

    /** Go's `&^` (AND NOT / bit clear). Kotlin has no operator for it. */
    private fun andNot(a: Int, b: Int): Int = a and b.inv() and 0xFF

    /** Go's `^v` on a byte is a bitwise NOT, not an XOR. */
    private fun not(v: Int): Int = v.inv() and 0xFF

    // --- constants ----------------------------------------------------------

    val SAP_SEED = intArrayOf(
        0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9, 0x11,
        0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49, 0xC7,
    )

    private val SAP_INITIAL_HASH = intArrayOf(
        0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE,
        0xB2, 0xF8, 0x38, 0x62, 0xEC, 0x22, 0x93, 0xD1, 0x20, 0x8F,
    )

    private val SAP_INITIAL_MATRIX = intArrayOf(
        0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56,
        0xF6, 0x1C, 0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1,
        0x39, 0x51, 0x4A, 0xAA, 0x09, 0x3E, 0xFE, 0x44, 0xAF, 0xDE,
        0xC3, 0x20, 0x9D, 0x42, 0xB8,
    )

    private val INITIAL_SESSION_KEY = intArrayOf(
        0xDC, 0xDC, 0xF3, 0xB9, 0x0B, 0x74, 0xDC, 0xFB,
        0x86, 0x7F, 0xF7, 0x60, 0x16, 0x72, 0x90, 0x51,
    )

    private val DESCRIPTOR_PREFIX = intArrayOf(
        0xA0, 0x44, 0x9C, 0x4D, 0x09, 0xE4, 0xBD, 0x7F, 0x6E,
        0xC5, 0xD0, 0xCC, 0x35, 0x9D, 0xA7, 0x46, 0x7A,
    )

    private val DESCRIPTOR_SUFFIX = intArrayOf(
        0x97, 0xB5, 0x0F, 0x84, 0xE2, 0x15, 0x5A, 0x9C, 0x24,
        0x99, 0x1C, 0xF4, 0x3A, 0x09, 0x63, 0x55, 0x47,
    )

    /**
     * The white-box output encoding Phase 1 leaves on the GP buffer: one XOR
     * constant across all 128 bytes. Measured, not assumed.
     */
    const val GP_OUTPUT_MASK = 0x0F

    /**
     * Go's wideSeed. The index is computed WIDER than a byte: `value shl count`
     * may exceed 255 before the modulo. Masking it to 8 bits changes the result.
     */
    fun wideSeed(value: Int, count: Int): Int {
        val c = count and 0xFF
        if (c == 0) return SAP_SEED[0]
        val v = value and 0xFF
        val wide = (v shl c) or (v ushr (8 - c))   // deliberately unmasked
        return SAP_SEED[wide % SAP_SEED.size]
    }

    // --- the ring index tables ----------------------------------------------

    /**
     * The four index sequences.
     *
     * The subtraction is 32-bit UNSIGNED and wraps for i below the subtrahend.
     * Kotlin's Int is signed, so plain `i - 155` gives -155 at i=0 where the
     * answer must be 101; going through UInt reproduces the wrap.
     */
    fun buildRingIndices(): Array<IntArray> {
        val x = IntArray(840)
        val y = IntArray(840)
        val z = IntArray(840)
        val w = IntArray(840)
        for (i in 0 until 840) {
            x[i] = ((i - 155).toUInt() % 210u).toInt()
            y[i] = ((i - 57).toUInt() % 210u).toInt()
            z[i] = ((i - 13).toUInt() % 210u).toInt()
            w[i] = (i.toUInt() % 210u).toInt()
        }
        return arrayOf(x, y, z, w)
    }

    /** work is three copies of the permuted block plus its first 18 bytes. */
    private fun fillWork(block: ByteArray): IntArray {
        val p = IntArray(64) { block[it xor 3].toInt() and 0xFF }
        val work = IntArray(210)
        for (i in 0 until 64) {
            work[i] = p[i]; work[64 + i] = p[i]; work[128 + i] = p[i]
        }
        for (i in 0 until 18) work[192 + i] = p[i]
        return work
    }

    // --- the SAP hash -------------------------------------------------------

    /** FairPlay's proprietary SAP hash of one 64-byte block. Not a standard hash. */
    fun sapHash(block: ByteArray): ByteArray {
        require(block.size == 64) { "block must be 64 bytes, got ${block.size}" }

        val ring = buildRingIndices()
        val rx = ring[0]; val ry = ring[1]; val rz = ring[2]; val rw = ring[3]

        val hash = SAP_INITIAL_HASH.copyOf()
        val matrix = SAP_INITIAL_MATRIX.copyOf()
        val aux = IntArray(10)
        val work = fillWork(block)

        for (i in 0 until 840) {
            val xv = work[rx[i]]
            val yv = work[ry[i]]
            val zv = work[rz[i]]
            val wi = rw[i]
            work[wi] = (rotl8(yv, 5) + (rotl8(zv, 3) xor work[wi]) - rotl8(xv, 7)) and 0xFF
        }

        nonlinearCircuit(hash, matrix, aux, work)

        val out = IntArray(16)
        // Go: copy(out[:], aux[:3]) then copy(out[4:], aux[3:]) - 3 then 7 bytes.
        for (i in 0 until 3) out[i] = aux[i]
        for (i in 0 until 7) out[4 + i] = aux[3 + i]
        for (i in 0 until 16) out[i] = (out[i] + 0xE1) and 0xFF
        out[3] = 0x3D
        out[11] = 0x3C
        out[10] = out[10] xor ((aux[3] xor 133) and 0xFF)

        for (i in 0 until 20) out[i and 15] = out[i and 15] xor work[i] xor matrix[i] xor hash[i]
        for (i in 20 until 35) out[i and 15] = out[i and 15] xor work[i] xor matrix[i]
        for (i in 35 until 210) out[i and 15] = out[i and 15] xor work[i]

        applyScramble(out)
        return ByteArray(16) { out[it].toByte() }
    }

    /**
     * 256 rounds of XOR-and-rotate, in place over 16 lanes. Every operation is
     * GF(2)-linear, so this collapses to a 128x128 binary matrix - which is what
     * the Go version ships for speed. The loop is kept here because a snippet is
     * for reading, and the matrix is 2 KB of opaque data.
     */
    fun applyScramble(out: IntArray) {
        for (i in 0 until 256) {
            out[i and 15] = out[i and 15] xor
                rotl8(out[(i - 7) and 15], 1) xor
                rotl8(out[(i - 5) and 15], 6) xor
                rotl8(out[(i - 1) and 15], 5)
        }
    }

    /**
     * The straight-line byte circuit. Statement order is load-bearing: several
     * lines assign to a cell a later line reads, and matrix[12] is written three
     * times. Reordering for tidiness breaks it.
     */
    private fun nonlinearCircuit(hash: IntArray, matrix: IntArray, aux: IntArray, work: IntArray) {
        fun hi(i: Int) = hash[(i and 0xFF) % 20]
        fun si(i: Int) = SAP_SEED[(i and 0xFF) % 21]
        fun h(i: Int) = hi(work[i])
        fun m(i: Int) = matrix[work[i] % 35]
        fun s(i: Int) = si(work[i])
        fun ma(i: Int) = matrix[aux[i] % 35]
        fun neg(v: Int) = (-v) and 0xFF

        matrix[12] = (0x14 + (selectBits(92, work[64], work[99] / 3) and wideSeed(s(206), 4))) and 0xFF
        work[4] = (2 * square(work[99] / 5)) and 0xFF
        work[153] = work[153] xor ((square(m(203)) * work[190]) and 0xFF)
        hash[3] = 0x13 xor ((s(205) ushr 1) and 0x10)
        work[33] = (work[33] - andNot(s(36), 9)) and 0xFF
        aux[5] = ((andNot(m(67), 2) or 1 or ((h(181) ushr 6) and 2) or (hash[3] and 0x10)) - 15) and 0xFF
        matrix[12] = 0x07
        work[2] = (work[2] - 64) and 0xFF
        hash[19] = s(58)
        aux[4] = (92 - m(32)) and 0xFF
        aux[9] = (m(15) + 0x9E) and 0xFF
        work[34] = (work[34] + si(aux[9]) / 5) and 0xFF
        hash[19] = (hash[19] + (0xE6 xor ((hi(aux[9]) ushr 1) and 0x66))) and 0xFF
        work[15] = work[15] xor ((3 * rotateOrZero(work[72], neg(s(190)) and 7) - 9 * s(126)) and 0xFF)
        hash[15] = hash[15] xor cube(m(181))
        matrix[4] = matrix[4] xor (work[202] / 3)
        matrix[1] = (matrix[1] + cube(majority((92 - hi(aux[4])) and 0xFF, not(work[105]), 0xC6))) and 0xFF
        // int math, then truncate
        hash[19] = hash[19] xor ((((224 or (s(92) and 27)) * m(41)) / 3) and 0xFF)
        work[140] = (work[140] + rotateOrZero(92, neg(work[5]) and 7)) and 0xFF
        matrix[12] = (matrix[12] + majority(not(work[4]) xor m(12), work[182], 192)) and 0xFF
        work[36] = (work[36] + 125) and 0xFF
        work[124] = rotl8(majority(majority(work[138], hash[15], 74), h(43), 95), 4)
        val auxHash = hi(aux[9])
        aux[1] = andNot(0x4C, auxHash and ((s(68) shl 1) and 0xFF))
        aux[2] = (222 - majority(((work[177] + s(79)) ushr 1) and 0xFF,
                                 (3 * work[148] / 5) and 0xFF, matrix[1])) and 0xFF
        matrix[16] = (matrix[16] + ((andNot(ma(4), 0x60) or auxHash or 8) -
                                    (rotl8(work[33], 2) or 128))) and 0xFF
        hash[14] = hash[14] xor ma(2)
        work[19] = (work[19] + majority(
            rotateOrZero(si(h(201)), (m(112) shl 1) and 6),
            (andNot(h(208), 0x7C) or (h(164) and 0x7C)) / 5, 37)) and 0xFF
        matrix[8] = rotateOrZero(140, neg(square(s(45))) and 7) xor aux[4]
        work[190] = 56
        work[53] = not((h(83) or 204) / 5)
        hash[13] = (hash[13] + h(41)) and 0xFF
        hash[10] = majority(ma(4), work[2], aux[2]) / 15
        aux[3] = (92 - square(0x28 or (ma(1) and (0x12 or (s(2) and 4))))) and 0xFF
        val seedBits = si(aux[4])
        matrix[13] = matrix[13] xor seedBits
        aux[6] = (92 + square(majority((m(179) - 38) and 0xFF, aux[2], 177))) and 0xFF
        val expansionBits = majority((aux[3] + (aux[4] and 74)) and 0xFF, not(seedBits), 121)
        work[47] = work[47] xor ((m(89) + majority(expansionBits xor 0xA6, aux[4], 4)) and 0xFF)
        aux[7] = (seedBits / 3 - ma(9) -
                  (0x14 or (work[151] and ((aux[4] and 0x88) or 0x62)) or (aux[4] and 0x22))) and 0xFF
        val expandedSelector = expansionBits xor ((aux[4] and 0xCA) ushr 1) xor 75
        aux[9] = (aux[9] + (0x80 or (majority(aux[7], work[151], 0x20) and 0x64) or
                            (seedBits and 0x44) or (ma(9) and 0x1B))) and 0xFF
        matrix[33] = matrix[33] xor work[26]
        matrix[30] = ((aux[9] / 3 - (andNot(aux[4], 8) or 0x13)) and 0xFF) xor h(122)
        work[22] = (m(90) and 0x1B) or 0x44
        var wide = selectBits(71, matrix[expandedSelector % 35], si(aux[5]))
        // int math, then truncate
        matrix[18] = (matrix[18] + ((wide * wide * wide) ushr 1)) and 0xFF
        matrix[5] = (matrix[5] - s(92)) and 0xFF
        matrix[18] = matrix[18] xor ((selectBits(aux[3], ma(3), selectBits(16, m(183), work[41])) *
                                      selectBits(expandedSelector, h(59), work[17])) and 0xFF)
        matrix[22] = (majority(
            selectBits(hash[14] or 28, (work[7] and 28) or 0x82, h(93)),
            rotateOrZero(ma(4), rotateOrZero(work[11], neg(m(28)) and 7) and 7),
            matrix[33]) + 74) and 0xFF
        hash[15] = (hash[15] - majority(majority(aux[3], aux[4], 214),
                                        si(h(39) xor 217), aux[6])) and 0xFF

        val hash9 = hi(aux[9])
        val indexedHash = hi(((aux[4] / 3 - (aux[9] or work[22])) and 0xFF) xor aux[6] xor
            (((m(57) or hash9) and (0x52 or (aux[9] and 0x0D))) or
             (((m(57) and hash9) or aux[9]) and 0x20)))
        aux[6] = square(square(h(99))) or ma(9)
        aux[1] = (aux[1] + rotateOrZero(h(151) or s(202), h(50) and 7) +
                  majority(h(4),
                      ((selectBits(matrix[16], indexedHash, m(138)) +
                        selectBits(17, work[33], s(39))) / 5) and 0xFF,
                      147)) and 0xFF
        aux[0] = selectBits(hash[10] and 7, ma(6) and h(209),
            selectBits(0x47, rotateOrZero(s(127), ma(6) and 7), (si(ma(5)) shl 1) and 0xFF))
        val selectedSquare = selectBits(198, square(m(14)), h(145) xor aux[0])
        val seed9 = si(aux[9])
        val hash3 = hi(aux[3])
        matrix[2] = (matrix[2] + ((((hash3 shl 1) and 0xFF) and
                                   ((work[25] and 0x96) or (seed9 and 8))) or (seed9 and 0x40))) and 0xFF
        matrix[14] = (matrix[14] - selectBits(34, work[97], ma(3) and (aux[0] xor m(100)))) and 0xFF
        work[23] = work[23] xor ((majority(majority(s(17), hash3, aux[0]), work[50] / 3, 0x76) shl 1) and 0xFF)
        hash[17] = 115
        hash[13] = ((majority(hi(aux[7]), work[10], 82) ushr 1) and 0x68) or (h(39) and 0x17)
        matrix[33] = (matrix[33] - (work[113] and 9)) and 0xFF
        matrix[28] = (matrix[28] - (andNot(aux[3], 0x20) or ((work[110] ushr 1) and 0x20))) and 0xFF
        work[95] = si(aux[3])
        hash[15] = majority((work[95] - 48) and 0xFF, not(work[184]), 189) and
                   cube(majority(aux[7], si(aux[1]), 0xAA))
        matrix[22] = (matrix[22] + work[183]) and 0xFF
        aux[4] = aux[4] xor ((3 * s(1)) and 0xFF)
        aux[5] = (aux[5] + 198 * majority(s(178), ma(1), 209) * h(13) * (s(26) ushr 1)) and 0xFF
        aux[8] = selectBits(10, ma(3), ma(9))
        matrix[18] = (matrix[18] - selectBits(hash[15], aux[5] / 15, cube(hi(aux[6]) or 81))) and 0xFF
        aux[1] = (aux[1] + si(hi(aux[1])) / 3 - h(160)) and 0xFF
        hash[16] = (147 - majority(aux[0],
            majority(s(69), work[172], (aux[2] - selectedSquare + 77) and 0xFF),
            0xC2 or (aux[0] and 5))) and 0xFF
        hash[3] = (hash[3] - wideSeed(majority(s(155), work[105], 141),
                                      majority(s(168), h(29), 6) and 7)) and 0xFF
        work[5] = rotateOrZero(0x38, neg(h(61) / 5) and 7) xor (not(ma(8)) / 5)
        work[198] = (work[198] + work[3]) and 0xFF
        wide = 162 or ma(9)
        // int math, then truncate
        work[164] = (work[164] + (wide * wide / 5)) and 0xFF
        aux[2] = majority(rotateOrZero(139, neg(aux[5]) and 6), hi(aux[3]), 12) or
                 selectBits(95, cube(seed9), hi(aux[7]))
        matrix[12] = (matrix[12] + (16 or ((work[103] or 60) and (aux[2] or (work[103] and 32)))) / 3) and 0xFF
        work[143] = (work[143] - (0x12 or (selectBits(aux[9],
                        selectBits(matrix[8], work[35], aux[7]), aux[8] / 3) and
                     (0x4D or ((work[172] ushr 1) and 0x20))))) and 0xFF
        matrix[29] = 162
        hash[15] = (hash[15] + majority(m(149) xor square(work[43]),
                        selectBits(95, h(125), si(aux[1])) ushr 1, 115)) and 0xFF
        aux[9] = (aux[9] - hi(aux[7])) and 0xFF
        hash[7] = (hash[7] - square(rotateOrZero(ma(5), neg(m(17) * (m(17) and 1)) and 0xFF))) and 0xFF
        matrix[8] = (matrix[8] + cube(s(202)) - work[184]) and 0xFF
        hash[16] = (m(102) shl 1) and 0x84
        aux[6] = aux[6] xor (si(aux[7]) ushr 1)
        hash[7] = (hash[7] - h(191) + selectBits(177, si(si(aux[1])), (s(80) shl 1) and 0xFF)) and 0xFF
        hash[6] = h(119)
        hash[12] = (hi(aux[8]) xor ((m(71) + m(15)) and 0xFF)) and
                   majority(andNot(work[118], 0x2C) or 2, square(hi(aux[9])), 27)
        val digestIndex = selectBits(0xA9, (s(57) * 231) and 0xFF, majority(work[32], ma(1), 23)) / 5
        val seedSample = si(aux[6])
        aux[5] = majority((seedSample and 0x1C) or (h(82) and 0xA2) or (si(digestIndex) and 0x41),
                          majority(cube(hi(aux[7])), work[82], 92), 192) xor digestIndex
        matrix[25] = matrix[25] xor ((2 * hi(aux[9]) * work[5] -
                        (rotateOrZero(aux[4], seedSample and 7) and ((aux[3] + 110) and 0xFF))) and 0xFF)
    }

    // --- the FairPlay MD5 family --------------------------------------------

    private val MD5_SHIFT = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val MD5_CONSTANT = intArrayOf(
        0xD76AA478.toInt(), 0xE8C7B756.toInt(), 0x242070DB.toInt(), 0xC1BDCEEE.toInt(),
        0xF57C0FAF.toInt(), 0x4787C62A.toInt(), 0xA8304613.toInt(), 0xFD469501.toInt(),
        0x698098D8.toInt(), 0x8B44F7AF.toInt(), 0xFFFF5BB1.toInt(), 0x895CD7BE.toInt(),
        0x6B901122.toInt(), 0xFD987193.toInt(), 0xA679438E.toInt(), 0x49B40821.toInt(),
        0xF61E2562.toInt(), 0xC040B340.toInt(), 0x265E5A51.toInt(), 0xE9B6C7AA.toInt(),
        0xD62F105D.toInt(), 0x02441453.toInt(), 0xD8A1E681.toInt(), 0xE7D3FBC8.toInt(),
        0x21E1CDE6.toInt(), 0xC33707D6.toInt(), 0xF4D50D87.toInt(), 0x455A14ED.toInt(),
        0xA9E3E905.toInt(), 0xFCEFA3F8.toInt(), 0x676F02D9.toInt(), 0x8D2A4C8A.toInt(),
        0xFFFA3942.toInt(), 0x8771F681.toInt(), 0x6D9D6122.toInt(), 0xFDE5380C.toInt(),
        0xA4BEEA44.toInt(), 0x4BDECFA9.toInt(), 0xF6BB4B60.toInt(), 0xBEBFBC70.toInt(),
        0x289B7EC6.toInt(), 0xEAA127FA.toInt(), 0xD4EF3085.toInt(), 0x04881D05.toInt(),
        0xD9D4D039.toInt(), 0xE6DB99E5.toInt(), 0x1FA27CF8.toInt(), 0xC4AC5665.toInt(),
        0xF4292244.toInt(), 0x432AFF97.toInt(), 0xAB9423A7.toInt(), 0xFC93A039.toInt(),
        0x655B59C3.toInt(), 0x8F0CCC92.toInt(), 0xFFEFF47D.toInt(), 0x85845DD1.toInt(),
        0x6FA87E4F.toInt(), 0xFE2CE6E0.toInt(), 0xA3014314.toInt(), 0x4E0811A1.toInt(),
        0xF7537E82.toInt(), 0xBD3AF235.toInt(), 0x2AD7D2BB.toInt(), 0xEB86D391.toInt(),
    )

    private fun rotl32(v: Int, n: Int): Int {
        val k = n and 31
        if (k == 0) return v
        return (v shl k) or (v ushr (32 - k))
    }

    /**
     * Standard MD5 rounds and constants, but big-endian message words and a
     * message-schedule mutation after round 31. java.security.MessageDigest
     * cannot do this.
     *
     * The state is Int; Kotlin's Int arithmetic wraps at 32 bits exactly like
     * Go's uint32, so no masking is needed here. Only the >>> (ushr) matters,
     * because >> would sign-extend.
     */
    private fun md5Compress(state: IntArray, padded: IntArray, off: Int) {
        val message = IntArray(16) {
            (padded[off + it * 4] shl 24) or (padded[off + it * 4 + 1] shl 16) or
            (padded[off + it * 4 + 2] shl 8) or padded[off + it * 4 + 3]
        }

        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]

        for (round in 0 until 64) {
            val f: Int
            val word: Int
            when {
                round < 16 -> { f = (b and c) or (b.inv() and d); word = round }
                round < 32 -> { f = (d and b) or (d.inv() and c); word = (5 * round + 1) and 15 }
                round < 48 -> { f = b xor c xor d; word = (3 * round + 5) and 15 }
                else -> { f = c xor (b or d.inv()); word = (7 * round) and 15 }
            }

            val nextB = b + rotl32(a + f + MD5_CONSTANT[round] + message[word], MD5_SHIFT[round])
            // Go: a, b, c, d = d, nextB, b, c - one simultaneous rotation.
            val prevB = b; val prevC = c
            a = d; d = prevC; c = prevB; b = nextB

            if (round == 31) mutateMessage(message, a, b, c, d)
        }

        state[0] += a; state[1] += b; state[2] += c; state[3] += d
    }

    /**
     * Only the cycle mutation is reachable from the descriptor; the swap and KDF
     * variants live in the Go reference.
     */
    private fun mutateMessage(message: IntArray, a: Int, b: Int, c: Int, d: Int) {
        val idx = intArrayOf(
            a and 15, b and 15, c and 15, d and 15,
            (a ushr 4) and 15, (b ushr 4) and 15, (c ushr 4) and 15, (d ushr 4) and 15,
        )
        val first = message[idx[0]]
        for (i in 0 until idx.size - 1) message[idx[i]] = message[idx[i + 1]]
        message[idx[idx.size - 1]] = first
    }

    // --- the descriptor and the bridge --------------------------------------

    /** The 20-byte descriptor over prefix || m3Sap || m2Sap || suffix. */
    fun descriptorForSap(m3Sap: ByteArray, m2Sap: ByteArray): ByteArray {
        require(m3Sap.size == 128) { "m3Sap must be 128 bytes" }
        require(m2Sap.size == 128) { "m2Sap must be 128 bytes" }

        val padded = IntArray(320)
        var off = 0
        for (v in DESCRIPTOR_PREFIX) padded[off++] = v
        for (v in m3Sap) padded[off++] = v.toInt() and 0xFF
        for (v in m2Sap) padded[off++] = v.toInt() and 0xFF
        for (v in DESCRIPTOR_SUFFIX) padded[off++] = v
        padded[off] = 0x80
        val bits = off.toLong() * 8L
        for (i in 0 until 8) padded[312 + i] = ((bits ushr (8 * i)) and 0xFF).toInt()

        val state = IntArray(4) {
            INITIAL_SESSION_KEY[it * 4] or (INITIAL_SESSION_KEY[it * 4 + 1] shl 8) or
            (INITIAL_SESSION_KEY[it * 4 + 2] shl 16) or (INITIAL_SESSION_KEY[it * 4 + 3] shl 24)
        }
        var firstFinal = IntArray(4)

        var blockOff = 0
        while (blockOff < 320) {
            val block = ByteArray(64) { padded[blockOff + it].toByte() }
            val add = sapHash(block)
            for (i in 0 until 4) {
                state[i] += (add[i * 4].toInt() and 0xFF) or
                            ((add[i * 4 + 1].toInt() and 0xFF) shl 8) or
                            ((add[i * 4 + 2].toInt() and 0xFF) shl 16) or
                            ((add[i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            md5Compress(state, padded, blockOff)
            if (blockOff == 320 - 64) {
                firstFinal = state.copyOf()
                md5Compress(state, padded, blockOff)
            }
            blockOff += 64
        }

        val out = ByteArray(20)
        out[0] = (firstFinal[0] ushr 24).toByte()
        out[1] = (firstFinal[0] ushr 16).toByte()
        out[2] = (firstFinal[0] ushr 8).toByte()
        out[3] = firstFinal[0].toByte()
        for (i in 0 until 4) {
            out[4 + i * 4] = (state[i] ushr 24).toByte()
            out[4 + i * 4 + 1] = (state[i] ushr 16).toByte()
            out[4 + i * 4 + 2] = (state[i] ushr 8).toByte()
            out[4 + i * 4 + 3] = state[i].toByte()
        }
        return out
    }

    /**
     * The 20 payload-dependent bytes Phase 2 consumes, for a per-session SAP.
     * `gp` is Phase 1's 128-byte output buffer.
     */
    fun bridgeX9HeadForSap(localSap: ByteArray, gp: ByteArray): ByteArray {
        require(gp.size == 128) { "gp must be 128 bytes" }
        val body = ByteArray(128) { ((gp[it].toInt() and 0xFF) xor GP_OUTPUT_MASK).toByte() }
        val d = descriptorForSap(localSap, body)
        // The descriptor emits big-endian words; x9Data is little-endian.
        val out = ByteArray(20)
        for (w in 0 until 5) for (b in 0 until 4) out[w * 4 + b] = d[w * 4 + 3 - b]
        return out
    }
}
