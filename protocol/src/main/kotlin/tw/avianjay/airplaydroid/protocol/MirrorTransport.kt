package tw.avianjay.airplaydroid.protocol

/**
 * Which mirroring protocol a receiver wants.
 *
 * This is the single place the decision is made. It lives in the pure-Kotlin
 * protocol module rather than the app because it is a fact about the device's
 * advertisement, and three separate places need to agree on it:
 *
 *  - the picker, which shows a badge and decides whether a row is tappable;
 *  - `MirrorController`, which picks the transport;
 *  - `MirrorService`, which opens the session.
 *
 * When the condition was duplicated, a change to one copy would silently leave
 * the others describing a different device -- so the badge could say "Legacy"
 * while the service took the HAP path.
 *
 * ### The rule
 *
 * **Not the model name.** A receiver is legacy when it advertises screen
 * mirroring but *not* HAP pairing. An AppleTV3-class dongle and a modern Apple TV
 * both report a model starting "AppleTV"; what separates them is feature bit 46
 * (HKPairingAndAccessControl).
 */
enum class MirrorTransport {
    /**
     * AirPlay 2: HomeKit pair-verify, then RTSP `SETUP` with stream type 110.
     * The video key comes from the pair-verify secret. No FairPlay.
     */
    HAP,

    /**
     * AirPlay 1: legacy pair-verify and the FairPlay SAP handshake, then RTSP
     * `SETUP` type 110 -- or, for an iOS 6-8 era receiver, port-7100
     * `/stream.xml` and `POST /stream`. The video key is wrapped by FairPlay and
     * the payload is AES-CTR encrypted.
     */
    LEGACY,

    /** The device does not accept screen mirroring at all. */
    UNSUPPORTED,
    ;

    companion object {

        /**
         * The transport for [device].
         *
         * A device whose AirPlay TXT record has not arrived yet is [UNSUPPORTED]
         * rather than guessed at: a RAOP-only speaker genuinely never sends one,
         * and a device that is still resolving will be re-evaluated on the next
         * discovery round.
         */
        fun forDevice(device: AirPlayDevice): MirrorTransport =
            forAirPlayTxt(device.airPlayTxt)

        /**
         * The transport implied by an `_airplay._tcp` record alone.
         *
         * Separate from [forDevice] so callers that only hold the TXT record --
         * the capability badge, for one -- do not have to fabricate a device to
         * ask the question.
         */
        fun forAirPlayTxt(airPlay: AirPlayTxt?): MirrorTransport {
            val features = airPlay?.features ?: return UNSUPPORTED
            if (!features.supportsScreenMirroring) return UNSUPPORTED
            return if (features.supportsHapPairing) HAP else LEGACY
        }

        /** Whether [device] will be mirrored to over the legacy protocol. */
        fun isLegacy(device: AirPlayDevice): Boolean = forDevice(device) == LEGACY

        /** Whether an `_airplay._tcp` record implies the legacy protocol. */
        fun isLegacy(airPlay: AirPlayTxt?): Boolean = forAirPlayTxt(airPlay) == LEGACY
    }
}
