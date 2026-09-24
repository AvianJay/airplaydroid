package tw.avianjay.airplaydroid.protocol.http

import java.security.MessageDigest

/**
 * HTTP Digest authentication for the AirPlay control channel.
 *
 * HARDWARE-VERIFIED against an Apple TV 4K (AppleTV6,2, tvOS 26.6) with
 * "Require Password" switched on. That receiver answers an unauthenticated
 * `POST /play` with:
 *
 *     HTTP/1.1 401 Unauthorized
 *     WWW-Authenticate: Digest realm="airplay", nonce="MTc4OTUxODg2OSB+..."
 *
 * and accepts the retry with a Digest header, returning 200.
 *
 * This matters more than it looks. A receiver advertising `flags` bit 7
 * (password) wants **RFC 2617 Digest**, not HomeKit pair-setup -- even when it
 * is an AirPlay 2 device that also advertises feature bits 38 and 48. The
 * password path and the pairing path are separate, and the password one is
 * three orders of magnitude less work.
 *
 * MD5 is weak, but it is what the protocol mandates; there is no stronger
 * option to negotiate here.
 */
object DigestAuth {

    /** The username real senders present. The receiver derives HA1 from whatever we send. */
    const val DEFAULT_USERNAME = "AirPlay"

    /** Parsed `WWW-Authenticate: Digest ...` challenge. */
    data class Challenge(
        val realm: String,
        val nonce: String,
        val qop: String? = null,
        val opaque: String? = null,
        val algorithm: String? = null,
    ) {
        companion object {
            /**
             * Parses the header value. Returns null for anything that is not a
             * Digest challenge, so callers can distinguish "no auth offered"
             * from "auth failed".
             */
            fun parse(headerValue: String?): Challenge? {
                val value = headerValue?.trim() ?: return null
                if (!value.regionMatches(0, "Digest", 0, 6, ignoreCase = true)) return null

                val params = HashMap<String, String>()
                // key="value" or key=value, comma separated, values may contain commas
                // only inside quotes -- which AirPlay nonces do not, but be safe.
                val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
                regex.findAll(value.substring(6)).forEach { m ->
                    val key = m.groupValues[1].lowercase()
                    params[key] = m.groupValues[2].ifEmpty { m.groupValues[3] }
                }

                val realm = params["realm"] ?: return null
                val nonce = params["nonce"] ?: return null
                return Challenge(
                    realm = realm,
                    nonce = nonce,
                    qop = params["qop"],
                    opaque = params["opaque"],
                    algorithm = params["algorithm"],
                )
            }
        }
    }

    /**
     * Builds the `Authorization` header value.
     *
     * [cnonce] is injectable so the qop path is testable; leave it null outside tests.
     */
    fun authorization(
        challenge: Challenge,
        method: String,
        uri: String,
        password: String,
        username: String = DEFAULT_USERNAME,
        cnonce: String? = null,
        nonceCount: Int = 1,
    ): String {
        val ha1 = md5(username + ":" + challenge.realm + ":" + password)
        val ha2 = md5(method + ":" + uri)

        val parts = mutableListOf(
            "username=\"" + username + "\"",
            "realm=\"" + challenge.realm + "\"",
            "nonce=\"" + challenge.nonce + "\"",
            "uri=\"" + uri + "\"",
        )

        val response: String
        // The Apple TV tested offers no qop, i.e. the simpler RFC 2069 form.
        // The qop branch is implemented anyway because other receivers do send it.
        val qop = challenge.qop
            ?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }

        if (qop == null) {
            response = md5(ha1 + ":" + challenge.nonce + ":" + ha2)
        } else {
            val nc = String.format("%08x", nonceCount)
            val client = cnonce ?: randomCnonce()
            response = md5(
                ha1 + ":" + challenge.nonce + ":" + nc + ":" + client + ":" + qop + ":" + ha2
            )
            parts += "qop=" + qop
            parts += "nc=" + nc
            parts += "cnonce=\"" + client + "\""
        }

        parts += "response=\"" + response + "\""
        challenge.opaque?.let { parts += "opaque=\"" + it + "\"" }

        return "Digest " + parts.joinToString(", ")
    }

    private fun randomCnonce(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }
}
