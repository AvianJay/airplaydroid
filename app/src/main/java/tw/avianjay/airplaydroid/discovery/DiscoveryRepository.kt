package tw.avianjay.airplaydroid.discovery

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.avianjay.airplaydroid.protocol.AirPlayDevice

/**
 * Holds the merged device list.
 *
 * Discovery runs only while MainActivity is at least STARTED, in rounds: each
 * return to the app starts a fresh NSD browse through [collectFrom]. The list is
 * process-scoped rather than owned by the activity so it outlives both activity
 * recreation and the gap between rounds -- coming back to the app shows the
 * receivers straight away instead of flashing an empty list while they resolve
 * again.
 *
 * A new round can only report a loss for services it resolved itself (see
 * NsdDeviceDiscovery), so a receiver that left while we were not browsing
 * would otherwise stay listed forever. Every carried-over service must
 * therefore be re-confirmed within [RECONFIRM_WINDOW_MS] or it is stripped.
 */
object DiscoveryRepository {

    /**
     * Several of NsdDeviceDiscovery's 8 s resolve timeouts, not one: below API 34
     * resolves run one at a time, so with a few receivers on the network, or one
     * resolve that hangs until its timeout, a receiver that is present can wait
     * well past 8 s for its turn. Too short a window strips it and re-adds it;
     * too long only means a departed receiver lingers a little.
     */
    private const val RECONFIRM_WINDOW_MS = 30_000L

    private val _state = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    private val devices = LinkedHashMap<String, AirPlayDevice>()

    /** Services carried over from an earlier round and not yet seen in this one. */
    private val unconfirmed = HashSet<Pair<String, AirPlayServiceType>>()

    /**
     * Devices added by address. No browse will ever confirm them -- mDNS is
     * exactly what could not reach them -- so they are exempt from the
     * reconfirm sweep and stay until the process ends.
     */
    private val manual = HashSet<String>()

    /**
     * Adds a receiver found by [tw.avianjay.airplaydroid.protocol.AddressLookup]
     * rather than by discovery, or refreshes it if it is already listed.
     */
    @Synchronized
    fun addManual(device: AirPlayDevice) {
        manual += device.key
        unconfirmed.removeAll { it.first == device.key }
        val existing = devices[device.key]
        devices[device.key] = existing?.mergeWith(device) ?: device
        publishDevices()
    }

    /** Runs one discovery round until the caller's scope is cancelled. */
    suspend fun collectFrom(discovery: DeviceDiscovery): Unit = coroutineScope {
        beginRound()
        // Cancelled with the round: backgrounding again inside the window strips
        // nothing, and the next round simply re-marks everything.
        launch {
            delay(RECONFIRM_WINDOW_MS)
            dropUnconfirmed()
        }
        try {
            discovery.events().collect(::apply)
        } finally {
            endRound()
        }
    }

    @Synchronized
    fun apply(event: DiscoveryEvent) {
        when (event) {
            // Not clearing lastError here: one type starting says nothing about
            // the other type's failure, which would vanish from the screen while
            // that type still is not browsing. A new round clears it instead.
            is DiscoveryEvent.ScanStarted -> _state.update {
                it.copy(scanning = it.scanning + event.type)
            }

            is DiscoveryEvent.ScanStopped -> _state.update {
                it.copy(scanning = it.scanning - event.type)
            }

            is DiscoveryEvent.Failure -> _state.update {
                it.copy(scanning = it.scanning - event.type, lastError = event.reason)
            }

            is DiscoveryEvent.ServiceResolved -> {
                unconfirmed.remove(event.device.key to event.type)
                val existing = devices[event.device.key]
                devices[event.device.key] = existing?.mergeWith(event.device) ?: event.device
                publishDevices()
            }

            is DiscoveryEvent.ServiceLost -> {
                unconfirmed.remove(event.key to event.type)
                if (strip(event.key, event.type)) publishDevices()
            }
        }
    }

    @Synchronized
    private fun beginRound() {
        // Every type is retried from scratch, so an earlier round's failure no
        // longer applies.
        _state.update { it.copy(lastError = null) }
        unconfirmed.clear()
        devices.values.filter { it.key !in manual }.forEach { device ->
            if (device.airPlayEndpoint != null) unconfirmed += device.key to AirPlayServiceType.AirPlay
            if (device.raopEndpoint != null) unconfirmed += device.key to AirPlayServiceType.Raop
        }
    }

    @Synchronized
    private fun dropUnconfirmed() {
        // Same result as a real goodbye for each: a device that only lost one
        // service type keeps the other.
        var changed = false
        unconfirmed.forEach { (key, type) -> changed = strip(key, type) || changed }
        unconfirmed.clear()
        if (changed) publishDevices()
    }

    /**
     * The browse was stopped by cancellation. Its onDiscoveryStopped arrives only
     * after the channel has closed, so it cannot be what clears [DiscoveryUiState.scanning].
     */
    @Synchronized
    private fun endRound() {
        _state.update { it.copy(scanning = emptySet()) }
    }

    /** Removes one service type's view of a device; returns whether anything changed. */
    private fun strip(key: String, type: AirPlayServiceType): Boolean {
        val existing = devices[key] ?: return false
        val stripped = when (type) {
            AirPlayServiceType.AirPlay ->
                existing.copy(airPlayEndpoint = null, airPlayTxt = null)

            AirPlayServiceType.Raop ->
                existing.copy(raopEndpoint = null, raopTxt = null)
        }
        if (stripped.airPlayEndpoint == null && stripped.raopEndpoint == null) {
            devices.remove(key)
        } else {
            devices[key] = stripped
        }
        return true
    }

    private fun publishDevices() {
        val snapshot = devices.values.sortedBy { it.displayName.lowercase() }
        _state.update { it.copy(devices = snapshot) }
    }
}
