package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * A FairPlay SAP responder: an m2 challenge in, the 20-byte response out.
 *
 * ```
 * challenge (128 B) --FairPlayWhiteBox.phase1--> gp (128 B)
 * (localSap, gp)    --FairPlaySapCore.bridgeX9HeadForSap--> x9Data (20 B)
 * x9Data            --FairPlayPhase2.exchangeFromX9--> response (20 B)
 * ```
 *
 * ### INCOMPLETE: Phase 2 is not implemented
 *
 * [FairPlayPhase2] throws, so this responder cannot produce a valid response and
 * **legacy mirroring does not work**. That is deliberate: before, this returned
 * the bridge output as if it were the answer, which produces 20 well-formed bytes
 * that every receiver rejects -- indistinguishable from a receiver that never
 * evaluated them.
 *
 * The gap was found by testing against real hardware, not by the test suite,
 * because the suite checked the two components (70/70 vectors) rather than the
 * whole chain. See `docs/fairplay-status.md`.
 *
 * ### The response depends on BOTH the challenge and the local SAP
 *
 * The gp buffer comes from the *challenge*; the local SAP is the sender's own
 * per-session value. Upstream:
 *
 * ```go
 * gp := wbaesFullPhase1(payload)                   // payload = m2 challenge
 * x9 := bridgeX9DataClosedForSAP(s.localSAP, gp)   // localSAP = this session's
 * ```
 *
 * Passing the challenge in both slots is the mistake this docstring exists to
 * prevent: it also yields plausible 20 bytes that no receiver accepts.
 */
object FairPlayResponderImpl : FairPlayResponder {

    override fun respond(mode: FairPlayRecords.Mode, challenge: ByteArray): ByteArray =
        respondWithSap(mode, challenge, ByteArray(128).also { it[1] = 0x01 })

    override fun respondWithSap(
        mode: FairPlayRecords.Mode,
        challenge: ByteArray,
        localSap: ByteArray,
    ): ByteArray {
        require(challenge.size == 128) { "a challenge is 128 bytes, got ${challenge.size}" }
        require(localSap.size == 128) { "a local SAP is 128 bytes, got ${localSap.size}" }
        if (mode != FairPlayRecords.Mode.MODE_3) {
            throw FairPlayResponder.Companion.Unsupported(
                "FairPlay mode ${mode.value} is not implemented; Phase 1's tables bake mode 3's key schedule"
            )
        }

        // gp comes from the CHALLENGE; the bridge folds in the LOCAL SAP.
        val gp = FairPlayWhiteBox.phase1(challenge)
        val x9Data = FairPlaySapCore.bridgeX9HeadForSap(localSap, gp)

        // Phase 2. Throws until it is ported -- see the class doc.
        return FairPlayPhase2.exchangeFromX9(x9Data)
    }
}
