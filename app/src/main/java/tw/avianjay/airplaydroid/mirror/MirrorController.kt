package tw.avianjay.airplaydroid.mirror

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.MirrorTransport
import tw.avianjay.airplaydroid.service.MirrorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class MirrorUiState(
    val device: AirPlayDevice? = null,
    val phase: Phase = Phase.Idle,
    val error: String? = null,
) {
    enum class Phase { Idle, AwaitingConsent, AwaitingPin, Pairing, Connecting, Mirroring }

    val active: Boolean get() = phase != Phase.Idle
}

/** What tapping a device in the picker should do; decided without I/O. */
sealed interface MirrorTap {
    data class Refused(val message: String) : MirrorTap
    data class Busy(val current: AirPlayDevice) : MirrorTap
    data object AskPassword : MirrorTap
    data object Start : MirrorTap
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
     * Two protocols reach this point, and which one applies is decided by whether
     * the receiver advertises HAP pairing -- not by its model name:
     *
     *  - **HAP** receivers use the AirPlay 2 path ([MirrorSession]).
     *  - **Everything else that advertises screen mirroring** uses the legacy
     *    path ([LegacyMirrorSessionFactory]): FairPlay SAP, then port-7100
     *    `/stream`.
     *
     * A legacy receiver is therefore no longer refused. It used to be, with a
     * message saying this app could not mirror to it.
     *
     * A receiver that wants an on-screen PIN (bit 9), or one with no access
     * control at all (transient pairing), still needs a pairing flow that has not
     * been built for the HAP path; the legacy path has the same gap.
     */
    fun refusalFor(device: AirPlayDevice, hasSavedPairing: Boolean): String? {
        val txt = device.airPlayTxt ?: return when {
            // An AirPort Express and similar speakers advertise only _raop._tcp:
            // their _airplay._tcp record is not late, it never comes.
            device.raopTxt?.features?.supportsScreenMirroring == false ->
                "${device.displayName} does not accept screen mirroring."
            else -> "${device.displayName} has not been fully resolved yet."
        }
        if (!txt.features.supportsScreenMirroring) return "${device.displayName} does not accept screen mirroring."
        if (txt.pairingBlocked) return "${device.displayName} only allows devices from its own Home."
        if (device.videoEndpoint == null) return "No address for ${device.displayName} yet."
        // No HAP pairing means the legacy protocol, which this app now speaks.
        // Nothing to refuse here: `usesLegacyPath` picks the transport.
        if (hasSavedPairing) return null
        // Password receivers pair with the password, PIN receivers (bits 3, 9) with
        // a code shown on screen, and the rest transiently.
        return null
    }

    /**
     * Whether [device] needs the legacy (AirPlay 1) transport.
     *
     * Delegates to [MirrorTransport] so the picker's badge, this decision and the
     * service's choice of session cannot disagree.
     */
    fun usesLegacyPath(device: AirPlayDevice): Boolean = MirrorTransport.isLegacy(device)

    /**
     * What a tap on [device] should do, given its saved pairing and the session
     * the screen is currently drawing ([mirror] is passed in rather than read
     * from [state] so the decision matches what the user sees).
     *
     * A refusal wins over [MirrorTap.Busy]: it is a fact about the device that
     * stays true after stopping, so "stop the current session first" would send
     * the user off for nothing. A password is asked for only when the receiver
     * wants one and none is saved -- a saved pairing alone does not carry Digest.
     */
    fun tapActionFor(device: AirPlayDevice, saved: PairingStore.Summary?, mirror: MirrorUiState): MirrorTap {
        refusalFor(device, saved != null)?.let { return MirrorTap.Refused(it) }
        if (mirror.active) return MirrorTap.Busy(mirror.device ?: device)
        if (wantsPassword(device) && saved?.hasPassword != true) return MirrorTap.AskPassword
        return MirrorTap.Start
    }

    /**
     * Receivers that refused a connection without a password even though their
     * advertisement said none was needed -- TXT records are read once per
     * discovery round, so they go stale when the password is switched on. Kept
     * for the process so the next tap asks for the password instead of repeating
     * the same refusal.
     */
    private val refusedTransient = ConcurrentHashMap.newKeySet<String>()

    internal fun markNeedsPassword(deviceKey: String) {
        refusedTransient += deviceKey
    }

    /**
     * Whether [device] needs its AirPlay password. The one rule shared by the
     * picker (whether to ask) and the service (persistent vs transient pairing):
     * the TXT says so (`pw` or `flags` bit 7), or a transient attempt was refused.
     */
    fun wantsPassword(device: AirPlayDevice): Boolean =
        device.airPlayTxt?.passwordRequired == true || device.key in refusedTransient

    /**
     * Starts a mirroring request, or returns false if one is already under way:
     * a second request would orphan the running session behind a new UI state.
     */
    fun request(device: AirPlayDevice, password: String?): Boolean {
        var accepted = false
        _state.update {
            if (it.active) it else {
                accepted = true
                MirrorUiState(device = device, phase = MirrorUiState.Phase.AwaitingConsent)
            }
        }
        if (accepted) pendingPassword = password
        return accepted
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

    /**
     * Progress from the service. Ignored once the session has ended: the setup
     * worker can report a phase after finish() already moved the state to Idle,
     * and that late update must not resurrect a session nothing is running.
     */
    internal fun setPhase(phase: MirrorUiState.Phase) {
        _state.update { if (it.phase == MirrorUiState.Phase.Idle) it else it.copy(phase = phase, error = null) }
    }

    @Volatile private var pinReply: CompletableFuture<String?>? = null

    /**
     * Blocks the service's setup thread until the user enters the code the
     * receiver is showing, or gives up (null: cancelled, stopped, or [timeoutMs]
     * passed). The picker shows its PIN dialog while the phase is AwaitingPin.
     */
    internal fun awaitPin(timeoutMs: Long): String? {
        val reply = CompletableFuture<String?>()
        pinReply = reply
        setPhase(MirrorUiState.Phase.AwaitingPin)
        return try {
            reply.get(timeoutMs, TimeUnit.MILLISECONDS)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: TimeoutException) {
            null
        } finally {
            pinReply = null
        }
    }

    fun submitPin(pin: String) {
        pinReply?.complete(pin)
    }

    internal fun ended(error: String?) {
        // Nothing may stay blocked on a PIN for a session that is over.
        pinReply?.complete(null)
        _state.update { MirrorUiState(device = it.device.takeIf { error != null }, error = error) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, device = if (it.active) it.device else null) }
    }
}
