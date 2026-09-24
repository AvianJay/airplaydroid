package tw.avianjay.airplaydroid.mirror

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.service.MirrorService

data class MirrorUiState(
    val device: AirPlayDevice? = null,
    val phase: Phase = Phase.Idle,
    val error: String? = null,
) {
    enum class Phase { Idle, AwaitingConsent, Pairing, Connecting, Mirroring }

    val active: Boolean get() = phase != Phase.Idle
}

/**
 * Screen-mirroring state, process-scoped like the playback controller so it
 * outlives activity recreation. The work itself runs in [MirrorService]: a
 * MediaProjection may only be obtained from a foreground service of type
 * mediaProjection.
 *
 * Flow: [request] records the target, the activity asks for capture consent,
 * [onConsent] hands the grant to the service, and the service reports progress
 * back through the internal setters.
 */
object MirrorController {

    private val _state = MutableStateFlow(MirrorUiState())
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    /** Password typed in the dialog; held only until the service picks it up. */
    @Volatile internal var pendingPassword: String? = null
        private set

    /**
     * Why [device] cannot be mirrored to, or null if it can.
     *
     * Only the path verified on hardware is offered: a receiver in password
     * mode (`flags` bit 7), paired persistently with that password. A receiver
     * that wants an on-screen PIN (bit 9), or one with no access control at all
     * (transient pairing), needs a pairing flow that has not been built yet.
     */
    fun refusalFor(device: AirPlayDevice, hasSavedPairing: Boolean): String? {
        val txt = device.airPlayTxt ?: return "${device.displayName} has not been fully resolved yet."
        if (!txt.features.supportsScreenMirroring) return "${device.displayName} does not accept screen mirroring."
        if (txt.pairingBlocked) return "${device.displayName} only allows devices from its own Home."
        if (device.videoEndpoint == null) return "No address for ${device.displayName} yet."
        if (hasSavedPairing) return null
        if (txt.flags.pairingRequired) {
            return "Mirroring to a receiver that shows a PIN is not supported yet. " +
                "Set its AirPlay access to require a password instead."
        }
        if (!txt.flags.passwordRequired) {
            return "Mirroring currently needs the receiver to have an AirPlay password " +
                "(Settings > AirPlay > Require Password)."
        }
        return null
    }

    fun request(device: AirPlayDevice, password: String?) {
        pendingPassword = password
        _state.value = MirrorUiState(device = device, phase = MirrorUiState.Phase.AwaitingConsent)
    }

    /** The result of the screen-capture consent prompt. */
    fun onConsent(context: Context, resultCode: Int, data: Intent?) {
        val device = _state.value.device
        if (device == null || data == null || resultCode != android.app.Activity.RESULT_OK) {
            pendingPassword = null
            _state.value = MirrorUiState()
            return
        }
        MirrorService.start(context, resultCode, data)
    }

    fun stop(context: Context) {
        when (_state.value.phase) {
            MirrorUiState.Phase.Idle -> Unit
            // No service exists until consent is given.
            MirrorUiState.Phase.AwaitingConsent -> {
                pendingPassword = null
                _state.value = MirrorUiState()
            }
            else -> MirrorService.stop(context)
        }
    }

    internal fun takePassword(): String? = pendingPassword.also { pendingPassword = null }

    internal fun setPhase(phase: MirrorUiState.Phase) {
        _state.update { it.copy(phase = phase, error = null) }
    }

    internal fun ended(error: String?) {
        _state.update { MirrorUiState(device = it.device.takeIf { error != null }, error = error) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, device = if (it.active) it.device else null) }
    }
}
