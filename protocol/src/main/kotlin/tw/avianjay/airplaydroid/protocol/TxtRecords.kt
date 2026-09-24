package tw.avianjay.airplaydroid.protocol

/**
 * Decoding of mDNS TXT records into typed values.
 *
 * Note the input type: Android's `NsdServiceInfo.getAttributes()` hands back
 * `Map<String, byte[]>`, not strings, and a valueless TXT key maps to a null
 * value. Everything here is null-tolerant by design -- a receiver advertising
 * something unexpected must degrade a badge, never crash discovery.
 */
object TxtRecords {

    fun text(attributes: Map<String, ByteArray?>, key: String): String? {
        val exact = attributes[key]
        if (exact != null) return String(exact, Charsets.UTF_8).trim().ifEmpty { null }
        // TXT keys are case-insensitive per RFC 6763.
        val entry = attributes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        return entry?.value?.let { String(it, Charsets.UTF_8).trim().ifEmpty { null } }
    }

    fun flag(attributes: Map<String, ByteArray?>, key: String): Boolean {
        val v = text(attributes, key) ?: return false
        return v.equals("true", ignoreCase = true) || v == "1"
    }

    /**
     * `pw` polarity is deliberately different from [flag]: anything that is not
     * literally "false" counts as a password being set, because receivers spell
     * it inconsistently (`pw=1`, `pw=true`, valueless `pw`).
     */
    fun passwordFlag(attributes: Map<String, ByteArray?>, key: String): Boolean {
        val v = text(attributes, key) ?: return false
        return !v.equals("false", ignoreCase = true)
    }

    fun intList(value: String?): List<Int> =
        value?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()

    fun stringList(value: String?): List<String> =
        value?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun readable(attributes: Map<String, ByteArray?>): Map<String, String?> =
        attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } }

    /** Normalises a device id / MAC to an uppercase hex string with no separators. */
    fun normalizeDeviceKey(value: String?): String? =
        value?.replace(":", "")
            ?.replace("-", "")
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
}

/** TXT record of an `_airplay._tcp` service. */
data class AirPlayTxt(
    val deviceId: String?,
    val model: String?,
    val sourceVersion: String?,
    val osVersion: String?,
    val features: AirPlayFeatures,
    val flags: StatusFlags,
    val publicKey: String?,
    /** `pi` - HAP pairing identifier. pair-verify M2 returns an Identifier that must equal this. */
    val pairingId: String?,
    /** `acl` - access control level. "1" means same-Home devices only: we can never pair. */
    val accessControlLevel: String?,
    /** `act` - access control type. "2" means "Current User", which we cannot satisfy. */
    val accessControlType: String?,
    /** `pw` OR status bit 7 -- a receiver may signal a password with either. */
    val passwordRequired: Boolean,
    val raw: Map<String, String?>,
) {
    /**
     * The receiver's access control makes pairing impossible for a third-party
     * sender. There is no flag bit or feature bit for this -- these two TXT keys
     * are the only signal, and a picker that ignores them will offer devices
     * that can never be driven.
     */
    val pairingBlocked: Boolean
        get() = accessControlLevel == "1" || accessControlType == "2"

    companion object {
        fun parse(attributes: Map<String, ByteArray?>): AirPlayTxt = AirPlayTxt(
            deviceId = TxtRecords.text(attributes, "deviceid"),
            model = TxtRecords.text(attributes, "model"),
            sourceVersion = TxtRecords.text(attributes, "srcvers"),
            osVersion = TxtRecords.text(attributes, "osvers"),
            features = AirPlayFeatures.parse(TxtRecords.text(attributes, "features")),
            flags = StatusFlags.parse(TxtRecords.text(attributes, "flags")),
            publicKey = TxtRecords.text(attributes, "pk"),
            pairingId = TxtRecords.text(attributes, "pi"),
            accessControlLevel = TxtRecords.text(attributes, "acl"),
            accessControlType = TxtRecords.text(attributes, "act"),
            passwordRequired = TxtRecords.passwordFlag(attributes, "pw") ||
                StatusFlags.parse(TxtRecords.text(attributes, "flags")).passwordRequired,
            raw = TxtRecords.readable(attributes),
        )
    }
}

/** TXT record of a `_raop._tcp` (AirTunes) service. */
data class RaopTxt(
    val model: String?,
    val features: AirPlayFeatures,
    val flags: StatusFlags,
    /** `et` - encryption types. 0=none, 1=RSA, 3=FairPlay, 4=MFiSAP, 5=FairPlay SAPv2.5 */
    val encryptionTypes: List<Int>,
    /** `cn` - audio codecs. 0=PCM, 1=ALAC, 2=AAC, 3=AAC-ELD */
    val audioCodecs: List<Int>,
    val metadataTypes: List<Int>,
    /** `tp` - transports, e.g. "TCP,UDP" or "UDP". */
    val transports: List<String>,
    /** `ek` - THIS, not `et`, is what a real sender uses to decide whether to encrypt. */
    val encryptStream: Boolean,
    val passwordRequired: Boolean,
    /** `sr`, `ch`, `ss` feed the ANNOUNCE fmtp line directly; defaults matter. */
    val sampleRate: Int,
    val channels: Int,
    val sampleSizeBits: Int,
    val publicKey: String?,
    val raw: Map<String, String?>,
) {
    val supportsAlac: Boolean get() = audioCodecs.contains(1)

    /**
     * `et` contains 4 (MFiSAP): POST /auth-setup must precede ANNOUNCE or the
     * ANNOUNCE may be answered 403. This is a real, actionable requirement --
     * unlike the FairPlay values, which a third-party sender simply ignores.
     */
    val requiresAuthSetup: Boolean get() = encryptionTypes.contains(4)

    /**
     * `et` contains 0: the stream may be sent unencrypted, which is what every
     * open-source sender actually does. True for every receiver observed in the
     * wild, which is precisely why "FairPlay only" is not a real device state.
     */
    val supportsUnencrypted: Boolean get() = encryptionTypes.isEmpty() || encryptionTypes.contains(0)

    companion object {
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_SAMPLE_SIZE_BITS = 16

        fun parse(attributes: Map<String, ByteArray?>): RaopTxt = RaopTxt(
            model = TxtRecords.text(attributes, "am"),
            features = AirPlayFeatures.parse(TxtRecords.text(attributes, "ft")),
            flags = StatusFlags.parse(TxtRecords.text(attributes, "sf")),
            encryptionTypes = TxtRecords.intList(TxtRecords.text(attributes, "et")),
            audioCodecs = TxtRecords.intList(TxtRecords.text(attributes, "cn")),
            metadataTypes = TxtRecords.intList(TxtRecords.text(attributes, "md")),
            transports = TxtRecords.stringList(TxtRecords.text(attributes, "tp")),
            encryptStream = TxtRecords.text(attributes, "ek") == "1",
            passwordRequired = TxtRecords.passwordFlag(attributes, "pw") ||
                StatusFlags.parse(TxtRecords.text(attributes, "sf")).passwordRequired,
            sampleRate = TxtRecords.text(attributes, "sr")?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE,
            channels = TxtRecords.text(attributes, "ch")?.toIntOrNull() ?: DEFAULT_CHANNELS,
            sampleSizeBits = TxtRecords.text(attributes, "ss")?.toIntOrNull()
                ?: DEFAULT_SAMPLE_SIZE_BITS,
            publicKey = TxtRecords.text(attributes, "pk"),
            raw = TxtRecords.readable(attributes),
        )
    }
}

/**
 * `_raop._tcp` instance names are `AABBCCDDEEFF@FriendlyName`, where the 12 hex
 * digits are the device id with separators stripped. This matters: it means the
 * merge key and the display name survive even if TXT resolution returns nothing.
 */
data class RaopInstanceName(val deviceKey: String?, val displayName: String) {
    companion object {
        fun parse(serviceName: String): RaopInstanceName {
            val at = serviceName.indexOf('@')
            if (at <= 0 || at == serviceName.lastIndex) {
                return RaopInstanceName(null, serviceName)
            }
            val prefix = serviceName.substring(0, at)
            val key = TxtRecords.normalizeDeviceKey(prefix)
                ?.takeIf { it.length == 12 && it.all { c -> c.isDigit() || c in 'A'..'F' } }
            // A non-MAC prefix is not a separator: keep the whole string as the name.
            return if (key == null) {
                RaopInstanceName(null, serviceName)
            } else {
                RaopInstanceName(key, serviceName.substring(at + 1))
            }
        }
    }
}
