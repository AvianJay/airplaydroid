package tw.avianjay.airplaydroid.protocol

/** A resolved network endpoint. Host and port always come from the SRV record. */
data class Endpoint(val host: String, val port: Int) {
    override fun toString(): String = host + ":" + port
}

/**
 * One physical receiver, merged from its `_airplay._tcp` and `_raop._tcp`
 * advertisements so a single Apple TV renders as a single row.
 *
 * [key] is the stable merge key: the TXT `deviceid` when available, otherwise the
 * hex prefix of the `_raop._tcp` instance name. Both are the device MAC, so they
 * agree -- which is what lets the two service types collapse into one device.
 */
data class AirPlayDevice(
    val key: String,
    val displayName: String,
    val airPlayEndpoint: Endpoint? = null,
    val raopEndpoint: Endpoint? = null,
    val airPlayTxt: AirPlayTxt? = null,
    val raopTxt: RaopTxt? = null,
) {
    val model: String?
        get() = airPlayTxt?.model ?: raopTxt?.model

    /**
     * Display-only summary. Protocol decisions must read the features of the
     * SERVICE being driven -- `ft` from [raopTxt] for an audio session,
     * `features` from [airPlayTxt] for /play -- because the two words are not
     * required to be identical.
     */
    val features: AirPlayFeatures
        get() = airPlayTxt?.features?.takeIf { !it.isEmpty }
            ?: raopTxt?.features
            ?: AirPlayFeatures.NONE

    val capabilities: List<DeviceCapability>
        get() = Capabilities.resolve(airPlayTxt, raopTxt)

    /**
     * RTSP audio -- AirPlay 1 AND AirPlay 2 -- is spoken on the `_raop._tcp`
     * port. The AirPlay 2 audio path does NOT move to the `_airplay._tcp` port;
     * a sender opens the RAOP port and then selects V1 or V2 over that socket.
     */
    val audioEndpoint: Endpoint?
        get() = raopEndpoint ?: airPlayEndpoint

    /** POST /play video-URL handoff is spoken on the `_airplay._tcp` port. */
    val videoEndpoint: Endpoint?
        get() = airPlayEndpoint

    /** Display and reachability probing only -- do not drive a protocol from this. */
    val primaryEndpoint: Endpoint?
        get() = airPlayEndpoint ?: raopEndpoint

    /** Merges another view of the same device, preferring newly-resolved data. */
    fun mergeWith(other: AirPlayDevice): AirPlayDevice = copy(
        displayName = other.displayName.ifBlank { displayName },
        airPlayEndpoint = other.airPlayEndpoint ?: airPlayEndpoint,
        raopEndpoint = other.raopEndpoint ?: raopEndpoint,
        airPlayTxt = other.airPlayTxt ?: airPlayTxt,
        raopTxt = other.raopTxt ?: raopTxt,
    )
}
