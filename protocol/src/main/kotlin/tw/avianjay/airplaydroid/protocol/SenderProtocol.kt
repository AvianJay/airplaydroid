package tw.avianjay.airplaydroid.protocol

/** Why a device cannot accept a video-URL handoff right now. */
sealed class ConnectionRefusal(message: String) : Exception(message) {

    class NoEndpoint(val deviceName: String) :
        ConnectionRefusal("No resolved endpoint for " + deviceName + " yet.")

    class NeedsPairing(val deviceName: String) :
        ConnectionRefusal(
            deviceName + " is an AirPlay 2 receiver and needs HomeKit pairing first " +
                "(milestone 4), even to accept a video URL."
        )

    class PairingBlocked(val deviceName: String) :
        ConnectionRefusal(
            deviceName + " restricts access control to its own Home, so it can never be paired."
        )

    class NeedsPassword(val deviceName: String) :
        ConnectionRefusal(deviceName + " is password-protected, which is not supported yet.")

    class NoVideoSupport(val deviceName: String) :
        ConnectionRefusal(deviceName + " is an audio-only receiver.")
}

/**
 * Decides whether [AirPlayV1Session] can drive a device, before any socket is opened.
 *
 * These gates are the ones real senders apply. Note what is NOT here: FairPlay.
 * Every receiver ever captured advertises `et` containing 0, and no open-source
 * sender implements FairPlay for the video-URL or audio paths. Pairing is the wall.
 */
object VideoHandoff {

    /** Returns null when the device can accept `POST /play`, else the reason it cannot. */
    fun refusalFor(device: AirPlayDevice): ConnectionRefusal? {
        val airPlay = device.airPlayTxt

        if (airPlay?.pairingBlocked == true) {
            return ConnectionRefusal.PairingBlocked(device.displayName)
        }
        // NOT refused for being AirPlay 2. That gate was removed after an
        // Apple TV 4K (feature bits 38 and 48 set, tvOS 26.6) accepted a bare
        // POST /play over HTTP Digest with no HomeKit pairing at all. Prior
        // research claimed AirPlay 2 receivers demand pairing even for /play;
        // real hardware says otherwise.
        //
        // A password is likewise not a refusal: the session answers the 401
        // challenge itself when one is configured, so the UI asks for it
        // instead of giving up here.
        if (airPlay != null && !airPlay.features.isEmpty && !airPlay.features.supportsVideoUrl) {
            return ConnectionRefusal.NoVideoSupport(device.displayName)
        }
        if (device.videoEndpoint == null) {
            return ConnectionRefusal.NoEndpoint(device.displayName)
        }
        return null
    }

    fun isSupported(device: AirPlayDevice): Boolean = refusalFor(device) == null
}
