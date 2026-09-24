package tw.avianjay.airplaydroid.protocol.pairing

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import tw.avianjay.airplaydroid.protocol.http.AirPlayConnection
import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The older AirPlay PIN pairing, on `/pair-setup-pin`.
 *
 * A different protocol from [HomeKitPairing], not a variant: binary plist
 * bodies, a 2048-bit SRP group with SHA-1, and the client's own identifier as
 * the SRP username. The PIN that `/pair-pin-start` shows belongs to this flow,
 * which is why proving it against a HomeKit `/pair-setup` session can never
 * succeed however correct the HomeKit side is.
 *
 * There are **three** request steps, and omitting the third is answered `500`:
 *
 * 1. `{method: "pin", user: <client id>}` -> `{pk, salt}`
 * 2. `{pk: A, proof: M}`                  -> proof accepted
 * 3. `{epk, authTag}`                     -> the client's Ed25519 public key,
 *    AES-GCM encrypted under keys derived from the SRP session key.
 *
 * Credentials are a 32-byte Ed25519 seed plus an 8-byte identifier; the
 * identifier is presented as uppercase hex and doubles as the SRP username.
 */
class LegacyPairing(
    private val connection: AirPlayConnection,
    /** 8 random bytes; hex-encoded uppercase this is the SRP username. */
    val clientIdBytes: ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) },
    /** 32-byte Ed25519 seed. Doubles as the SRP private exponent `a`. */
    val clientPrivateKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) {

    val clientId: String = clientIdBytes.joinToString("") { "%02X".format(it) }

    /** What the receiver returns in step one. */
    class Challenge(val salt: ByteArray, val serverPublicKey: ByteArray)

    /** Everything needed to drive a later pair-verify. Persist this. */
    class Credentials(
        val clientId: ByteArray,
        val clientPrivateKey: ByteArray,
        val sessionKey: ByteArray,
    )

    sealed class Failure(message: String) : Exception(message) {
        class Refused(val status: Int, val step: String, val body: ByteArray = ByteArray(0)) :
            Failure(
                "receiver refused $step with $status" +
                    (if (body.isNotEmpty()) " body=" + String(body).take(160) else "")
            )

        class Malformed(val detail: String) : Failure("malformed pairing response: $detail")
        class WrongPin : Failure("the receiver rejected that PIN")
    }

    /** Asks the receiver to display a PIN. */
    fun startPin() {
        val response = connection.exchange(request("/pair-pin-start", null))
        if (!response.isSuccess) throw Failure.Refused(response.status, "pair-pin-start")
    }

    /** Step one: obtain the salt and the receiver's public key. */
    fun begin(): Challenge {
        val response = connection.exchange(
            request(
                "/pair-setup-pin",
                BinaryPlist.encode(
                    PlistValue.dict(
                        "method" to PlistValue.PString("pin"),
                        "user" to PlistValue.PString(clientId),
                    )
                ),
            )
        )
        if (!response.isSuccess) throw Failure.Refused(response.status, "step 1", response.body)

        val dict = BinaryPlist.decode(response.body) as? PlistValue.PDict
            ?: throw Failure.Malformed("step one did not return a dictionary")
        val salt = (dict["salt"] as? PlistValue.PData)?.value
            ?: throw Failure.Malformed("no salt")
        val pk = (dict["pk"] as? PlistValue.PData)?.value
            ?: throw Failure.Malformed("no pk")
        return Challenge(salt, pk)
    }

    /** Steps two and three: prove the PIN, then hand over the long-term public key. */
    fun complete(challenge: Challenge, pin: String): Credentials {
        val srp = LegacyAirPlaySrp.compute(
            username = clientId,
            pin = pin,
            salt = challenge.salt,
            serverPublicKey = challenge.serverPublicKey,
            clientPrivateKey = clientPrivateKey,
        )

        val step2 = connection.exchange(
            request(
                "/pair-setup-pin",
                BinaryPlist.encode(
                    PlistValue.dict(
                        "pk" to PlistValue.PData(srp.publicKey),
                        "proof" to PlistValue.PData(srp.proof),
                    )
                ),
            )
        )
        if (step2.status == 470 || step2.status == 401) throw Failure.WrongPin()
        if (!step2.isSuccess) throw Failure.Refused(step2.status, "step 2", step2.body)

        // Step three: AES-GCM the Ed25519 public key under keys derived from K.
        val publicKey = Ed25519PrivateKeyParameters(clientPrivateKey, 0)
            .generatePublicKey().encoded

        val aesKey = sha512("Pair-Setup-AES-Key".toByteArray(), srp.sessionKey).copyOf(16)
        val aesIv = sha512("Pair-Setup-AES-IV".toByteArray(), srp.sessionKey).copyOf(16)
        // The last IV byte is incremented by one. Not a typo in the reference.
        aesIv[15] = (aesIv[15] + 1).toByte()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, aesIv),
        )
        val sealed = cipher.doFinal(publicKey)
        val epk = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)

        val step3 = connection.exchange(
            request(
                "/pair-setup-pin",
                BinaryPlist.encode(
                    PlistValue.dict(
                        "epk" to PlistValue.PData(epk),
                        "authTag" to PlistValue.PData(tag),
                    )
                ),
            )
        )
        if (!step3.isSuccess) throw Failure.Refused(step3.status, "step 3", step3.body)

        return Credentials(clientIdBytes, clientPrivateKey, srp.sessionKey)
    }

    private fun request(uri: String, body: ByteArray?) = AirPlayRequest(
        method = "POST",
        uri = uri,
        protocol = AirPlayRequest.RTSP_1_0,
        headers = buildList {
            add("User-Agent" to "AirPlay/320.20")
            add("Connection" to "keep-alive")
            if (body != null) add("Content-Type" to "application/x-apple-binary-plist")
        },
        body = body ?: ByteArray(0),
    )

    private fun sha512(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }
}
