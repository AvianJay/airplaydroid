/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * GENERATED FILE -- do not edit by hand.
 * Regenerate with _research/gen_phase2_tables.py.
 *
 * FairPlay SAP Phase 2 constant tables for AirPlayDroid.
 *
 * Phase 2 is the analytical white-box MD5 / WB-AES pipeline that turns the
 * 20-byte bridge digest (`x9`) into the 20-byte m3 response. Every table here is
 * transcribed from the upstream Go implementation
 * (github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake,
 * packages `fairplayhash` and `fpbridge`), vendored under
 * `_research/src/objevovat_phase2/`.
 *
 * The `...Bases` / `...Spec` / `...Patch` blobs are Go interpreted strings full
 * of `\xNN` escapes. They are decoded by the generator rather than transcribed,
 * because a mis-decoded escape still produces 256 plausible-looking bytes -- it
 * only stops being a bijection. The `init` block below re-checks that property
 * at runtime for all 176 unpacked substitution tables.
 *
 * The literals are Apple-derived data (LGPL-3.0-or-later upstream). See NOTICE.
 */
package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * The four 128-bit NEON vector registers Phase 2 consumes.
 *
 * Only [vreg0] is payload-dependent: it is `x9[0:16]` put through the NEON
 * prologue transform with [vreg1] (XOR mask), [vreg3] (AND mask) and [vreg2]
 * (ADD bias) as its constants. Round 0 uses these; rounds 1+ reload the same
 * values from immediates (see [Phase2Tables.bridgeVreg1] and friends).
 *
 * Mirrors `fairplayhash.NeonState` / `fpbridge.bridgeNeonState`.
 */
internal class NeonState(
    val vreg0: LongArray,
    val vreg1: LongArray,
    val vreg2: LongArray,
    val vreg3: LongArray,
) {
    init {
        require(vreg0.size == 2 && vreg1.size == 2 && vreg2.size == 2 && vreg3.size == 2) {
            "NeonState lanes are 2 x uint64 each"
        }
    }
}

/**
 * SP-relative Phase-2 scratch memory. Mirrors `fairplayhash.HashState`.
 *
 * [mem] is indexed by SP-relative byte offsets, so the message areas live at
 * [Phase2Tables.RoundMsgAreaOffset] and the 20-byte digest lands at
 * [Phase2Tables.Span7Offset].
 */
internal class HashState(val mem: ByteArray)

/**
 * Every constant and table FairPlay SAP Phase 2 needs.
 *
 * Section order follows the Go files it was ported from, so a reader can diff
 * this against `_research/src/objevovat_phase2/` directly.
 */
internal object Phase2Tables {

    /** How many 256-entry substitution tables [unpackTable] produces. */
    const val UNPACKED_TABLE_COUNT = 176

    /**
     * Round-31-boundary message permutation family. Mirrors
     * `fairplayhash.BridgeMutation` (m3hash.go).
     */
    enum class BridgeMutation { KDF, CYCLE }

    // =====================================================================
    // roundc.go -- white-box MD5 round constants
    // =====================================================================


    /** roundc.go `AddConsts`: the obfuscated T[i] values, one per MD5 sub-round. */
    val AddConsts: IntArray = intArrayOf(
        0x9695377e.toInt(), 0xa7f24a5c.toInt(), 0xe34b03e1.toInt(), 0x80e861f4.toInt(),
        0xb4a6a2b5.toInt(), 0x06b25930, 0x675ad919, 0xbc712807.toInt(),
        0x28ab2bde, 0x4a6f8ab5, 0xbf29eeb7.toInt(), 0x48876ac4,
        0x2abaa428, 0xbcc30499.toInt(), 0x65a3d694, 0x08de9b27,
        0xb548b868.toInt(), 0x86940647.toInt(), 0xe588ed57.toInt(), 0xa8e15ab0.toInt(),
        0x9559a363.toInt(), 0xc16ea759.toInt(), 0x97cc7987.toInt(), 0xa6fe8ece.toInt(),
        0xe10c60ec.toInt(), 0x82619adc.toInt(), 0xb3ffa08d.toInt(), 0x0484a7f3,
        0x690e7c0b, 0xbc1a36fe.toInt(), 0x269995df, 0x4c54df90,
        0xbf24cc48.toInt(), 0x469c8987, 0x2cc7f428, 0xbd0fcb12.toInt(),
        0x63e97d4a, 0x0b0962af, 0xb5e5de66.toInt(), 0x7dea4f76,
        0xe7c611cc.toInt(), 0xa9cbbb00.toInt(), 0x9419c38b.toInt(), 0xc3b2b00b.toInt(),
        0x98ff633f.toInt(), 0xa6062ceb.toInt(), 0xdecd0ffe.toInt(), 0x83d6e96b.toInt(),
        0xb353b54a.toInt(), 0x0255929d, 0x6abeb6ad, 0xbbbe333f.toInt(),
        0x2485ecc9, 0x4e375f98, 0xbf1a8783.toInt(), 0x44aef0d7,
        0x2ed31155, 0xbd5779e6.toInt(), 0x622bd61a, 0x0d32a4a7,
        0xb67e1188.toInt(), 0x7c65853b, 0xea0265c1.toInt(), 0xaab16697.toInt(),
    )

    /** roundc.go `ModConsts`: 2 * OutBiases, for the affine-bijection reduction. */
    val ModConsts: IntArray = intArrayOf(
        0x2b2f55ec, 0x95fdce9c.toInt(), 0x01453b68, 0x51e49cf4,
        0xd047fc7c.toInt(), 0x3caf4cd8, 0xbec41846.toInt(), 0xef33bdda.toInt(),
        0xa760a0f2.toInt(), 0x319c88b0, 0xe3ea1d34.toInt(), 0x3d2fc57a,
        0x996a78e4.toInt(), 0xf1ae7ffe.toInt(), 0x81b36544.toInt(), 0xf3f4d4f0.toInt(),
        0x7457e6cc, 0x90766eda.toInt(), 0xd5107492.toInt(), 0x153cee18,
        0xe85381d0.toInt(), 0x1a1c97ca, 0xa9be99dc.toInt(), 0x4daa4ac0,
        0x2b182376, 0x0a0f5b02, 0x54de4fac, 0xcf52d71a.toInt(),
        0xc0e0fa26.toInt(), 0xb4dd068c.toInt(), 0x59be1bd6, 0xc0840c1a.toInt(),
        0xf638c318.toInt(), 0x60762ff4, 0x5b34f6d4, 0xcc972894.toInt(),
        0x17b99e7c, 0x2010cc86, 0x335de7f0, 0x5df74f20,
        0x0c8e30ec, 0xaa0c8aca.toInt(), 0xdc7bb2e0.toInt(), 0x468975da,
        0x2d91c95e, 0x19b68850, 0x3fb5b43e, 0x1c14ee5a,
        0x0681f15e, 0x09de60fa, 0xe68baa96.toInt(), 0xad147952.toInt(),
        0xc80f6ad8.toInt(), 0xb746d080.toInt(), 0xefbf0f80.toInt(), 0x7f727bb8,
        0x22d0d44a, 0x6d59388c, 0x44075568, 0x3be0faf6,
        0xe4c49252.toInt(), 0xff6971d6.toInt(), 0x8d9db6d6.toInt(), 0x00000000,
    )

    /** roundc.go `OutBiases`: affine-bijection encoding bias per sub-round. */
    val OutBiases: IntArray = intArrayOf(
        0x1597aaf6, 0x4afee74e, 0x00a29db4, 0x28f24e7a,
        0x6823fe3e, 0x1e57a66c, 0x5f620c23, 0x7799deed,
        0x53b05079, 0x18ce4458, 0x71f50e9a, 0x1e97e2bd,
        0x4cb53c72, 0xf8d73fff.toInt(), 0x40d9b2a2, 0x79fa6a78,
        0x3a2bf366, 0x483b376d, 0x6a883a49, 0x0a9e770c,
        0x7429c0e8, 0x0d0e4be5, 0x54df4cee, 0x26d52560,
        0x158c11bb, 0x0507ad81, 0x2a6f27d6, 0x67a96b8d,
        0x60707d13, 0x5a6e8346, 0x2cdf0deb, 0x6042060d,
        0x7b1c618c, 0x303b17fa, 0x2d9a7b6a, 0x664b944a,
        0x0bdccf3e, 0x10086643, 0x19aef3f8, 0x2efba790,
        0x06471876, 0x55064565, 0x6e3dd970, 0x2344baed,
        0x16c8e4af, 0x0cdb4428, 0x1fdada1f, 0x0e0a772d,
        0x0340f8af, 0x04ef307d, 0x7345d54b, 0x568a3ca9,
        0x6407b56c, 0x5ba36840, 0x77df87c0, 0x3fb93ddc,
        0x11686a25, 0x36ac9c46, 0x2203aab4, 0x1df07d7b,
        0x72624929, 0x7fb4b8eb, 0x46cedb6b, 0x00000000,
    )

    /** roundc.go `RorAmounts`: MD5 rotation amounts, already right-rotate (32 - left). */
    val RorAmounts: IntArray = intArrayOf(
        25, 20, 15, 10, 25, 20, 15, 10, 25, 20, 15, 10, 25, 20, 15, 10,
        27, 23, 18, 12, 27, 23, 18, 12, 27, 23, 18, 12, 27, 23, 18, 12,
        28, 21, 16, 9, 28, 21, 16, 9, 28, 21, 16, 9, 28, 21, 16, 9,
        26, 22, 17, 11, 26, 22, 17, 11, 26, 22, 17, 11, 26, 22, 17, 11,
    )

    /** roundc.go `MsgSchedule`: the standard MD5 message-word schedule. */
    val MsgSchedule: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        1, 6, 11, 0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12,
        5, 8, 11, 14, 1, 4, 7, 10, 13, 0, 3, 6, 9, 12, 15, 2,
        0, 7, 14, 5, 12, 3, 10, 1, 8, 15, 6, 13, 4, 11, 2, 9,
    )

    /** roundc_plain.go `plainAddConsts`: AddConsts with the sub-round-17 OutBiases anomaly (OutBiases[13]) folded in, so the encoding-free MD5 loop is branch-free. */
    val plainAddConsts: IntArray = intArrayOf(
        0x9695377e.toInt(), 0xa7f24a5c.toInt(), 0xe34b03e1.toInt(), 0x80e861f4.toInt(),
        0xb4a6a2b5.toInt(), 0x06b25930, 0x675ad919, 0xbc712807.toInt(),
        0x28ab2bde, 0x4a6f8ab5, 0xbf29eeb7.toInt(), 0x48876ac4,
        0x2abaa428, 0xbcc30499.toInt(), 0x65a3d694, 0x08de9b27,
        0xb548b868.toInt(), 0x7f6b4646, 0xe588ed57.toInt(), 0xa8e15ab0.toInt(),
        0x9559a363.toInt(), 0xc16ea759.toInt(), 0x97cc7987.toInt(), 0xa6fe8ece.toInt(),
        0xe10c60ec.toInt(), 0x82619adc.toInt(), 0xb3ffa08d.toInt(), 0x0484a7f3,
        0x690e7c0b, 0xbc1a36fe.toInt(), 0x269995df, 0x4c54df90,
        0xbf24cc48.toInt(), 0x469c8987, 0x2cc7f428, 0xbd0fcb12.toInt(),
        0x63e97d4a, 0x0b0962af, 0xb5e5de66.toInt(), 0x7dea4f76,
        0xe7c611cc.toInt(), 0xa9cbbb00.toInt(), 0x9419c38b.toInt(), 0xc3b2b00b.toInt(),
        0x98ff633f.toInt(), 0xa6062ceb.toInt(), 0xdecd0ffe.toInt(), 0x83d6e96b.toInt(),
        0xb353b54a.toInt(), 0x0255929d, 0x6abeb6ad, 0xbbbe333f.toInt(),
        0x2485ecc9, 0x4e375f98, 0xbf1a8783.toInt(), 0x44aef0d7,
        0x2ed31155, 0xbd5779e6.toInt(), 0x622bd61a, 0x0d32a4a7,
        0xb67e1188.toInt(), 0x7c65853b, 0xea0265c1.toInt(), 0xaab16697.toInt(),
    )


    // =====================================================================
    // spn1_fullmix_const.go -- SPN#1 MixColumns and round-0 plaintext
    // =====================================================================


    /** spn1_fullmix_const.go `SPN1CoreInput`: SPN#1's payload-independent round-0 plaintext. */
    val SPN1CoreInput: ByteArray = bytes(
        0x0f, 0x54, 0x5e, 0x5a, 0xb7, 0x7e, 0x16, 0x80, 0x1a, 0xed, 0xd5, 0x81, 0xd0, 0x87, 0x26, 0xdc,
    )

    /** spn1_fullmix_const.go `SPN1MixColConst`: the additive constant of the GF(2)-affine MixColumns map. */
    val SPN1MixColConst: ByteArray = bytes(
        0x0c, 0x48, 0x79, 0x5f, 0x0c, 0x48, 0x79, 0x5f, 0x0c, 0x48, 0x79, 0x5f, 0x0c, 0x48, 0x79, 0x5f,
    )

    /**
     * spn1_fullmix_const.go `spn1MixBlock` -- the 32x32 GF(2) block of SPN#1's
     * full MixColumns. The 128x128 matrix is block diagonal with four identical
     * 32x32 blocks, so only one block (128 bytes) is carried.
     */
    private val spn1MixBlock: ByteArray = bytes(
        0x52, 0x53, 0x01, 0x01, 0x8a, 0x88, 0x02, 0x02, 0x2e, 0x2a, 0x04, 0x04, 0xed, 0xe5, 0x08, 0x08,
        0x8e, 0x9e, 0x10, 0x10, 0xa6, 0x86, 0x20, 0x20, 0x96, 0xd6, 0x40, 0x40, 0x77, 0xf7, 0x80, 0x80,
        0x01, 0x52, 0x53, 0x01, 0x02, 0x8a, 0x88, 0x02, 0x04, 0x2e, 0x2a, 0x04, 0x08, 0xed, 0xe5, 0x08,
        0x10, 0x8e, 0x9e, 0x10, 0x20, 0xa6, 0x86, 0x20, 0x40, 0x96, 0xd6, 0x40, 0x80, 0x77, 0xf7, 0x80,
        0x01, 0x01, 0x52, 0x53, 0x02, 0x02, 0x8a, 0x88, 0x04, 0x04, 0x2e, 0x2a, 0x08, 0x08, 0xed, 0xe5,
        0x10, 0x10, 0x8e, 0x9e, 0x20, 0x20, 0xa6, 0x86, 0x40, 0x40, 0x96, 0xd6, 0x80, 0x80, 0x77, 0xf7,
        0x53, 0x01, 0x01, 0x52, 0x88, 0x02, 0x02, 0x8a, 0x2a, 0x04, 0x04, 0x2e, 0xe5, 0x08, 0x08, 0xed,
        0x9e, 0x10, 0x10, 0x8e, 0x86, 0x20, 0x20, 0xa6, 0xd6, 0x40, 0x40, 0x96, 0xf7, 0x80, 0x80, 0x77,
    )


    /**
     * spn1_fullmix_const.go `buildMixColRows`: the full 128x128 MixColumns matrix
     * rebuilt from [spn1MixBlock] by broadcasting each 32-bit block into its
     * diagonal column group. `SPN1MixColRows[r][k]` is row r's byte k.
     */
    val SPN1MixColRows: Array<ByteArray> = Array(128) { r ->
        val col = r / 32
        ByteArray(16).also { row ->
            for (k in 0 until 4) row[col * 4 + k] = spn1MixBlock[(r % 32) * 4 + k]
        }
    }

    /** spn1_mixcol_fast.go `spn1MixConst`: [SPN1MixColConst] as two little-endian u64. */
    val spn1MixConst: LongArray = LongArray(2) { i ->
        var acc = 0L
        for (j in 0 until 8) acc = acc or ((SPN1MixColConst[i * 8 + j].toLong() and 0xFF) shl (8 * j))
        acc
    }

    /**
     * spn1_mixcol_fast.go `spn1MixNib`: the nibble-wise form of MixColumns.
     * `spn1MixNib[2*i+hi][nibble]` is the 128-bit column contribution of byte i's
     * high nibble; XORing all 32 lookups plus [spn1MixConst] gives the map.
     */
    val spn1MixNib: Array<Array<LongArray>> = run {
        val cols = Array(128) { LongArray(2) }
        for (ob in 0 until 128) {
            val row = SPN1MixColRows[ob]
            for (ib in 0 until 128) {
                if ((((row[ib ushr 3].toInt() and 0xFF) ushr (ib and 7)) and 1) != 0) {
                    cols[ib][ob ushr 6] = cols[ib][ob ushr 6] or (1L shl (ob and 63))
                }
            }
        }
        Array(32) { n ->
            Array(16) { v ->
                val acc = LongArray(2)
                for (k in 0 until 4) {
                    if (((v ushr k) and 1) != 0) {
                        acc[0] = acc[0] xor cols[n * 4 + k][0]
                        acc[1] = acc[1] xor cols[n * 4 + k][1]
                    }
                }
                acc
            }
        }
    }

    /** spn1.go `spn1ShiftRows` / spn1_trailing.go `spn1TrailShiftRows`: AES ShiftRows. */

    val spn1ShiftRows: IntArray = intArrayOf(
        0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11,
    )

    val spn1TrailShiftRows: IntArray = intArrayOf(
        0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11,
    )


    // =====================================================================
    // spn1_coretables_const.go -- SPN#1 core TypeI substitution tables
    // =====================================================================

    /** spn1_coretables_const.go `SPN1CoreTablesBases` (2 x 256-byte base tables). */
    private val SPN1CoreTablesBases: IntArray = intArrayOf(

        73, 29, 118, 21, 242, 58, 182, 172, 52, 160, 105, 127, 129, 215, 136, 187,
        113, 110, 40, 22, 190, 112, 56, 186, 152, 1, 166, 91, 146, 255, 32, 142,
        208, 176, 14, 201, 47, 222, 61, 111, 159, 83, 183, 252, 200, 123, 130, 100,
        254, 218, 92, 11, 243, 134, 178, 66, 54, 214, 245, 248, 233, 42, 239, 131,
        203, 154, 141, 18, 221, 231, 101, 88, 135, 89, 0, 202, 253, 139, 240, 132,
        79, 177, 189, 161, 165, 133, 209, 157, 84, 115, 85, 241, 55, 195, 210, 6,
        86, 38, 216, 80, 51, 247, 155, 164, 20, 117, 205, 59, 169, 5, 103, 107,
        230, 98, 95, 102, 191, 3, 207, 193, 93, 74, 192, 170, 69, 114, 158, 138,
        34, 148, 143, 180, 137, 9, 72, 23, 64, 2, 250, 96, 238, 171, 184, 227,
        225, 62, 194, 196, 99, 228, 116, 140, 70, 185, 15, 217, 244, 28, 68, 71,
        235, 145, 65, 251, 24, 45, 163, 168, 122, 144, 63, 77, 173, 67, 106, 147,
        126, 17, 109, 226, 153, 50, 213, 179, 30, 219, 49, 232, 94, 236, 57, 150,
        81, 181, 175, 151, 97, 167, 198, 19, 104, 44, 246, 39, 229, 90, 119, 206,
        10, 35, 60, 48, 156, 25, 13, 46, 8, 36, 249, 16, 78, 124, 212, 7,
        199, 53, 211, 128, 149, 237, 188, 174, 41, 224, 220, 162, 108, 43, 33, 121,
        76, 197, 37, 82, 204, 87, 27, 75, 12, 31, 26, 4, 234, 125, 223, 120,
        87, 107, 45, 131, 100, 50, 138, 237, 254, 63, 86, 26, 220, 230, 49, 208,
        252, 17, 170, 53, 197, 59, 145, 248, 33, 191, 57, 47, 243, 106, 51, 223,
        163, 233, 27, 169, 69, 188, 200, 213, 80, 31, 11, 144, 219, 201, 224, 102,
        93, 211, 159, 109, 65, 54, 37, 20, 236, 111, 43, 193, 164, 125, 66, 67,
        14, 30, 95, 41, 12, 216, 122, 92, 174, 114, 179, 123, 42, 168, 5, 75,
        234, 155, 173, 178, 61, 94, 207, 108, 214, 251, 117, 82, 128, 74, 226, 83,
        98, 162, 176, 232, 77, 218, 4, 38, 153, 130, 149, 158, 103, 204, 239, 24,
        147, 134, 120, 247, 3, 121, 222, 150, 205, 76, 245, 235, 126, 183, 199, 192,
        142, 9, 132, 25, 167, 6, 228, 187, 249, 203, 250, 113, 73, 15, 79, 246,
        181, 241, 62, 127, 91, 148, 202, 35, 242, 68, 165, 119, 212, 221, 184, 190,
        253, 13, 146, 72, 116, 129, 198, 55, 2, 22, 36, 18, 161, 44, 21, 185,
        194, 52, 175, 84, 135, 60, 152, 143, 157, 90, 172, 28, 171, 186, 124, 88,
        104, 189, 70, 118, 40, 64, 141, 8, 215, 140, 195, 19, 46, 139, 23, 255,
        56, 238, 101, 78, 229, 209, 244, 182, 48, 89, 180, 1, 160, 196, 115, 151,
        217, 29, 39, 177, 154, 16, 110, 96, 210, 97, 136, 99, 137, 32, 156, 240,
        231, 7, 112, 227, 71, 58, 206, 133, 34, 166, 10, 0, 85, 81, 105, 225,

    )

    /** spn1_coretables_const.go `SPN1CoreTablesSpec`: (base, inXor, outXor) per table. */
    private val SPN1CoreTablesSpec: IntArray = intArrayOf(

        0, 0, 0, 0, 13, 219, 0, 126, 147, 0, 246, 178,
        0, 176, 0, 0, 253, 219, 0, 25, 147, 0, 102, 178,
        0, 210, 0, 0, 158, 219, 0, 46, 147, 0, 248, 178,
        0, 91, 0, 0, 222, 219, 0, 36, 147, 0, 161, 178,
        1, 0, 0, 1, 115, 219, 1, 54, 147, 1, 255, 178,
        1, 164, 0, 1, 150, 219, 1, 53, 147, 1, 87, 178,
        1, 201, 0, 1, 215, 219, 1, 70, 147, 1, 203, 178,
        1, 128, 0, 1, 99, 219, 1, 133, 147, 1, 5, 178,
        1, 97, 0, 1, 162, 219, 1, 181, 147, 1, 125, 178,
        1, 44, 0, 1, 134, 219, 1, 248, 147, 1, 247, 178,
        1, 12, 0, 1, 227, 219, 1, 6, 147, 1, 135, 178,
        1, 101, 0, 1, 99, 219, 1, 251, 147, 1, 57, 178,
        1, 42, 0, 1, 49, 219, 1, 239, 147, 1, 38, 178,
        1, 239, 0, 1, 5, 219, 1, 111, 147, 1, 94, 178,
        1, 10, 0, 1, 84, 219, 1, 161, 147, 1, 98, 178,
        1, 134, 0, 1, 33, 219, 1, 34, 147, 1, 224, 178,
        1, 193, 0, 1, 201, 219, 1, 32, 147, 1, 90, 178,
        1, 199, 0, 1, 126, 219, 1, 55, 147, 1, 69, 178,
        1, 36, 0, 1, 152, 219, 1, 237, 147, 1, 156, 178,
        1, 75, 0, 1, 74, 219, 1, 183, 147, 1, 199, 178,
        1, 34, 0, 1, 134, 219, 1, 129, 147, 1, 158, 178,
        1, 12, 0, 1, 74, 219, 1, 206, 147, 1, 36, 178,
        1, 193, 0, 1, 96, 219, 1, 22, 147, 1, 3, 178,
        1, 99, 0, 1, 253, 219, 1, 217, 147, 1, 127, 178,
        1, 99, 0, 1, 178, 219, 1, 139, 147, 1, 68, 178,
        1, 134, 0, 1, 74, 219, 1, 61, 147, 1, 29, 178,
        1, 174, 0, 1, 152, 219, 1, 211, 147, 1, 165, 178,
        1, 36, 0, 1, 134, 219, 1, 114, 147, 1, 97, 178,
        1, 130, 0, 1, 207, 219, 1, 107, 147, 1, 137, 178,
        1, 237, 0, 1, 55, 219, 1, 46, 147, 1, 178, 178,
        1, 170, 0, 1, 29, 219, 1, 146, 147, 1, 172, 178,
        1, 103, 0, 1, 207, 219, 1, 152, 147, 1, 118, 178,
        1, 244, 0, 1, 105, 219, 1, 125, 147, 1, 1, 178,
        1, 240, 0, 1, 236, 219, 1, 43, 147, 1, 233, 178,
        1, 179, 0, 1, 67, 219, 1, 142, 147, 1, 254, 178,
        1, 61, 0, 1, 20, 219, 1, 110, 147, 1, 51, 178,

    )

    /**
     * SPN#1's 9 core-round TypeI substitution tables: `[round][outPos][input]`.
     * Unpacked at object init via [unpackTable].
     */
    val SPN1CoreTables: Array<Array<ByteArray>> = Array(9) { i ->
        Array(16) { j -> unpackTable(SPN1CoreTablesBases, SPN1CoreTablesSpec, (i * 16 + j) * 3) }
    }

    // =====================================================================
    // spn1_trailing_const.go -- SPN#1 trailing final-round S-boxes
    // =====================================================================

    /** spn1_trailing_const.go `SPN1TrailTablesBases` (1 x 256-byte base table). */
    private val SPN1TrailTablesBases: IntArray = intArrayOf(

        250, 249, 85, 113, 136, 68, 202, 247, 56, 152, 1, 183, 88, 87, 153, 72,
        157, 143, 38, 233, 131, 188, 67, 221, 30, 14, 84, 54, 66, 211, 235, 155,
        53, 236, 5, 60, 47, 255, 178, 108, 251, 99, 95, 229, 118, 142, 41, 213,
        184, 203, 101, 43, 20, 31, 24, 216, 224, 29, 97, 201, 122, 57, 156, 74,
        128, 219, 91, 180, 197, 242, 18, 80, 45, 77, 65, 207, 23, 44, 40, 42,
        39, 48, 36, 110, 34, 194, 10, 175, 62, 164, 252, 64, 132, 127, 114, 82,
        200, 92, 254, 125, 3, 245, 168, 58, 192, 52, 199, 223, 104, 160, 123, 220,
        86, 141, 161, 94, 238, 15, 103, 140, 21, 100, 50, 89, 165, 241, 145, 234,
        167, 232, 253, 130, 222, 96, 120, 159, 147, 8, 112, 150, 109, 12, 61, 144,
        137, 81, 59, 228, 135, 121, 51, 93, 215, 248, 176, 106, 35, 182, 16, 32,
        230, 76, 244, 181, 243, 126, 193, 98, 70, 237, 198, 63, 55, 212, 214, 208,
        206, 73, 90, 13, 187, 231, 2, 107, 7, 179, 196, 154, 185, 158, 117, 174,
        190, 148, 83, 163, 49, 71, 166, 22, 27, 189, 111, 0, 173, 170, 119, 115,
        28, 210, 177, 217, 240, 79, 116, 78, 239, 102, 209, 149, 226, 133, 191, 146,
        75, 151, 169, 138, 162, 19, 124, 134, 25, 33, 246, 171, 227, 6, 17, 204,
        186, 139, 205, 37, 172, 225, 9, 4, 11, 129, 69, 26, 105, 218, 46, 195,

    )

    /** spn1_trailing_const.go `SPN1TrailTablesSpec`. */
    private val SPN1TrailTablesSpec: IntArray = intArrayOf(

        0, 0, 0, 0, 77, 147, 0, 222, 2, 0, 177, 138,
        0, 25, 36, 0, 19, 98, 0, 141, 229, 0, 198, 86,
        0, 67, 156, 0, 226, 213, 0, 205, 66, 0, 131, 165,
        0, 151, 29, 0, 150, 72, 0, 219, 38, 0, 11, 164,

    )

    /**
     * SPN#1's 16 byte->byte output S-boxes of the trailing final round:
     * `out[p] = SPN1TrailTables[p][in[spn1TrailShiftRows[p]]]`.
     *
     * Deliberately `Array<ByteArray>` (16 x 256), matching the Go declaration
     * `var SPN1TrailTables [16][256]byte` -- indexing is `[p][input]`.
     */
    val SPN1TrailTables: Array<ByteArray> = Array(16) { p ->
        unpackTable(SPN1TrailTablesBases, SPN1TrailTablesSpec, p * 3)
    }

    // =====================================================================
    // ground_g_const.go -- TailSPN's final-round S-boxes
    // =====================================================================

    /** ground_g_const.go `gPerm`: which BE(roundOutputs[8]) byte feeds output position p. */

    val gPerm: IntArray = intArrayOf(
        0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3,
    )


    /** ground_g_const.go `gSboxBases` (1 x 256-byte base table). */
    private val gSboxBases: IntArray = intArrayOf(

        250, 59, 135, 85, 198, 19, 220, 137, 184, 199, 103, 201, 188, 130, 40, 68,
        175, 223, 119, 212, 5, 73, 246, 125, 7, 217, 202, 249, 225, 8, 41, 4,
        174, 216, 101, 173, 99, 194, 35, 97, 127, 31, 126, 2, 124, 121, 207, 21,
        96, 245, 75, 167, 88, 17, 42, 157, 57, 12, 86, 163, 18, 191, 105, 154,
        106, 123, 45, 39, 52, 203, 153, 244, 62, 128, 14, 209, 144, 120, 230, 228,
        118, 160, 110, 243, 43, 51, 65, 60, 61, 74, 131, 115, 80, 166, 66, 27,
        180, 11, 150, 24, 72, 3, 232, 71, 93, 205, 170, 134, 22, 189, 98, 251,
        187, 50, 111, 254, 231, 143, 29, 255, 183, 164, 13, 95, 215, 82, 148, 108,
        113, 200, 178, 37, 109, 236, 214, 165, 53, 161, 210, 192, 70, 64, 28, 32,
        190, 79, 238, 185, 240, 234, 186, 208, 56, 63, 138, 46, 15, 33, 140, 182,
        92, 67, 213, 242, 104, 77, 247, 177, 87, 211, 252, 218, 197, 253, 142, 102,
        171, 227, 23, 136, 114, 146, 172, 58, 1, 141, 193, 133, 36, 248, 241, 239,
        89, 151, 100, 206, 139, 117, 155, 91, 81, 10, 55, 0, 222, 195, 129, 122,
        158, 235, 224, 44, 156, 30, 159, 169, 6, 226, 204, 112, 94, 38, 181, 90,
        9, 196, 237, 221, 162, 26, 145, 132, 176, 34, 78, 47, 16, 152, 69, 233,
        229, 76, 116, 149, 147, 84, 219, 54, 168, 48, 49, 25, 107, 179, 83, 20,

    )

    /** ground_g_const.go `gSboxSpec`. */
    private val gSboxSpec: IntArray = intArrayOf(

        0, 0, 0, 0, 72, 205, 0, 66, 92, 0, 86, 148,
        0, 36, 25, 0, 147, 22, 0, 38, 74, 0, 165, 209,
        0, 156, 67, 0, 98, 72, 0, 2, 79, 0, 164, 89,
        0, 29, 151, 0, 213, 185, 0, 229, 28, 0, 138, 227,

    )

    /** ground_g_const.go `gSbox`: the 16 final-round S-boxes of TailInput. */
    val gSbox: Array<ByteArray> = Array(16) { p -> unpackTable(gSboxBases, gSboxSpec, p * 3) }

    // =====================================================================
    // tail_spn.go / tail_spn_fast.go -- the 9-round tail WB-AES SPN
    // =====================================================================


    /** tail_spn.go `tailSPNTypeI`: TypeI table index per round. Position P uses `tailSPNTypeI[round][15 - P]`. */
    val tailSPNTypeI: Array<IntArray> = arrayOf(
        intArrayOf(
            47, 94, 141, 44, 91, 138, 41, 88, 135, 38, 85, 132, 35, 82, 129, 32,
        ),
        intArrayOf(
            79, 126, 29, 76, 123, 26, 73, 120, 23, 70, 117, 20, 67, 114, 17, 64,
        ),
        intArrayOf(
            111, 14, 61, 108, 11, 58, 105, 8, 55, 102, 5, 52, 99, 2, 49, 96,
        ),
        intArrayOf(
            143, 46, 93, 140, 43, 90, 137, 40, 87, 134, 37, 84, 131, 34, 81, 128,
        ),
        intArrayOf(
            31, 78, 125, 28, 75, 122, 25, 72, 119, 22, 69, 116, 19, 66, 113, 16,
        ),
        intArrayOf(
            63, 110, 13, 60, 107, 10, 57, 104, 7, 54, 101, 4, 51, 98, 1, 48,
        ),
        intArrayOf(
            95, 142, 45, 92, 139, 42, 89, 136, 39, 86, 133, 36, 83, 130, 33, 80,
        ),
        intArrayOf(
            127, 30, 77, 124, 27, 74, 121, 24, 71, 118, 21, 68, 115, 18, 65, 112,
        ),
        intArrayOf(
            15, 62, 109, 12, 59, 106, 9, 56, 103, 6, 53, 100, 3, 50, 97, 0,
        ),
    )

    /** tail_spn.go `tailSPNInvShiftRows`: AES InvShiftRows for a column-major 4x4 matrix. */
    val tailSPNInvShiftRows: IntArray = intArrayOf(
        0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3,
    )

    /** tail_spn.go `tailSPNXORMask`: constant mask applied after TypeI output encoding. */
    val tailSPNXORMask: ByteArray = bytes(
        0x67, 0xbc, 0x54, 0xc0, 0x8e, 0x32, 0x85, 0x1b, 0x50, 0xd2, 0x12, 0x5f, 0x68, 0xb7, 0x40, 0xa5,
    )


    /**
     * tail_spn_fast.go `tailSPNFinalI`: the final output encoding and the
     * [tailSPNXORMask] folded into one table, so TailSPN's last two loops become
     * one. Built from the shared Phase-1 Type-I tables (`wbaesTypeI[144+p]`), so
     * it carries no data of its own.
     */
    val tailSPNFinalI: Array<ByteArray> by lazy {
        Array(16) { p ->
            val mask = tailSPNXORMask[p].toInt() and 0xFF
            ByteArray(256) { v -> (FairPlayWhiteBoxTables.typeI[144 + p][v] xor mask).toByte() }
        }
    }

    // =====================================================================
    // prologue.go -- NEON prologue constants and the Group 1->2 shuffle
    // =====================================================================

    /** prologue.go `NeonXORConst`: the constant XOR mask in vreg[1] (rounds 1+). */
    val NeonXORConst: Int = 0x7efd6cfa.toInt()

    /** prologue.go `NeonANDConst`: the constant AND mask in vreg[3] after SHL (rounds 1+). */
    val NeonANDConst: Int = 0xfdfad9f4.toInt()

    /** prologue.go `NeonADDConst`: the final ADD bias in vreg[2] (rounds 1+). */
    val NeonADDConst: Int = 0xc1d80000.toInt()


    /** prologue.go `g2ShuffleXORConsts`: the 8 XOR constants of the state-dependent Fisher-Yates shuffle at the Group 1->2 boundary. */
    val g2ShuffleXORConsts: IntArray = intArrayOf(
        0x00000003, 0x0000000d, 0x0000000b, 0x00000006, 0x00000001, 0x00000000, 0x0000000e, 0x00000004,
    )

    /** prologue.go `g2PermTable`. DEPRECATED upstream: a fixed permutation that is wrong for general inputs. Kept for reference/test compatibility only. */
    val g2PermTable: Array<IntArray> = arrayOf(
        intArrayOf(
            3, 10, 11, 5, 7, 14, 2, 12, 8, 9, 4, 1, 13, 6, 0, 15,
        ),
        intArrayOf(
            0, 10, 11, 5, 6, 3, 8, 4, 7, 9, 1, 12, 13, 2, 14, 15,
        ),
        intArrayOf(
            4, 3, 10, 2, 7, 6, 11, 14, 8, 9, 12, 13, 1, 15, 0, 5,
        ),
        intArrayOf(
            0, 3, 8, 1, 4, 7, 10, 11, 2, 9, 12, 13, 15, 5, 14, 6,
        ),
        intArrayOf(
            10, 1, 6, 8, 11, 5, 2, 12, 3, 9, 13, 7, 0, 15, 14, 4,
        ),
        intArrayOf(
            14, 4, 5, 10, 3, 9, 7, 1, 8, 2, 6, 11, 12, 13, 0, 15,
        ),
        intArrayOf(
            6, 4, 9, 2, 7, 3, 10, 11, 8, 5, 12, 13, 1, 0, 14, 15,
        ),
        intArrayOf(
            2, 10, 11, 12, 3, 4, 7, 0, 8, 9, 5, 1, 13, 6, 14, 15,
        ),
        intArrayOf(
            1, 7, 2, 3, 4, 5, 0, 6, 8, 9, 10, 11, 12, 13, 14, 15,
        ),
        intArrayOf(
            10, 3, 14, 5, 7, 9, 8, 4, 6, 1, 11, 0, 12, 13, 2, 15,
        ),
        intArrayOf(
            2, 0, 6, 1, 14, 9, 3, 10, 8, 7, 11, 5, 12, 13, 4, 15,
        ),
    )

    /** prologue.go `roundToPermIdx`: round number -> g2PermTable row (-1 = skip). DEPRECATED, see g2PermTable. */
    val roundToPermIdx: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7,
        8, -1, 9, 0, 1, 2, 3, 4,
        5, 6, 7, 10,
    )


    // =====================================================================
    // hiddeng2.go -- the hardcoded R8/R19 hidden-word sets
    // =====================================================================

    /** hiddeng2.go `HiddenWordsG0Base`: row 0 of the G0-1 hidden words, little-endian. */
    private val HiddenWordsG0Base: IntArray = intArrayOf(

        137, 2, 14, 191, 45, 65, 36, 63, 248, 134, 14, 181, 112, 208, 134, 191,
        152, 88, 122, 240, 232, 69, 45, 66, 201, 104, 180, 12, 156, 148, 241, 153,
        131, 29, 148, 16, 250, 108, 213, 192, 250, 108, 213, 64, 250, 108, 213, 64,
        250, 108, 213, 64, 250, 108, 213, 64, 250, 108, 216, 96, 250, 108, 213, 64,

    )

    /** hiddeng2.go `HiddenWordsG0Patch`: per-row (index, LE32) patches onto row 0. */
    private val HiddenWordsG0Patch: IntArray = intArrayOf(

        1, 5, 232, 69, 45, 67, 1, 5, 232, 69, 45, 68, 1, 5, 232, 69,
        45, 69, 1, 5, 232, 69, 45, 70, 1, 5, 232, 69, 45, 71, 1, 5,
        232, 69, 45, 72, 1, 5, 232, 69, 45, 73, 11, 0, 243, 36, 77, 214,
        1, 183, 83, 224, 166, 2, 139, 17, 117, 23, 3, 124, 10, 43, 104, 4,
        154, 152, 151, 240, 5, 245, 105, 197, 138, 6, 88, 25, 61, 63, 7, 197,
        104, 204, 255, 8, 250, 108, 213, 192, 9, 250, 108, 213, 64, 14, 250, 108,
        214, 64, 16, 0, 121, 46, 35, 95, 1, 240, 165, 223, 32, 2, 22, 255,
        167, 9, 3, 251, 243, 80, 4, 4, 154, 152, 151, 240, 5, 245, 105, 197,
        138, 6, 222, 173, 190, 239, 7, 202, 254, 186, 190, 8, 196, 163, 129, 218,
        9, 255, 255, 255, 255, 10, 60, 92, 126, 37, 11, 0, 0, 0, 0, 12,
        135, 196, 239, 6, 13, 0, 0, 0, 0, 14, 36, 0, 0, 0, 15, 0,
        0, 0, 0, 1, 5, 232, 69, 45, 65, 0, 1, 5, 232, 69, 45, 67,
        1, 5, 232, 69, 45, 68, 1, 5, 232, 69, 45, 69, 1, 5, 232, 69,
        45, 70, 1, 5, 232, 69, 45, 71, 1, 5, 232, 69, 45, 72, 1, 5,
        232, 69, 45, 73, 11, 0, 209, 255, 143, 44, 1, 141, 110, 110, 84, 2,
        246, 30, 197, 227, 3, 25, 104, 73, 110, 4, 95, 226, 55, 176, 5, 56,
        178, 232, 180, 6, 26, 178, 127, 70, 7, 197, 94, 136, 177, 8, 250, 108,
        213, 192, 9, 250, 108, 213, 64, 14, 250, 108, 214, 64,

    )

    /**
     * hiddeng2.go `HiddenWordsG0`: the 16 hidden words for groups 0-1 (sub-rounds
     * 0-31) of each of the 20 WB-MD5 rounds. Only rounds 8 and 19 are consumed;
     * every other round derives its hidden words from the NEON prologue.
     */
    val HiddenWordsG0: Array<IntArray> = unpackHiddenWords(HiddenWordsG0Base, HiddenWordsG0Patch)

    /** hiddeng2.go `HiddenWordsG2Base`: row 0 of the G2-3 hidden words, little-endian. */
    private val HiddenWordsG2Base: IntArray = intArrayOf(

        112, 208, 134, 191, 250, 108, 213, 64, 250, 108, 213, 64, 232, 69, 45, 66,
        156, 148, 241, 153, 250, 108, 216, 96, 248, 134, 14, 181, 250, 108, 213, 64,
        131, 29, 148, 16, 250, 108, 213, 192, 152, 88, 122, 240, 45, 65, 36, 63,
        250, 108, 213, 64, 201, 104, 180, 12, 137, 2, 14, 191, 250, 108, 213, 64,

    )

    /** hiddeng2.go `HiddenWordsG2Patch`: per-row (index, LE32) patches onto row 0. */
    private val HiddenWordsG2Patch: IntArray = intArrayOf(

        11, 0, 137, 2, 14, 191, 3, 232, 69, 45, 67, 4, 201, 104, 180, 12,
        5, 112, 208, 134, 191, 6, 131, 29, 148, 16, 7, 152, 88, 122, 240, 8,
        156, 148, 241, 153, 10, 45, 65, 36, 63, 11, 250, 108, 213, 64, 13, 248,
        134, 14, 181, 14, 250, 108, 216, 96, 11, 0, 152, 88, 122, 240, 1, 112,
        208, 134, 191, 3, 248, 134, 14, 181, 5, 201, 104, 180, 12, 6, 250, 108,
        213, 64, 7, 250, 108, 216, 96, 10, 250, 108, 213, 64, 11, 250, 108, 213,
        64, 12, 45, 65, 36, 63, 13, 250, 108, 213, 64, 15, 232, 69, 45, 68,
        13, 0, 137, 2, 14, 191, 1, 112, 208, 134, 191, 2, 131, 29, 148, 16,
        3, 45, 65, 36, 63, 4, 152, 88, 122, 240, 5, 156, 148, 241, 153, 6,
        250, 108, 213, 64, 8, 248, 134, 14, 181, 10, 250, 108, 213, 64, 11, 250,
        108, 213, 64, 13, 232, 69, 45, 69, 14, 250, 108, 216, 96, 15, 201, 104,
        180, 12, 13, 0, 250, 108, 213, 64, 1, 45, 65, 36, 63, 2, 201, 104,
        180, 12, 3, 131, 29, 148, 16, 4, 250, 108, 213, 64, 5, 232, 69, 45,
        70, 8, 112, 208, 134, 191, 10, 250, 108, 213, 64, 11, 156, 148, 241, 153,
        12, 137, 2, 14, 191, 13, 250, 108, 213, 64, 14, 250, 108, 216, 96, 15,
        152, 88, 122, 240, 12, 0, 250, 108, 216, 96, 1, 152, 88, 122, 240, 2,
        232, 69, 45, 71, 3, 250, 108, 213, 64, 4, 112, 208, 134, 191, 5, 250,
        108, 213, 192, 6, 156, 148, 241, 153, 7, 45, 65, 36, 63, 9, 248, 134,
        14, 181, 10, 201, 104, 180, 12, 11, 250, 108, 213, 64, 13, 250, 108, 213,
        64, 12, 0, 201, 104, 180, 12, 1, 152, 88, 122, 240, 2, 250, 108, 213,
        192, 3, 248, 134, 14, 181, 5, 112, 208, 134, 191, 6, 250, 108, 213, 64,
        9, 232, 69, 45, 72, 10, 250, 108, 213, 64, 11, 250, 108, 213, 64, 12,
        45, 65, 36, 63, 13, 137, 2, 14, 191, 14, 250, 108, 216, 96, 8, 0,
        248, 134, 14, 181, 3, 250, 108, 213, 64, 4, 112, 208, 134, 191, 5, 152,
        88, 122, 240, 6, 156, 148, 241, 153, 7, 137, 2, 14, 191, 10, 232, 69,
        45, 73, 14, 250, 108, 216, 96, 14, 0, 183, 83, 224, 166, 1, 197, 104,
        204, 255, 2, 139, 17, 117, 23, 3, 124, 10, 43, 104, 4, 154, 152, 151,
        240, 5, 245, 105, 197, 138, 6, 243, 36, 77, 214, 7, 88, 25, 61, 63,
        8, 250, 108, 213, 192, 9, 250, 108, 213, 64, 10, 250, 108, 213, 64, 11,
        250, 108, 213, 64, 13, 250, 108, 213, 64, 14, 250, 108, 214, 64, 16, 0,
        102, 132, 149, 43, 1, 73, 225, 95, 129, 2, 22, 255, 167, 9, 3, 251,
        243, 80, 4, 4, 154, 152, 151, 240, 5, 245, 105, 197, 138, 6, 222, 173,
        190, 239, 7, 202, 254, 186, 190, 8, 196, 163, 129, 218, 9, 255, 255, 255,
        255, 10, 60, 92, 126, 37, 11, 0, 0, 0, 0, 12, 135, 196, 239, 6,
        13, 0, 0, 0, 0, 14, 36, 0, 0, 0, 15, 0, 0, 0, 0, 13,
        0, 250, 108, 213, 64, 1, 112, 208, 134, 191, 2, 250, 108, 216, 96, 3,
        232, 69, 45, 65, 5, 250, 108, 213, 192, 6, 131, 29, 148, 16, 7, 152,
        88, 122, 240, 8, 201, 104, 180, 12, 9, 45, 65, 36, 63, 10, 250, 108,
        213, 64, 11, 137, 2, 14, 191, 13, 250, 108, 213, 64, 14, 248, 134, 14,
        181, 0, 11, 0, 137, 2, 14, 191, 3, 232, 69, 45, 67, 4, 201, 104,
        180, 12, 5, 112, 208, 134, 191, 6, 131, 29, 148, 16, 7, 152, 88, 122,
        240, 8, 156, 148, 241, 153, 10, 45, 65, 36, 63, 11, 250, 108, 213, 64,
        13, 248, 134, 14, 181, 14, 250, 108, 216, 96, 11, 0, 152, 88, 122, 240,
        1, 112, 208, 134, 191, 3, 248, 134, 14, 181, 5, 201, 104, 180, 12, 6,
        250, 108, 213, 64, 7, 250, 108, 216, 96, 10, 250, 108, 213, 64, 11, 250,
        108, 213, 64, 12, 45, 65, 36, 63, 13, 250, 108, 213, 64, 15, 232, 69,
        45, 68, 13, 0, 137, 2, 14, 191, 1, 112, 208, 134, 191, 2, 131, 29,
        148, 16, 3, 45, 65, 36, 63, 4, 152, 88, 122, 240, 5, 156, 148, 241,
        153, 6, 250, 108, 213, 64, 8, 248, 134, 14, 181, 10, 250, 108, 213, 64,
        11, 250, 108, 213, 64, 13, 232, 69, 45, 69, 14, 250, 108, 216, 96, 15,
        201, 104, 180, 12, 13, 0, 250, 108, 213, 64, 1, 45, 65, 36, 63, 2,
        201, 104, 180, 12, 3, 131, 29, 148, 16, 4, 250, 108, 213, 64, 5, 232,
        69, 45, 70, 8, 112, 208, 134, 191, 10, 250, 108, 213, 64, 11, 156, 148,
        241, 153, 12, 137, 2, 14, 191, 13, 250, 108, 213, 64, 14, 250, 108, 216,
        96, 15, 152, 88, 122, 240, 12, 0, 250, 108, 216, 96, 1, 152, 88, 122,
        240, 2, 232, 69, 45, 71, 3, 250, 108, 213, 64, 4, 112, 208, 134, 191,
        5, 250, 108, 213, 192, 6, 156, 148, 241, 153, 7, 45, 65, 36, 63, 9,
        248, 134, 14, 181, 10, 201, 104, 180, 12, 11, 250, 108, 213, 64, 13, 250,
        108, 213, 64, 12, 0, 201, 104, 180, 12, 1, 152, 88, 122, 240, 2, 250,
        108, 213, 192, 3, 248, 134, 14, 181, 5, 112, 208, 134, 191, 6, 250, 108,
        213, 64, 9, 232, 69, 45, 72, 10, 250, 108, 213, 64, 11, 250, 108, 213,
        64, 12, 45, 65, 36, 63, 13, 137, 2, 14, 191, 14, 250, 108, 216, 96,
        8, 0, 248, 134, 14, 181, 3, 250, 108, 213, 64, 4, 112, 208, 134, 191,
        5, 152, 88, 122, 240, 6, 156, 148, 241, 153, 7, 137, 2, 14, 191, 10,
        232, 69, 45, 73, 14, 250, 108, 216, 96, 13, 0, 246, 30, 197, 227, 1,
        209, 255, 143, 44, 2, 26, 178, 127, 70, 3, 141, 110, 110, 84, 4, 250,
        108, 214, 64, 5, 250, 108, 213, 64, 6, 25, 104, 73, 110, 8, 250, 108,
        213, 192, 9, 197, 94, 136, 177, 10, 250, 108, 213, 64, 11, 56, 178, 232,
        180, 13, 250, 108, 213, 64, 14, 95, 226, 55, 176,

    )

    /**
     * hiddeng2.go `HiddenWordsG2`: the 16 hidden words for groups 2-3 (sub-rounds
     * 32-63) of each of the 20 rounds, as they stand at the sub-round-32 boundary.
     */
    val HiddenWordsG2: Array<IntArray> = unpackHiddenWords(HiddenWordsG2Base, HiddenWordsG2Patch)

    // =====================================================================
    // bridge_native_consts_gen.go -- native bridge inputs
    // =====================================================================


    /** bridge_native_consts_gen.go `bridgeX9Tail`: the 44 constant bytes that follow the 20-byte bridge digest in x9Data. Phase 2 never reads them (they carry no payload information), but the prologue's block-3 input does, so they are carried for parity. */
    val bridgeX9Tail: ByteArray = bytes(
        0xee, 0xd8, 0x57, 0x00, 0xcf, 0xfb, 0xde, 0xcb, 0xa2, 0x27, 0x1c, 0x59, 0x89, 0xb0, 0xbe, 0xcf,
        0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x20, 0x00, 0x00, 0x00, 0x00,
    )

    /** bridge_native_consts_gen.go `bridgeVreg1`: the XOR mask, duplicated across both lanes. */
    val bridgeVreg1: LongArray = longArrayOf(
        0x7efd6cfa7efd6cfaL, 0x7efd6cfa7efd6cfaL,
    )

    /** bridge_native_consts_gen.go `bridgeVreg2`: the ADD bias, duplicated across both lanes. */
    val bridgeVreg2: LongArray = longArrayOf(
        0xc1d80000c1d80000UL.toLong(), 0xc1d80000c1d80000UL.toLong(),
    )

    /** bridge_native_consts_gen.go `bridgeVreg3`: the AND mask, duplicated across both lanes. */
    val bridgeVreg3: LongArray = longArrayOf(
        0xfdfad9f4fdfad9f4UL.toLong(), 0xfdfad9f4fdfad9f4UL.toLong(),
    )


    // =====================================================================
    // hash.go -- Phase-2 scratch layout and RoundB T-boxes
    // =====================================================================

    /** hash.go `HiddenWordsOffset`: SP+0, the 16 hidden words (64 bytes). */
    const val HiddenWordsOffset: Int = 0

    /** hash.go `RoundAShortOffset`: SP+3184, the short bswap target (4 words). */
    const val RoundAShortOffset: Int = 3184

    /** hash.go `RoundAShortCount`. */
    const val RoundAShortCount: Int = 4

    /** hash.go `RoundALongOffset`: SP+3208, the long bswap target (14 words). */
    const val RoundALongOffset: Int = 3208

    /** hash.go `RoundALongCount`. */
    const val RoundALongCount: Int = 14

    /** hash.go `RoundBTargetOffset`: SP+3056, the RoundB T-box target (15 words). */
    const val RoundBTargetOffset: Int = 3056

    /** finalize.go `Span7Offset`: SP+13584, where the 20-byte digest is written. */
    const val Span7Offset: Int = 13584

    /** compute.go `HashOutputSize`. */
    const val HashOutputSize: Int = 20

    /** roundc_plain.go `anomalySubRound`: the sub-round with the extra OutBias term. */
    const val anomalySubRound: Int = 17

    /** roundc_plain.go `anomalyEncRound`: `anomalySubRound - 4`. */
    const val anomalyEncRound: Int = 13

    /** roundc_plain.go `shuffleSubRound`: the Group 1->2 boundary. */
    const val shuffleSubRound: Int = 32


    /** hash.go `round8InitialState`: the constant initial MD5 state for rounds 8 and 19. */
    val round8InitialState: IntArray = intArrayOf(
        0xb9f3dcdc.toInt(), 0xfbdc740b.toInt(), 0x60f77f86, 0x51907216,
    )

    /** hash.go `RoundMsgAreaOffset`: round -> SP-relative byte offset of the MD5 message area. Rounds 8 and 19 use 3568; round 9 uses 3272; all others 3360. */
    val RoundMsgAreaOffset: IntArray = intArrayOf(
        3360, 3360, 3360, 3360, 3360, 3360, 3360, 3360,
        3568, 3272, 3360, 3360, 3360, 3360, 3360, 3360,
        3360, 3360, 3360, 3568,
    )

    /** hash.go `RoundBTbox`: the 15 T-box XOR constants per round, XORed into SP+3056..SP+3116. Non-zero only for rounds 8 and 10. */
    val RoundBTbox: Array<IntArray> = arrayOf(
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00017068, 0x00000000, 0x00000000,
            0x00000000, 0x00017068, 0x00000000, 0x00000000, 0x00000000,
            0x00017068, 0x00000000, 0x00000000, 0x00000000, 0x2616e38c,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x2616e38c,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
        intArrayOf(
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        ),
    )


    // =====================================================================
    // m3hash.go -- the Phase-1 bridge MD5 (round-31 permutation family)
    // =====================================================================


    /** m3hash.go `BridgeMD5IV`: the bridge hash IV. Identical to [round8InitialState]. */
    val BridgeMD5IV: IntArray = intArrayOf(
        0xb9f3dcdc.toInt(), 0xfbdc740b.toInt(), 0x60f77f86, 0x51907216,
    )

    /** m3hash.go `StdMD5K`: the standard RFC 1321 MD5 per-round additive constants. The bridge's real constant is `StdMD5K[i] + offset`. */
    val StdMD5K: IntArray = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )

    /** m3hash.go `bridgeMD5Rot`: standard MD5 LEFT-rotation amounts. */
    val bridgeMD5Rot: IntArray = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** m3hash.go `bridgeMD5Schedule`: the standard MD5 message-word schedule. */
    val bridgeMD5Schedule: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        1, 6, 11, 0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12,
        5, 8, 11, 14, 1, 4, 7, 10, 13, 0, 3, 6, 9, 12, 15, 2,
        0, 7, 14, 5, 12, 3, 10, 1, 8, 15, 6, 13, 4, 11, 2, 9,
    )


    /** m3hash.go `BridgeHash1Offset`: added to StdMD5K for Hash1's blocks B1-B4. */
    val BridgeHash1Offset: Int = 0xb36309e4.toInt()

    /** m3hash.go `BridgeHash1FinalOffset`: Hash1's final block B5 uses plain StdMD5K. */
    val BridgeHash1FinalOffset: Int = 0x00000000

    /** m3hash.go `BridgeHash2Offset`: added to StdMD5K for Hash2's blocks C1-C4. */
    val BridgeHash2Offset: Int = 0xd68864c0.toInt()

    // =====================================================================
    // spn1_r8r19.go -- the R8/R19 staging messages
    // =====================================================================


    /** spn1_r8r19.go `r8Suffix`: R8's second message block plus the shared MD5 pad+length. */
    val r8Suffix: ByteArray = bytes(
        0xa0, 0x2b, 0xc2, 0xaf, 0xfb, 0xfc, 0xef, 0x49, 0x5e, 0xac, 0x67, 0xfe, 0xcb, 0xfb, 0xf6, 0xbe,
        0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    /** spn1_r8r19.go `mdPadLen`: the MD5 pad+length tail shared by R8 and R19 (bytes 32..63). */
    val mdPadLen: ByteArray = bytes(
        0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    )


    // =====================================================================
    // finalize.go -- the legacy span7 fold
    // =====================================================================

    /** finalize.go `TailByteSubstXOR`: XORed into span7[0:4] after the fold. */
    val TailByteSubstXOR: Int = 0xc054bc67.toInt()


    /** finalize.go `FoldRoundOrder`: fold index -> round number for the legacy span7 fold. */
    val FoldRoundOrder: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7,
        10,
    )

    /** finalize.go `GpFoldConstants`: the payload-independent GP-mixing XOR constants. */
    val GpFoldConstants: Array<IntArray> = arrayOf(
        intArrayOf(
            0x1f35dcbd, 0x0620944b, 0x6b2ebcff, 0x4c04404b,
        ),
        intArrayOf(
            0x12d93967, 0xa8497349.toInt(), 0x38bfbad7, 0x6b3cfc83,
        ),
        intArrayOf(
            0xc0c6f215.toInt(), 0xeb86897b.toInt(), 0xf3e98781.toInt(), 0x7d667a8e,
        ),
        intArrayOf(
            0x5deb0b4a, 0x25ae3c64, 0x56dbfa68, 0x63eaef7f,
        ),
        intArrayOf(
            0x4db1f4ee, 0x7d8bca77, 0xce8ffa1e.toInt(), 0x671db270,
        ),
        intArrayOf(
            0x742116f8, 0xac346a48.toInt(), 0x0b6a8a30, 0x873a97ba.toInt(),
        ),
        intArrayOf(
            0x363c61ac, 0x5291fc79, 0xb388e73d.toInt(), 0x24f84651,
        ),
        intArrayOf(
            0x15f85e7e, 0x99b15da3.toInt(), 0xa187359d.toInt(), 0x2ff544a2,
        ),
        intArrayOf(
            0x6ca729d8, 0x80292920.toInt(), 0xb2d8299f.toInt(), 0x623b2de6,
        ),
    )


    // =====================================================================
    // analytical.go -- the payload-independent normal-round IV
    // =====================================================================


    /** analytical.go `normalRoundIV`: the payload-independent constant IV that every normal WB-MD5 round starts from. Equals round 9's output, the no-op restoration round. */
    val normalRoundIV: IntArray = intArrayOf(
        0x1d4a4587, 0x92f39fcc.toInt(), 0x1d87d836, 0xcdc86697.toInt(),
    )


    // =====================================================================
    // Shared helpers
    // =====================================================================

    /** Packs a list of 0..255 byte values, so the literals above stay readable. */
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /**
     * tablepack_rt.go `unpackTable`: rebuilds one 256-byte table from a base table
     * and its XOR masks -- `table[v] = bases[spec[off]*256 + (v xor inXor)] xor outXor`.
     */
    private fun unpackTable(bases: IntArray, spec: IntArray, off: Int): ByteArray {
        val base = spec[off] * 256
        val inXor = spec[off + 1]
        val outXor = spec[off + 2]
        return ByteArray(256) { v -> ((bases[base + (v xor inXor)] and 0xFF) xor outXor).toByte() }
    }

    /**
     * hiddeng2.go `unpackHiddenWords`: rebuilds the 20x16 hidden-word table from
     * row 0 plus per-row (index, little-endian u32) patches.
     */
    private fun unpackHiddenWords(base: IntArray, patch: IntArray): Array<IntArray> {
        val row0 = IntArray(16) { i ->
            base[i * 4] or (base[i * 4 + 1] shl 8) or (base[i * 4 + 2] shl 16) or
                (base[i * 4 + 3] shl 24)
        }
        val w = Array(20) { IntArray(16) }
        w[0] = row0
        var p = 0
        for (r in 1 until 20) {
            val row = row0.copyOf()
            val n = patch[p]
            p++
            for (k in 0 until n) {
                val idx = patch[p]
                row[idx] = patch[p + 1] or (patch[p + 2] shl 8) or (patch[p + 3] shl 16) or
                    (patch[p + 4] shl 24)
                p += 5
            }
            w[r] = row
        }
        check(p == patch.size) { "HiddenWords patch not fully consumed: $p != ${patch.size}" }
        return w
    }

    /**
     * Every unpacked table is a substitution box, so every one must be a
     * bijection. A mis-decoded `\xNN` escape still yields 256 plausible bytes --
     * it just stops being injective. This runs once at object init and fails
     * loudly instead of producing a response no receiver accepts.
     */
    init {
        var n = 0
        fun verify(table: ByteArray, name: String) {
            val seen = BooleanArray(256)
            for (b in table) {
                val v = b.toInt() and 0xFF
                check(!seen[v]) { "Phase2Tables: $name is not a bijection (duplicate output $v)" }
                seen[v] = true
            }
            n++
        }
        for (i in 0 until 9) for (j in 0 until 16) verify(SPN1CoreTables[i][j], "SPN1CoreTables[$i][$j]")
        for (p in 0 until 16) verify(SPN1TrailTables[p], "SPN1TrailTables[$p]")
        for (p in 0 until 16) verify(gSbox[p], "gSbox[$p]")
        check(n == UNPACKED_TABLE_COUNT) {
            "Phase2Tables: verified $n substitution tables, expected $UNPACKED_TABLE_COUNT"
        }
    }
}
