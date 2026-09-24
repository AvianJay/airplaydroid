package tw.avianjay.airplaydroid.protocol

/**
 * The 64-bit AirPlay `features` / `ft` bitmask advertised in mDNS TXT records.
 *
 * The wire format is the fiddly part. It is serialised either as a single 32-bit
 * hex value:
 *
 *     features=0x5A7FFFF7
 *
 * or as a comma-separated pair, in which the **low word comes first**:
 *
 *     features=0x7F8AD0,0x38BCB46
 *              ^^^^^^^^ ^^^^^^^^^
 *              low 32   high 32
 *
 * Getting that order backwards silently misreads every capability.
 *
 * Bit meanings below were derived by reading the code where receivers CONSTRUCT
 * their own advertisement -- a receiver declaring its own capabilities is
 * authoritative in a way third-party bit tables are not -- and cross-checked
 * against the parsing side of shipping senders. Citations name the file so a
 * future reader can re-verify rather than trust this comment.
 */
@JvmInline
value class AirPlayFeatures(val raw: ULong) {

    fun hasBit(index: Int): Boolean {
        require(index in 0..63) { "feature bit index out of range: " + index }
        return (raw shr index) and 1uL == 1uL
    }

    // ------------------------------------------------------------ video

    /** Bit 0 - SupportsAirPlayVideoV1. UxPlay sets it exactly when its /play handlers are on. */
    val supportsAirPlayVideoV1: Boolean get() = hasBit(0)

    /** Bit 49 - SupportsAirPlayVideoV2. Modern third-party AirPlay 2 TVs set this and CLEAR bit 0. */
    val supportsAirPlayVideoV2: Boolean get() = hasBit(49)

    /**
     * Can this receiver accept a video URL handoff (POST /play)?
     *
     * VERIFIED against shipping sender logic, not inferred: pyatv gates
     * FeatureName.PlayUrl on `SupportsAirPlayVideoV1 or SupportsAirPlayVideoV2`
     * (pyatv/protocols/airplay/__init__.py). Testing bit 0 alone would hide
     * every modern Roku/Samsung-class receiver.
     */
    val supportsVideoUrl: Boolean get() = supportsAirPlayVideoV1 || supportsAirPlayVideoV2

    /** Bit 4 - HLS/m3u8 URLs. Independently required on top of the video bits for HLS content. */
    val supportsHls: Boolean get() = hasBit(4)

    /** Bit 1 - SupportsAirPlayPhoto. Dropped by tvOS 14. */
    val supportsPhoto: Boolean get() = hasBit(1)

    /** Bit 7 - SupportsScreenMirroring. Permanently out of scope; never use as a video test. */
    val supportsScreenMirroring: Boolean get() = hasBit(7)

    // ------------------------------------------------------------ audio

    /**
     * Bit 9 - SupportsAirPlayAudio.
     *
     * Gate `_airplay._tcp` audio on this. Do NOT gate `_raop._tcp` on it:
     * classic AirPlay 1 RAOP advertisements carry no `ft` key at all.
     */
    val supportsAudio: Boolean get() = hasBit(9)

    /**
     * Bit 30 - HasUnifiedAdvertiserInfo. When set, the RAOP audio endpoint is
     * reachable on the `_airplay._tcp` service itself and a separate
     * `_raop._tcp` advertisement is not required.
     * (UxPlay: "RAOP support: with this bit set, the AirTunes service is not required.")
     */
    val hasUnifiedAdvertiserInfo: Boolean get() = hasBit(30)

    // ------------------------------------------------------------ pairing

    /** Bit 27 - SupportsLegacyPairing: /pair-setup carries raw AirPlay 1 blobs, not HAP TLV8. */
    val supportsLegacyPairing: Boolean get() = hasBit(27)

    /** Bit 38 - SupportsUnifiedMediaControl. One of the two AirPlay 2 indicators. */
    val supportsUnifiedMediaControl: Boolean get() = hasBit(38)

    /** Bit 43 - SupportsSystemPairing. */
    val supportsSystemPairing: Boolean get() = hasBit(43)

    /** Bit 46 - SupportsHKPairingAndAccessControl. */
    val supportsHomeKitPairing: Boolean get() = hasBit(46)

    /** Bit 48 - SupportsCoreUtilsPairingAndEncryption. The other AirPlay 2 indicator. */
    val supportsCoreUtilsPairing: Boolean get() = hasBit(48)

    /**
     * Is this an AirPlay 2 receiver? Such a device requires HomeKit pairing
     * before ANY session -- including a bare POST /play -- so milestone 3 must
     * gate on this or it will fail against the most common modern hardware.
     */
    val isAirPlay2: Boolean get() = supportsUnifiedMediaControl || supportsCoreUtilsPairing

    val isEmpty: Boolean get() = raw == 0uL

    override fun toString(): String = "AirPlayFeatures(0x" + raw.toString(16).uppercase() + ")"

    companion object {
        val NONE = AirPlayFeatures(0uL)

        /**
         * Parses the TXT value. Returns [NONE] for null, blank, or malformed
         * input -- discovery must never crash because one receiver advertises
         * something odd.
         */
        fun parse(value: String?): AirPlayFeatures {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return NONE

            val parts = text.split(',')
            if (parts.size > 2) return NONE
            val low = parseWord(parts.getOrNull(0)) ?: return NONE
            val high = if (parts.size == 2) (parseWord(parts[1]) ?: return NONE) else 0uL

            return AirPlayFeatures((high shl 32) or low)
        }

        /**
         * Follows pyatv's grammar exactly: `^0x([0-9A-Fa-f]{1,8})(,0x([0-9A-Fa-f]{1,8}))?$`.
         * Over-long words are REJECTED rather than masked -- masking turns a
         * malformed value into a plausible-looking one that then drives wrong badges.
         */
        private fun parseWord(word: String?): ULong? {
            val w = word?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
            if (w.isEmpty() || w.length > 8) return null
            return w.toULongOrNull(16)
        }
    }
}
