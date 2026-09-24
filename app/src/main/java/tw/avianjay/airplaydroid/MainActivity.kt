package tw.avianjay.airplaydroid

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackController
import tw.avianjay.airplaydroid.service.AirPlaySessionService
import tw.avianjay.airplaydroid.ui.AirPlayDroidTheme
import tw.avianjay.airplaydroid.ui.DevicePickerScreen

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Intentionally ignored. A denial only hides the ongoing notification;
            // discovery and the foreground service are unaffected.
        }

    // Asked right before screen capture. Either answer proceeds to capture:
    // without it, mirroring simply carries no sound.
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
        enableEdgeToEdge()

        AirPlaySessionService.start(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            AirPlayDroidTheme {
                val discovery by DiscoveryRepository.state.collectAsStateWithLifecycle()
                val playback by PlaybackController.state.collectAsStateWithLifecycle()
                val mirror by MirrorController.state.collectAsStateWithLifecycle()
                val pairings = remember { PairingStore(applicationContext) }
                val snackbarHostState = remember { SnackbarHostState() }

                // Playback errors arrive asynchronously from the IO scope, so
                // they are surfaced here rather than at the call site.
                LaunchedEffect(playback.error) {
                    playback.error?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        PlaybackController.dismissError()
                    }
                }

                LaunchedEffect(mirror.error) {
                    mirror.error?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        MirrorController.dismissError()
                    }
                }

                DevicePickerScreen(
                    state = discovery,
                    playback = playback,
                    snackbarHostState = snackbarHostState,
                    onPlay = PlaybackController::play,
                    onTogglePlayPause = PlaybackController::togglePlayPause,
                    onStop = PlaybackController::stop,
                    onSubmitPin = PlaybackController::submitPin,
                    onCancelPin = PlaybackController::cancelPin,
                    onRefused = { message ->
                        // Shown immediately: this is a local decision, no socket involved.
                        PlaybackController.reportRefusal(message)
                    },
                    mirror = mirror,
                    mirrorRefusalFor = { MirrorController.refusalFor(it, pairings.has(it.key)) },
                    hasSavedPairing = { pairings.has(it.key) },
                    onMirror = { device, password ->
                        if (!MirrorController.request(device, password)) {
                            PlaybackController.reportRefusal("Already mirroring. Stop the current session first.")
                            return@DevicePickerScreen
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
                    },
                    onStopMirror = { MirrorController.stop(this) },
                )
            }
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        requestScreenCapture.launch(manager.createScreenCaptureIntent())
    }

    /** Requested in context on first launch; never gates discovery. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
