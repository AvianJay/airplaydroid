package tw.avianjay.airplaydroid.discovery

import kotlinx.coroutines.flow.Flow
import tw.avianjay.airplaydroid.protocol.AirPlayDevice

/**
 * The two mDNS service types an AirPlay sender cares about. A single Apple TV
 * advertises both; they are merged into one [AirPlayDevice] downstream.
 */
enum class AirPlayServiceType(val mdnsType: String, val label: String) {
    AirPlay("_airplay._tcp", "AirPlay"),
    Raop("_raop._tcp", "RAOP"),
}

sealed interface DiscoveryEvent {
    data class ScanStarted(val type: AirPlayServiceType) : DiscoveryEvent
    data class ScanStopped(val type: AirPlayServiceType) : DiscoveryEvent
    data class ServiceResolved(val type: AirPlayServiceType, val device: AirPlayDevice) : DiscoveryEvent
    data class ServiceLost(val type: AirPlayServiceType, val key: String) : DiscoveryEvent
    data class Failure(val type: AirPlayServiceType, val reason: String) : DiscoveryEvent
}

/**
 * The swap seam. The only implementation today is [NsdDeviceDiscovery]; keeping
 * the interface means the repository and UI never learn about NsdManager.
 */
interface DeviceDiscovery {
    fun events(): Flow<DiscoveryEvent>
}

/** What the picker renders. */
data class DiscoveryUiState(
    val devices: List<AirPlayDevice> = emptyList(),
    /** Types whose NSD browse is running in the current round. */
    val scanning: Set<AirPlayServiceType> = emptySet(),
    val lastError: String? = null,
) {
    val isScanning: Boolean get() = scanning.isNotEmpty()
}
