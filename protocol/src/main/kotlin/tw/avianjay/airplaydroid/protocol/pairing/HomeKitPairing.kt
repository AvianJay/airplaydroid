package tw.avianjay.airplaydroid.protocol.pairing

import tw.avianjay.airplaydroid.protocol.StatusFlags
import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import java.util.UUID

/**
 * HomeKit pair-setup over the AirPlay control channel.
 *
 * **The whole exchange must run on ONE TCP connection.** The receiver keeps the
 * SRP state (salt, its private exponent, `B`) against the connection, so sending
 * M3 on a fresh socket is answered `470 Connection Authorization Required` — a
 * message that says exactly what is wrong once you know to read it that way.
 * This class therefore takes an [AirPlayConnection] and reuses it.
 *
 * Which mode to use is dictated by the receiver's `flags`, not by preference:
 *
 * | `flags` bit | meaning              | mode                        | SRP password |
 * |---|---|---|---|
 * | 7  | a password is set     | [Mode.PERSISTENT], `X-Apple-HKP: 3` | the device password |
 * | 9  | pairing required      | [Mode.PERSISTENT], `X-Apple-HKP: 3` | the on-screen PIN |
 * | neither |                  | [Mode.TRANSIENT], `X-Apple-HKP: 4`  | [TRANSIENT_PASSWORD] |
 *
 * Sending the wrong one is refused with `470` at M3, before the proof is even
 * checked. An earlier version of this table mapped bit 7 to transient, which is
 * exactly what produced the 470 against the Apple TV 4K; owntone's
 * `response_handler_info_generic` has the correct mapping, and persistent mode
 * with the device password was then confirmed to reach M4 on tvOS 26.6.
 *
 * Wire format confirmed against an Apple TV 4K on tvOS 26.6:
 * requests are `RTSP/1.0`, bodies are TLV8 despite a `Content-Type` header
 * claiming binary plist, and the 384-byte keys arrive TLV-fragmented as 255+129.
 */
class HomeKitPairing(
    private val connection: AirPlayConnection,
    private val clientId: String = UUID.randomUUID().toString().uppercase(),
    private val clientName: String = "AirPlayDroid",
) {

    enum class Mode(val hkpHeader: String) {
        /** No long-term keys are kept. Used when the receiver advertises a password. */
        TRANSIENT("4"),

        /** Establishes a lasting pairing. Used when the receiver demands pairing. */
        PERSISTENT("3"),
    }

    sealed class Failure(message: String) : Exception(message) {
        class Refused(val status: Int) :
            Failure("receiver refused pair-setup with $status" +
                if (status == 470) " (wrong mode, or M1 and M3 were not on one connection)" else "")

        class Rejected(val error: Tlv8.PairError) : Failure("receiver returned ${error.name}")
        class Malformed(val detail: String) : Failure("malformed pairing response: $detail")
        class ProofMismatch : Failure("the receiver's proof did not verify")
    }

    /**
     * Runs M1 -> M4. [password] is the transient constant for [Mode.TRANSIENT]
     * or the PIN shown on screen for [Mode.PERSISTENT].
     *
     * Returns the SRP shared secret `K`, from which the session keys derive.
     */
    fun pairSetup(mode: Mode, password: String): ByteArray {
        val m2 = exchange(
            mode,
            cseq = 0,
            body = Tlv8.encode(
                buildList {
                    add(Tlv8.METHOD to Tlv8.byte(0))
                    add(Tlv8.STATE to Tlv8.byte(1))
                    if (mode == Mode.TRANSIENT) {
                        add(Tlv8.FLAGS to Tlv8.byte(Tlv8.FLAG_TRANSIENT))
                    }
                }
            ),
        )

        val salt = m2[Tlv8.SALT] ?: throw Failure.Malformed("M2 carried no salt")
        val serverKey = m2[Tlv8.PUBLIC_KEY] ?: throw Failure.Malformed("M2 carried no public key")

        val session = Srp6aClient().start(password, salt, serverKey)

        val m4 = exchange(
            mode,
            cseq = 1,
            body = Tlv8.encode(
                Tlv8.STATE to Tlv8.byte(3),
                Tlv8.PUBLIC_KEY to session.publicKey,
                Tlv8.PROOF to session.clientProof,
            ),
        )

        val proof = m4[Tlv8.PROOF] ?: throw Failure.Malformed("M4 carried no proof")
        if (!proof.contentEquals(session.expectedServerProof)) throw Failure.ProofMismatch()

        return session.sharedSecret
    }

    private fun exchange(mode: Mode, cseq: Int, body: ByteArray): Map<Int, ByteArray> {
        val response = connection.exchange(
            AirPlayRequest(
                method = "POST",
                uri = "/pair-setup",
                // RTSP/1.0, as a real sender uses -- not HTTP/1.1.
                protocol = AirPlayRequest.RTSP_1_0,
                headers = listOf(
                    "X-Apple-HKP" to mode.hkpHeader,
                    "X-Apple-Client-ID" to clientId,
                    "X-Apple-Client-Name" to clientName,
                    // The receiver expects this header even though the body is TLV8.
                    "Content-Type" to "application/x-apple-binary-plist",
                    "CSeq" to cseq.toString(),
                ),
                body = body,
            )
        )

        if (!response.isSuccess) throw Failure.Refused(response.status)

        val tlv = Tlv8.decode(response.body)
        Tlv8.errorOf(tlv)?.let { throw Failure.Rejected(it) }
        return tlv
    }

    companion object {
        /** The transient flow authenticates with this fixed value, not a user secret. */
        const val TRANSIENT_PASSWORD = "3939"

        /** Picks the mode the receiver's own flags demand. */
        fun modeFor(flags: StatusFlags): Mode =
            if (flags.pairingRequired || flags.passwordRequired) Mode.PERSISTENT else Mode.TRANSIENT
    }
}
