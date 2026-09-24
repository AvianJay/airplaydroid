package tw.avianjay.airplaydroid.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.avianjay.airplaydroid.protocol.AirPlayDevice

/**
 * Holds the merged device list.
 *
 * This is process-scoped rather than owned by a ViewModel on purpose: the
 * foreground service owns the NsdManager lifecycle (so the list survives activity
 * recreation), and a plain shared holder keeps the UI free of binder plumbing for
 * what is still a skeleton. If session state grows past this, promote it to a
 * bound-service interface -- the UI already only reads [state].
 */
object DiscoveryRepository {

    private val _state = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    private val devices = LinkedHashMap<String, AirPlayDevice>()

    @Synchronized
    fun apply(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.ScanStarted -> _state.value = _state.value.copy(
                scanning = _state.value.scanning + event.type,
                lastError = null,
            )

            is DiscoveryEvent.ScanStopped -> _state.value = _state.value.copy(
                scanning = _state.value.scanning - event.type,
            )

            is DiscoveryEvent.Failure -> _state.value = _state.value.copy(
                scanning = _state.value.scanning - event.type,
                lastError = event.reason,
            )

            is DiscoveryEvent.ServiceResolved -> {
                val existing = devices[event.device.key]
                devices[event.device.key] = existing?.mergeWith(event.device) ?: event.device
                publishDevices()
            }

            is DiscoveryEvent.ServiceLost -> {
                val existing = devices[event.key]
                if (existing != null) {
                    val stripped = when (event.type) {
                        AirPlayServiceType.AirPlay ->
                            existing.copy(airPlayEndpoint = null, airPlayTxt = null)

                        AirPlayServiceType.Raop ->
                            existing.copy(raopEndpoint = null, raopTxt = null)
                    }
                    if (stripped.airPlayEndpoint == null && stripped.raopEndpoint == null) {
                        devices.remove(event.key)
                    } else {
                        devices[event.key] = stripped
                    }
                    publishDevices()
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        devices.clear()
        _state.value = DiscoveryUiState()
    }

    private fun publishDevices() {
        val snapshot = devices.values.sortedBy { it.displayName.lowercase() }
        _state.value = _state.value.copy(
            devices = snapshot,
            counts = mapOf(
                AirPlayServiceType.AirPlay to snapshot.count { it.airPlayEndpoint != null },
                AirPlayServiceType.Raop to snapshot.count { it.raopEndpoint != null },
            ),
        )
    }
}
