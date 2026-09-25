/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * FairPlay SAP Phase 2 -- the analytical pipeline driver.
 *
 * Phase 2 turns the 20-byte bridge digest (`x9`) into the 20-byte m3 response.
 * Upstream Go (`fpbridge/fp_exchange_native.go`):
 *
 *   ns     := bridgeNeonState(x9Data)
 *   state  := fairplayhash.HashState{Mem: make([]byte, 16384)}
 *   fairplayhash.ComputeM3Setup(&state, [4]uint32{})
 *   fairplayhash.ComputeHashAnalytical(&state, &ns, x9Data)
 *   result := state.Mem[Span7Offset : Span7Offset+20]
 *
 * `ComputeM3Setup` only writes the caller-supplied (all-zero) initial MD5 words
 * into `RoundMsgAreaOffset[0]`; `ComputeHashAnalytical` never reads them, so the
 * 16 KB scratch window carries no information into the result. What remains is
 * a pure function of `x9Data`:
 *
 *   rounds 0..7, 10..18   independent WB-MD5, seeded by the NEON prologue
 *     mixTable = BE(roundOutputs[10..18])                    144 bytes
 *     SPN1(roundOutputs) -> R8 hidden words                  -> roundOutputs[8]
 *     TailInput(roundOutputs) -> TailSPN                     -> R19 hidden words -> [19]
 *
 *   span7[0:4]  = TailSPN(TailInput(ro), mixTable)[0:4]
 *   span7[4:20] = BigEndian(roundOutputs[19])
 *
 * The straight 0..19 loop is structurally wrong: rounds 8 and 19 consume the
 * *later* rounds' outputs through the mixing table, which is why the two-phase
 * order above is required. Round 9 is a no-op restoration round whose output is
 * the constant [Phase2Tables.normalRoundIV].
 *
 * The constant tables live in `FairPlayPhase2Tables.kt`, the WB-MD5 round
 * machinery in `FairPlayPhase2Rounds.kt`, and the WB-AES SPN passes in
 * `FairPlayPhase2Spn.kt`. This file is only the driver.
 */
package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * Phase 2 of the FairPlay SAP response: the analytical white-box MD5 pipeline
 * that maps the bridge digest to the m3 response.
 *
 * ### Where Phase 2 sits
 *
 * ```go
 * gp := wbaesFullPhase1(payload)      // Phase 1  -- implemented
 * x9 := bridgeX9DataClosed(gp)        // Bridge   -- implemented
 * return exchangeFromX9(x9[:])        // Phase 2  -- this file
 * ```
 *
 * ### Only the 20-byte digest is an input
 *
 * Upstream's `x9Data` is 64 bytes: this 20-byte head plus a 44-byte **constant**
 * tail. `fpbridge/fp_bridge_closed.go` says so explicitly -- "The tail is
 * constant across both payload and local SAP: Phase 2 is seeded by the 20-byte
 * descriptor alone, which is why doubletake's exchange takes only that digest."
 *
 * So [exchangeFromX9] taking 20 bytes is the correct shape, not a simplification:
 * the tail carries no payload information and is re-appended internally
 * ([Phase2Rounds] holds it as `Phase2Tables.bridgeX9Tail`) because the prologue
 * reads the full 64-byte window for rounds 1+.
 */
internal object FairPlayPhase2 {

    /**
     * Runs Phase 2 over the bridge digest, producing the 20-byte m3 response.
     *
     * [x9Data] is [FairPlaySapCore.bridgeX9HeadForSap]'s output.
     */
    fun exchangeFromX9(x9Data: ByteArray): ByteArray {
        require(x9Data.size == X9_BYTES) { "x9Data is $X9_BYTES bytes, got ${x9Data.size}" }

        val ns = Phase2Spn.bridgeNeonState(x9Data)
        val ro = computeRoundOutputsAnalytical(ns, x9Data)

        // span7[0:4] comes from the tail SPN pass, which is itself a pure
        // function of the round outputs -- recomputed here exactly as
        // ComputeHashAnalytical does, rather than threaded out of the round
        // computation.
        val tailOut = Phase2Spn.tailSpn(Phase2Spn.tailInput(ro), mixTable(ro))

        val out = ByteArray(RESPONSE_BYTES)
        tailOut.copyInto(out, 0, 0, 4)
        for (w in 0 until 4) {
            putBeU32(out, 4 + w * 4, ro[19][w])
        }
        return out
    }

    /**
     * analytical.go `ComputeRoundOutputsAnalytical`: all 20 WB-MD5 round outputs
     * in the two-phase order the pipeline requires.
     *
     * Every normal round (0..7, 10..18) shares ONE hidden-word set -- the Phase-1
     * `Vreg0` prologue output -- and differs only by the per-round counter
     * injected into the MSB of `hidden[5]`. They all run from the
     * payload-independent constant IV [Phase2Tables.normalRoundIV].
     *
     * Rounds 8 and 19 are the two SPN-consuming rounds. They run from
     * [Phase2Tables.round8InitialState] with hidden words derived from the
     * SPN staging messages, and they depend on the *later* rounds' outputs via
     * the mixing table -- hence the ordering below.
     */
    fun computeRoundOutputsAnalytical(ns: NeonState, x9Data: ByteArray): Array<IntArray> {
        val ro = Array(20) { IntArray(4) }

        // ALL normal rounds share this base; only hidden[5]'s MSB moves.
        val base = Phase2Rounds.computeHiddenWords(ns, x9Data, 0)

        fun runCtr(c: Int): IntArray {
            val h = base.copyOf()
            h[5] += c shl 24
            val st = Phase2Tables.normalRoundIV.copyOf()
            Phase2Rounds.roundCMd5Plain(st, h, null)
            return st
        }

        // Execution order != storage order: pass 1 stores counters 0..8 into
        // ro[10..18].
        ro[10] = runCtr(0)
        for (k in 1..8) {
            ro[10 + k] = runCtr(k)
        }

        // Round 9 is the no-op restoration round; its output is the constant IV.
        ro[9] = Phase2Tables.normalRoundIV.copyOf()

        // Pass 2's normal rounds reproduce ro[0..7] identically to ro[11..18]
        // (counters 1..8).
        for (k in 0 until 8) {
            ro[k] = ro[11 + k].copyOf()
        }

        // The mixing table is BE(roundOutputs[10..18]), the 144-byte AddRoundKey
        // source that feeds both SPN#1 and TailSPN.
        val mix = mixTable(ro)

        // Round 8: SPN#1 output -> hidden words -> RoundC.
        val spn1Out = Phase2Spn.spn1(ro)
        ro[8] = round8InitialStateCopy().also {
            Phase2Rounds.roundCMd5Plain(it, Phase2Rounds.r8HiddenWords(spn1Out), null)
        }

        // Round 19: TailInput -> TailSPN -> staging(ro8, tailOut) -> hidden -> RoundC.
        val tailOut = Phase2Spn.tailSpn(Phase2Spn.tailInput(ro), mix)
        ro[19] = round8InitialStateCopy().also {
            Phase2Rounds.roundCMd5Plain(it, Phase2Rounds.r19HiddenWords(ro[8], tailOut), null)
        }

        return ro
    }

    /** `mixTable = BE(roundOutputs[10..18])`, 144 bytes. */
    private fun mixTable(ro: Array<IntArray>): ByteArray {
        val mix = ByteArray(144)
        for (b in 0 until 9) {
            Phase2Spn.beRoundOutput(ro[10 + b]).copyInto(mix, b * 16)
        }
        return mix
    }

    /** A fresh copy of the constant round-8/19 initial MD5 state. */
    private fun round8InitialStateCopy(): IntArray = Phase2Tables.round8InitialState.copyOf()

    /** `binary.BigEndian.PutUint32(b[off:], v)`. */
    private fun putBeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    const val X9_BYTES = 20
    const val RESPONSE_BYTES = 20
}
