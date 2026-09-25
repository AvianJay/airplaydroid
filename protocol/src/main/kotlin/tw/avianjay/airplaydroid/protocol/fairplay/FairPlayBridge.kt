// SPDX-License-Identifier: LGPL-3.0-or-later
/*
 * FairPlayBridge.kt - standalone Kotlin bridge hash adapter.
 *
 * Transcribed verbatim from the `objevovat` project
 * fairplay-sap-core-airplay2-sender-authentication-handshake,
 * ports/kotlin/FairPlayBridge.kt:
 *   https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake
 * The only changes made here are the `package` line and this header. No
 * constant, function or statement was renamed, reordered or "tidied".
 * NOTE: upstream carries SPDX BlueOak-1.0.0 on this one file; the repo-level
 * licence recorded here is LGPL-3.0-or-later. Reconcile before redistributing.
 *
 * APPLE-DERIVED REVERSE-ENGINEERED DATA. The bridge MD5 IV and the per-hash
 * additive offsets below are recovered from Apple's FairPlay SAP
 * implementation. They are opaque recovered constants, not portable logic.
 *
 * This is the recovered FairPlay SAP authentication primitive: standard MD5
 * compression, standard message schedule, and the standard RFC 1321 MD5 K
 * table plus a per-hash-instance additive offset -- with one extra step:
 * right after round 31, the message array is permuted in place, and rounds
 * 32-63 continue against the permuted array. It has no dependencies beyond
 * the Kotlin/JVM standard library.
 *
 * It is portable logic, not a complete payload-to-m3 responder. The full
 * handshake still needs the recovered White-Box AES data and bridge tables.
 */

package tw.avianjay.airplaydroid.protocol.fairplay

object FairPlayBridge {
    private val bridgeMd5Iv = intArrayOf(
        0xb9f3dcdc.toInt(), 0xfbdc740b.toInt(),
        0x60f77f86.toInt(), 0x51907216.toInt(),
    )

    /**
     * Standard RFC 1321 MD5 per-round additive constant table. The bridge
     * hash's real per-round constant is stdMd5K[i] + offset, where offset
     * depends only on which hash-instance a block belongs to (see the
     * BRIDGE_HASH*_OFFSET constants below) -- NOT a bespoke 64-entry table.
     */
    private val stdMd5K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db.toInt(), 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a.toInt(), 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8.toInt(), 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122.toInt(), 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821.toInt(),
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51.toInt(), 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453.toInt(), 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6.toInt(), 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed.toInt(),
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9.toInt(), 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122.toInt(), 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9.toInt(), 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6.toInt(), 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05.toInt(),
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8.toInt(), 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97.toInt(), 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3.toInt(), 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f.toInt(), 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1.toInt(),
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb.toInt(), 0xeb86d391.toInt(),
    )

    /** Per-hash-instance additive offsets, added to stdMd5K[i] for every
     * round of every block in that group. */
    val BRIDGE_HASH1_OFFSET: Int = 0xb36309e4.toInt() // Hash1's non-final blocks (first 4 of 5)
    val BRIDGE_HASH1_FINAL_OFFSET: Int = 0x00000000 // Hash1's final (5th) block: no offset
    val BRIDGE_HASH2_OFFSET: Int = 0xd68864c0.toInt() // all 4 of Hash2's blocks

    /** Which round-31-boundary message permutation a block uses. */
    enum class BridgeMutation { KDF, CYCLE }

    private fun applyBridgeMutation(message: IntArray, variant: BridgeMutation, a: Int, b: Int, c: Int, d: Int) {
        fun swap(i: Int, j: Int) {
            val tmp = message[i]
            message[i] = message[j]
            message[j] = tmp
        }
        when (variant) {
            BridgeMutation.KDF -> {
                swap(a and 15, b and 15)
                swap(c and 15, d and 15)
                for (shift in intArrayOf(4, 8, 12)) {
                    swap((a ushr shift) and 15, (b ushr shift) and 15)
                }
            }
            BridgeMutation.CYCLE -> {
                val idx = intArrayOf(
                    a and 15, b and 15, c and 15, d and 15,
                    (a ushr 4) and 15, (b ushr 4) and 15, (c ushr 4) and 15, (d ushr 4) and 15,
                )
                val first = message[idx[0]]
                for (i in 0 until idx.size - 1) {
                    message[idx[i]] = message[idx[i + 1]]
                }
                message[idx[idx.size - 1]] = first
            }
        }
    }

    private val rotations = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val schedule = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        1, 6, 11, 0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12,
        5, 8, 11, 14, 1, 4, 7, 10, 13, 0, 3, 6, 9, 12, 15, 2,
        0, 7, 14, 5, 12, 3, 10, 1, 8, 15, 6, 13, 4, 11, 2, 9,
    )

    /** Returns a fresh state initialized to the recovered bridge IV. */
    fun initialState(): IntArray = bridgeMd5Iv.copyOf()

    /**
     * Updates state in place; message contains 16 little-endian words and is
     * mutated in place by the round-31 permutation.
     */
    fun compress(state: IntArray, message: IntArray, offset: Int, variant: BridgeMutation) {
        require(state.size == 4) { "state must contain four words" }
        require(message.size == 16) { "message must contain sixteen words" }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]

        for (i in 0 until 64) {
            val function = when (i / 16) {
                0 -> (b and c) or (b.inv() and d)
                1 -> (d and b) or (d.inv() and c)
                2 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val mixed = a + function + message[schedule[i]] + stdMd5K[i] + offset
            val nextB = b + Integer.rotateLeft(mixed, rotations[i])

            a = d
            d = c
            c = b
            b = nextB

            if (i == 31) {
                applyBridgeMutation(message, variant, a, b, c, d)
            }
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }
}
