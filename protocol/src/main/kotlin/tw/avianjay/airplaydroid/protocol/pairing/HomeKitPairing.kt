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
    private val clientName: String = DEFAULT_CLIENT_NAME,
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
        class SignatureMismatch : Failure("the receiver's signature did not verify")
    }

    /**
     * The outcome of a persistent pairing. Persist all of it: pair-verify needs
     * our long-term key to sign with, and the receiver's to check its signature.
     */
    class Credentials(
        val clientId: String,
        /** 32-byte Ed25519 seed. Secret. */
        val clientSeed: ByteArray,
        val receiverId: ByteArray,
        /** The receiver's 32-byte Ed25519 long-term public key. */
        val receiverPublicKey: ByteArray,
    ) {
        /** `key=hex` lines. Contains [clientSeed]: store it privately, never in a backup. */
        fun encode(): String =
            "clientId=$clientId\n" +
                "clientSeed=${clientSeed.hex()}\n" +
                "receiverId=${receiverId.hex()}\n" +
                "receiverPublicKey=${receiverPublicKey.hex()}\n"

        companion object {
            fun decode(text: String): Credentials {
                val fields = text.lines().filter { '=' in it }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                fun field(name: String) = fields[name] ?: throw IllegalArgumentException("credentials lack $name")
                return Credentials(
                    clientId = field("clientId"),
                    clientSeed = field("clientSeed").unhex(),
                    receiverId = field("receiverId").unhex(),
                    receiverPublicKey = field("receiverPublicKey").unhex(),
                )
            }

            private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

            private fun String.unhex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }

    /**
     * Persistent pair-setup, M1 -> M6. After the SRP half, each side proves it
     * holds an Ed25519 long-term key by signing a value derived from `K`, and the
     * two public keys are exchanged under ChaCha20-Poly1305 keyed from `K`.
     *
     * [password] is the device password for a receiver with `flags` bit 7, or
     * the on-screen PIN for one with bit 9.
     */
    fun pair(password: String): Credentials {
        val k = pairSetup(Mode.PERSISTENT, password)
        val longTerm = HapCrypto.Ed25519KeyPair.generate()
        val encryptKey = HapCrypto.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k)

        val controllerX = HapCrypto.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k)
        val id = clientId.toByteArray()
        val subTlv = Tlv8.encode(
            Tlv8.IDENTIFIER to id,
            Tlv8.PUBLIC_KEY to longTerm.publicKey,
            Tlv8.SIGNATURE to longTerm.sign(controllerX + id + longTerm.publicKey),
        )

        val m6 = exchange(
            Mode.PERSISTENT,
            body = Tlv8.encode(
                Tlv8.STATE to Tlv8.byte(5),
                Tlv8.ENCRYPTED_DATA to HapCrypto.seal(encryptKey, HapCrypto.labelNonce("PS-Msg05"), subTlv),
            ),
        )

        val sealed = m6[Tlv8.ENCRYPTED_DATA] ?: throw Failure.Malformed("M6 carried no encrypted data")
        val plain = try {
            HapCrypto.open(encryptKey, HapCrypto.labelNonce("PS-Msg06"), sealed)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw Failure.Malformed("M6 did not decrypt: ${e.message}")
        }
        val accessory = Tlv8.decode(plain)
        val receiverId = accessory[Tlv8.IDENTIFIER] ?: throw Failure.Malformed("M6 carried no identifier")
        val receiverKey = accessory[Tlv8.PUBLIC_KEY] ?: throw Failure.Malformed("M6 carried no public key")
        val signature = accessory[Tlv8.SIGNATURE] ?: throw Failure.Malformed("M6 carried no signature")

        // pyatv skips this check; pair_ap does it, and so do we.
        val accessoryX = HapCrypto.hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", k)
        if (!HapCrypto.ed25519Verify(receiverKey, accessoryX + receiverId + receiverKey, signature)) {
            throw Failure.SignatureMismatch()
        }

        return Credentials(clientId, longTerm.seed, receiverId, receiverKey)
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

    /**
     * Asks the receiver to show a one-time PIN on its screen. For a receiver with
     * `flags` bit 9 (or bit 3): what Apple TV does under "Allow Access" without a
     * password, where every new device must enter the code once. Then call
     * [pair] with the PIN, on this same connection.
     *
     * A receiver in password mode answers 200 here but shows nothing, so this is
     * only for bit 9 / bit 3.
     */
    fun startPin() {
        val response = connection.exchange(
            AirPlayRequest(
                method = "POST",
                uri = "/pair-pin-start",
                protocol = AirPlayRequest.RTSP_1_0,
                headers = listOf(
                    "X-Apple-HKP" to Mode.PERSISTENT.hkpHeader,
                    "X-Apple-Client-ID" to clientId,
                    "X-Apple-Client-Name" to clientName,
                    "CSeq" to (nextCseq++).toString(),
                ),
                body = null,
            )
        )
        if (!response.isSuccess) throw Failure.Refused(response.status)
    }

    private var nextCseq = 0

    private fun exchange(mode: Mode, body: ByteArray, uri: String = "/pair-setup"): Map<Int, ByteArray> {
        val response = connection.exchange(
            AirPlayRequest(
                method = "POST",
                uri = uri,
                // RTSP/1.0, as a real sender uses -- not HTTP/1.1.
                protocol = AirPlayRequest.RTSP_1_0,
                headers = listOf(
                    "X-Apple-HKP" to mode.hkpHeader,
                    "X-Apple-Client-ID" to clientId,
                    "X-Apple-Client-Name" to clientName,
                    // The receiver expects this header even though the body is TLV8.
                    "Content-Type" to "application/x-apple-binary-plist",
                    "CSeq" to (nextCseq++).toString(),
                ),
                body = body,
            )
        )

        if (!response.isSuccess) throw Failure.Refused(response.status)

        val tlv = Tlv8.decode(response.body)
        Tlv8.errorOf(tlv)?.let { throw Failure.Rejected(it) }
        return tlv
    }

    /**
     * The keys pair-verify establishes: one per direction for the encrypted
     * control channel, plus the X25519 secret the stream keys derive from.
     */
    class Session(val sharedSecret: ByteArray) {
        val controlWriteKey: ByteArray =
            HapCrypto.hkdf("Control-Salt", "Control-Write-Encryption-Key", sharedSecret)
        val controlReadKey: ByteArray =
            HapCrypto.hkdf("Control-Salt", "Control-Read-Encryption-Key", sharedSecret)
    }

    /**
     * Pair-verify, M1 -> M4, against credentials from an earlier [pair]. Each
     * side signs both ephemeral X25519 keys with its long-term Ed25519 key, so
     * neither can be impersonated by someone who merely saw the pairing.
     *
     * After this returns, the caller must switch the connection to encrypted
     * framing with [Session.controlWriteKey] / [Session.controlReadKey] before
     * sending anything else.
     */
    fun verify(credentials: Credentials): Session {
        val ephemeral = HapCrypto.X25519KeyPair.generate()

        val m2 = exchange(
            Mode.PERSISTENT,
            uri = "/pair-verify",
            body = Tlv8.encode(
                Tlv8.STATE to Tlv8.byte(1),
                Tlv8.PUBLIC_KEY to ephemeral.publicKey,
            ),
        )

        val receiverEphemeral = m2[Tlv8.PUBLIC_KEY] ?: throw Failure.Malformed("verify M2 carried no public key")
        val sealed = m2[Tlv8.ENCRYPTED_DATA] ?: throw Failure.Malformed("verify M2 carried no encrypted data")

        val shared = ephemeral.agree(receiverEphemeral)
        val key = HapCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)

        val accessory = try {
            Tlv8.decode(HapCrypto.open(key, HapCrypto.labelNonce("PV-Msg02"), sealed))
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw Failure.Malformed("verify M2 did not decrypt: ${e.message}")
        }
        val receiverId = accessory[Tlv8.IDENTIFIER] ?: throw Failure.Malformed("verify M2 carried no identifier")
        val signature = accessory[Tlv8.SIGNATURE] ?: throw Failure.Malformed("verify M2 carried no signature")

        if (!receiverId.contentEquals(credentials.receiverId)) {
            throw Failure.Malformed("verify M2 came from ${String(receiverId)}, not the paired receiver")
        }
        if (!HapCrypto.ed25519Verify(
                credentials.receiverPublicKey,
                receiverEphemeral + receiverId + ephemeral.publicKey,
                signature,
            )
        ) {
            throw Failure.SignatureMismatch()
        }

        val id = credentials.clientId.toByteArray()
        val longTerm = HapCrypto.Ed25519KeyPair(credentials.clientSeed)
        val inner = Tlv8.encode(
            Tlv8.IDENTIFIER to id,
            Tlv8.SIGNATURE to longTerm.sign(ephemeral.publicKey + id + receiverEphemeral),
        )

        exchange(
            Mode.PERSISTENT,
            uri = "/pair-verify",
            body = Tlv8.encode(
                Tlv8.STATE to Tlv8.byte(3),
                Tlv8.ENCRYPTED_DATA to HapCrypto.seal(key, HapCrypto.labelNonce("PV-Msg03"), inner),
            ),
        )

        return Session(shared)
    }

    companion object {
        /** The transient flow authenticates with this fixed value, not a user secret. */
        const val TRANSIENT_PASSWORD = "3939"

        /**
         * Used only when the caller names no client. The app always passes the
         * name from its settings; the probes and tests in `:protocol` do not.
         */
        const val DEFAULT_CLIENT_NAME = "AirPlayDroid"

        /** Picks the mode the receiver's own flags demand. */
        fun modeFor(flags: StatusFlags): Mode =
            if (flags.pairingRequired || flags.pinRequired || flags.passwordRequired) Mode.PERSISTENT else Mode.TRANSIENT
    }
}
