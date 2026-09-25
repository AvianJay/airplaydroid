package tw.avianjay.airplaydroid.protocol.fairplay

import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.AirPlayResponse
import java.security.SecureRandom

/**
 * Computes the 20-byte FairPlay response for one m2 challenge.
 *
 * This is the **seam** between the framing (public, fully determined by captures)
 * and the cryptographic core (reverse-engineered, Apple-derived tables). Keeping
 * it an interface means the whole session path can be built, tested and driven
 * against real hardware without the core, and the core can be swapped without
 * touching any of it.
 *
 * The core is [FairPlayResponderImpl]; see `docs/fairplay-research.md` for what
 * it is and how far it is verified.
 */
fun interface FairPlayResponder {

    /**
     * Returns the 20-byte response for [challenge] under [mode].
     *
     * Implementations must reject a mode they do not implement rather than
     * answering from the wrong key schedule -- a plausible-looking wrong answer
     * is worse than a refusal, because it fails later and obscurely.
     *
     * **The response depends on the session's own local SAP as well as the
     * challenge.** A responder that only takes the challenge cannot be correct
     * for a session-aware sender, so [FairPlaySapSession] generates the local SAP
     * first and calls [respondWithSap] instead.
     */
    fun respond(mode: FairPlayRecords.Mode, challenge: ByteArray): ByteArray

    /**
     * Returns the response for [challenge], folding in [localSap].
     *
     * Defaults to ignoring [localSap] so a simple stub still compiles; a real
     * implementation must use it, or the receiver will reject the m3.
     */
    fun respondWithSap(
        mode: FairPlayRecords.Mode,
        challenge: ByteArray,
        localSap: ByteArray,
    ): ByteArray = respond(mode, challenge)

    companion object {
        /** Thrown by a responder that cannot answer. */
        class Unsupported(message: String) : Exception(message)
    }
}

/**
 * Drives one FairPlay SAP handshake as a **sender**, over an existing control
 * connection:
 *
 * ```
 * S -> R   m1  16 bytes    "I am an AirPlay sender" (capability mask)
 * R -> S   m2  142 bytes   mode + 128-byte challenge
 * S -> R   m3  164 bytes   fresh local SAP + 20-byte response
 * R -> S   m4  32 bytes    acknowledgement; streaming may begin
 * ```
 *
 * ### The session-aware part matters
 *
 * [localSap] is generated **fresh per session** from [random]. This is not
 * cosmetic. A sender that splices in one captured 144-byte prefix emits a
 * byte-identical m3 every time, and strict receivers reject that as a replay --
 * the documented symptom is `RTSP/1.0 466 Key Management Error`. Generating it
 * per session is what makes the exchange non-replayable.
 *
 * The shape matches Apple's own sender, as read off `doubletake`: byte 1 is
 * `0x01` and the remaining 126 bytes are opaque entropy.
 *
 * ### Reading the reply is not enough to know you succeeded
 *
 * A legacy receiver answers a **rejected** m3 with HTTP `200` and the refusal in
 * the *body* -- a 12-byte frame beginning `1e 1e 1e 1e`. So [handshake] checks
 * the body, not the status line. A sender that only looks at the status will
 * believe it succeeded and then fail much later.
 */
class FairPlaySapSession(
    private val connection: AirPlayConnection,
    private val responder: FairPlayResponder,
    private val random: SecureRandom = SecureRandom(),
    private var cseq: Int = 1,
) {

    /** What one completed handshake produced. */
    data class Result(
        /** The receiver's chosen mode. */
        val mode: FairPlayRecords.Mode,
        /** The 128-byte challenge the receiver sent. */
        val challenge: ByteArray,
        /** This session's local SAP, which the receiver has now accepted. */
        val localSap: ByteArray,
        /** The 20-byte response the responder computed. */
        val response: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Result && mode == other.mode &&
                    challenge.contentEquals(other.challenge) &&
                    localSap.contentEquals(other.localSap) &&
                    response.contentEquals(other.response)
                )

        override fun hashCode(): Int {
            var r = mode.hashCode()
            r = 31 * r + challenge.contentHashCode()
            r = 31 * r + localSap.contentHashCode()
            r = 31 * r + response.contentHashCode()
            return r
        }
    }

    /** Why a handshake did not complete. */
    sealed class Failure(message: String) : Exception(message) {
        /** The receiver did not answer m1 with a usable m2. */
        class BadChallenge(message: String) : Failure(message)

        /** The receiver answered m3 with its refusal frame. */
        class Rejected(val status: Byte?) :
            Failure(
                "receiver refused the FairPlay response" +
                    (status?.let { " (status 0x%02x)".format(it) } ?: "")
            )

        /** The receiver answered m3 with something that is neither m4 nor a refusal. */
        class UnexpectedReply(val bytes: Int, val status: Int) :
            Failure("expected an m4 or a refusal frame, got $bytes bytes under HTTP $status")
    }

    /**
     * Generates a fresh local SAP.
     *
     * Byte 1 is `0x01` and the rest is entropy, matching the layout visible in
     * Apple's sender. Byte 0 is left at zero.
     */
    private fun newLocalSap(): ByteArray {
        val sap = ByteArray(FairPlayRecords.CHALLENGE_BYTES)
        random.nextBytes(sap)
        sap[0] = 0x00
        sap[1] = 0x01
        return sap
    }

    /**
     * Runs the handshake to completion. Blocking; call from an IO dispatcher.
     *
     * @throws Failure when the receiver refuses, and [FairPlayResponder.Unsupported]
     *   when [responder] cannot answer the mode the receiver selected.
     */
    fun handshake(): Result {
        val localSap = newLocalSap()

        // ---- m1 -> m2
        val m2Response = send(FairPlayRecords.m1())
        val challenge = FairPlayRecords.parseM2(m2Response.body).getOrElse { cause ->
            throw Failure.BadChallenge(
                "receiver did not answer m1 with a usable m2: ${cause.message}"
            )
        }

        // ---- compute, then m3 -> m4
        //
        // The local SAP must be generated BEFORE the response: the receiver folds
        // it into its own check, so the 20 bytes depend on it. Computing the
        // response from the challenge alone yields a well-formed m3 that every
        // receiver rejects.
        val response = responder.respondWithSap(challenge.mode, challenge.challenge, localSap)
        require(response.size == FairPlayRecords.RESPONSE_BYTES) {
            "responder returned ${response.size} bytes, expected ${FairPlayRecords.RESPONSE_BYTES}"
        }
        val m3 = FairPlayRecords.m3(challenge.mode, localSap, response)
        val reply = send(m3)

        // A refusal rides in the BODY under a 200 status, so the status alone
        // cannot be trusted here.
        if (FairPlayRecords.isErrorFrame(reply.body)) {
            throw Failure.Rejected(FairPlayRecords.errorStatus(reply.body))
        }
        if (reply.body.size != FairPlayRecords.M4_BYTES ||
            !FairPlayRecords.confirmM4(reply.body, m3)
        ) {
            throw Failure.UnexpectedReply(reply.body.size, reply.status)
        }

        return Result(challenge.mode, challenge.challenge, localSap, response)
    }

    private fun send(body: ByteArray): AirPlayResponse =
        connection.exchange(
            AirPlayRequest(
                method = "POST",
                uri = "/fp-setup",
                protocol = AirPlayRequest.RTSP_1_0,
                headers = listOf(
                    "CSeq" to "${cseq++}",
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/octet-stream",
                ),
                body = body,
            )
        )

    companion object {
        /**
         * The version a legacy receiver reports, and the one every capture was
         * taken against.
         */
        const val USER_AGENT = "AirPlay/220.68"
    }
}
