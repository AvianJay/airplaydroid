package tw.avianjay.airplaydroid.protocol

/**
 * Capability badges shown in the device picker.
 *
 * These are the states a real sender actually branches on, taken from the
 * decision tables in owntone's /info response handler and pyatv's
 * `get_pairing_requirement`. The previous "FairPlay only" badge was deleted: it
 * can never fire, because every receiver ever dumped advertises `et` containing
 * 0 (unencrypted). FairPlay is not what blocks a third-party sender -- pairing is.
 */
enum class DeviceCapability(val label: String) {
    Audio("Audio"),
    Video("Video"),
    AirPlay2("AirPlay 2"),
    /** Speaks the AirPlay 1 mirroring protocol: FairPlay SAP, then RTSP type 110 (or port-7100 /stream). */
    Legacy("Legacy"),
    NeedsPin("Needs PIN"),
    NeedsPassword("Needs password"),
    PairingBlocked("Pairing blocked"),
    Busy("In use"),
}

object Capabilities {

    fun resolve(airPlay: AirPlayTxt?, raop: RaopTxt?): List<DeviceCapability> {
        val result = mutableListOf<DeviceCapability>()

        // A _raop._tcp advertisement is itself the audio capability: classic
        // AirPlay 1 RAOP records carry no feature bits at all, so testing bit 9
        // would hide every AirPort Express.
        val audio = raop != null || airPlay?.features?.supportsAudio == true
        if (audio) result += DeviceCapability.Audio

        if (airPlay?.features?.supportsVideoUrl == true) result += DeviceCapability.Video

        if (airPlay?.features?.isAirPlay2 == true) result += DeviceCapability.AirPlay2

        // Advertises mirroring but not HAP pairing: the legacy protocol. The
        // badge exists so the user can see which transport a tap will use, and it
        // reads the same [MirrorTransport] the service branches on.
        if (MirrorTransport.isLegacy(airPlay)) result += DeviceCapability.Legacy

        if (airPlay?.pairingBlocked == true) result += DeviceCapability.PairingBlocked

        val flags = airPlay?.flags ?: raop?.flags
        if (flags?.pinRequired == true) result += DeviceCapability.NeedsPin

        val password = airPlay?.passwordRequired == true || raop?.passwordRequired == true
        if (password) result += DeviceCapability.NeedsPassword

        if (flags?.busy == true) result += DeviceCapability.Busy

        return result
    }
}
