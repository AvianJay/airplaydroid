package tw.avianjay.airplaydroid

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.discovery.NsdDeviceDiscovery
import tw.avianjay.airplaydroid.mirror.LegacyVideoKeyStore
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackController
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.settings.SettingsStore

/**
 * Everything a screen that can start a mirroring session needs from an
 * `Activity`: the two runtime permissions, the screen-capture consent prompt,
 * discovery, and the shared stores.
 *
 * There are two such screens and they are deliberately different -- the picker
 * ([MainActivity]) and the Quick Settings popup
 * ([tw.avianjay.airplaydroid.CastPopupActivity]) -- but a mirroring session may
 * only be started one way, or the two would drift: the popup would forget the
 * audio-permission step, or ask for consent before checking whether a session is
 * already running, and the failure would look like a receiver problem.
 */
abstract class MirrorHostActivity : ComponentActivity() {

    /** The app-wide settings: client name, screen-awake, legacy key, updates. */
    protected val settingsStore by lazy { SettingsStore(applicationContext) }

    /** HomeKit pairings, plus each receiver's AirPlay password. */
    protected val pairings by lazy { PairingStore(applicationContext) }

    /** The per-receiver legacy video key overrides. */
    protected val legacyKeys by lazy { LegacyVideoKeyStore(applicationContext) }

    /**
     * Whether this screen draws behind the system bars. False for the popup,
     * which is a dialog over someone else's screen: there the status and
     * navigation bars must keep the colour of the app underneath, and
     * `enableEdgeToEdge` would repaint them for this activity instead.
     */
    protected open val edgeToEdge: Boolean get() = true

    /**
     * Asked right before screen capture. Either answer proceeds to capture:
     * without it, mirroring simply carries no sound.
     */
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            launchScreenCapture()
        }

    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            MirrorController.onConsent(this, result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (edgeToEdge) enableEdgeToEdge()
    }

    /**
     * Discovery runs only while this activity is at least STARTED, so nothing
     * scans in the background. STARTED rather than RESUMED on purpose: the
     * RECORD_AUDIO and screen-capture prompts are translucent activities that
     * only pause this one, so browsing carries on through the mirror start flow.
     *
     * Overlapping hosts share one round -- see [DiscoveryRepository.collectFrom].
     */
    protected fun discoverWhileStarted() {
        val nsdManager = getSystemService(NsdManager::class.java)
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager unavailable on this device")
            return
        }
        val discovery = NsdDeviceDiscovery(nsdManager)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiscoveryRepository.collectFrom(discovery)
            }
        }
    }

    /**
     * The one way to start mirroring, shared by both hosts.
     *
     * The order matters and is the same everywhere: claim the controller first,
     * so a second tap cannot orphan a running session behind a new UI state,
     * then collect the audio permission, then ask for capture consent. The
     * service is started from [MirrorController.onConsent], once the grant is in
     * hand -- a MediaProjection may only be created from a service already in the
     * foreground as `mediaProjection`.
     */
    protected fun startMirroring(device: AirPlayDevice, password: String?) {
        if (!MirrorController.request(device, password)) {
            PlaybackController.reportRefusal(
                getString(
                    R.string.mirror_already_active,
                    MirrorController.state.value.device?.displayName.orEmpty(),
                )
            )
            return
        }
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        // Playback capture exists from Android 10; asking earlier is pointless.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !audioGranted) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchScreenCapture()
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        requestScreenCapture.launch(manager.createScreenCaptureIntent())
    }

    /**
     * Holds the screen on while [enabled], and releases it the moment it turns
     * false -- when mirroring ends, when the setting is switched off, or when
     * this activity is destroyed.
     *
     * `FLAG_KEEP_SCREEN_ON` on the window rather than a `WakeLock`: it needs no
     * permission, cannot be held by mistake after the activity is gone, and is
     * exactly the scope wanted here, since mirroring is started from a screen
     * that is on screen.
     */
    @Composable
    protected fun KeepScreenAwake(enabled: Boolean) {
        val window = this.window
        DisposableEffect(enabled) {
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private companion object {
        const val TAG = "MirrorHost"
    }
}
