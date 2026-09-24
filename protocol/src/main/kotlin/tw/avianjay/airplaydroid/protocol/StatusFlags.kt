package tw.avianjay.airplaydroid.protocol

/**
 * The `flags` (_airplay._tcp) / `sf` (_raop._tcp) status bitmask. `sf` and
 * `flags` are interchangeable spellings of the same field.
 *
 * The same mask also arrives as the uint64 `statusFlags` key in the GET /info
 * plist, which is the authoritative copy at connect time -- the TXT copy can be
 * stale. Hence [Long] rather than Int.
 *
 * The pairing decision a real sender makes (owntone's /info response handler):
 *   bit 9 set  -> persistent HAP pairing required
 *   bit 3 set  -> PIN pairing
 *   bit 7 set  -> password (the password is the pair-setup credential)
 *   otherwise  -> transient pairing
 *
 * HARDWARE-VERIFIED on 2026-09-15 against four receivers on one LAN:
 *   Apple TV 4K (AppleTV6,2, tvOS 26.6)  flags=0xc4  -> bits 2, 6, 7
 *       Bit 7 confirmed: that receiver really does have "Require Password" on.
 *   MacBook Air / Mac14,2 / Mac17,5      flags=0x204 -> bits 2, 9
 *       Bit 9 confirmed: those require persistent pairing, and carry act=2.
 * Bit 2 is set on every device, confirming it is the universal default rather
 * than meaningful state. The RAOP `sf` value matched `flags` exactly on all
 * four, confirming the two keys are one field.
 */
@JvmInline
value class StatusFlags(val raw: Long) {

    fun hasBit(index: Int): Boolean {
        require(index in 0..63) { "status flag bit index out of range: " + index }
        return (raw ushr index) and 1L == 1L
    }

    /** Bit 0 - a problem was detected on the receiver. */
    val problemsExist: Boolean get() = hasBit(0)

    /** Bit 1 - device is not yet configured (fresh out of the box). */
    val notConfigured: Boolean get() = hasBit(1)

    /**
     * Bit 2 - audio cable attached. This is the universal default: `sf=0x4`
     * alone means "nothing notable", not meaningful state.
     */
    val audioCableAttached: Boolean get() = hasBit(2)

    /** Bit 3 - receiver will display a PIN for pairing. */
    val pinRequired: Boolean get() = hasBit(3)

    /** Bit 6 - supports playback from cloud. */
    val supportsFromCloud: Boolean get() = hasBit(6)

    /** Bit 7 - a password is required. Hardware-verified; see the class KDoc. */
    val passwordRequired: Boolean get() = hasBit(7)

    /**
     * Bit 9 - OneTimePairingRequired: persistent HomeKit pairing must be
     * completed before any session. Hardware-verified on three Macs (flags=0x204).
     */
    val pairingRequired: Boolean get() = hasBit(9)

    /** Bit 10 - setup for HomeKit access control. */
    val setupHKAccessControl: Boolean get() = hasBit(10)

    /** Bit 11 - supports relay. */
    val supportsRelay: Boolean get() = hasBit(11)

    /** Bit 17 - receiver is busy with another sender. */
    val busy: Boolean get() = hasBit(17)

    val isEmpty: Boolean get() = raw == 0L

    override fun toString(): String = "StatusFlags(0x" + raw.toString(16).uppercase() + ")"

    companion object {
        val NONE = StatusFlags(0L)

        fun parse(value: String?): StatusFlags {
            val text = value?.trim()?.removePrefix("0x")?.removePrefix("0X").orEmpty()
            if (text.isEmpty() || text.length > 16) return NONE
            val parsed = text.toULongOrNull(16) ?: return NONE
            return StatusFlags(parsed.toLong())
        }
    }
}
