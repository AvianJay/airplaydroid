package tw.avianjay.airplaydroid

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.discovery.NsdDeviceDiscovery
import tw.avianjay.airplaydroid.mirror.LegacyVideoKeyStore
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackController
import tw.avianjay.airplaydroid.protocol.AddressLookup
import tw.avianjay.airplaydroid.settings.SettingsStore
import tw.avianjay.airplaydroid.ui.AirPlayDroidTheme
import tw.avianjay.airplaydroid.ui.DevicePickerScreen
import tw.avianjay.airplaydroid.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Intentionally ignored. A denial only hides the mirroring
            // notification and its Stop action; mirroring still runs.
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

        // Left behind by the discovery service that used to run in the
        // foreground; channels survive app upgrades, so it would otherwise
        // linger in the app's notification settings.
        getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(LEGACY_DISCOVERY_CHANNEL)

        startDiscoveryWhileVisible()
        requestNotificationPermissionIfNeeded()

        setContent {
            AirPlayDroidTheme {
                val discovery by DiscoveryRepository.state.collectAsStateWithLifecycle()
                val playback by PlaybackController.state.collectAsStateWithLifecycle()
                val mirror by MirrorController.state.collectAsStateWithLifecycle()
                val pairingRevision by PairingStore.revision.collectAsStateWithLifecycle()
                val pairings = remember { PairingStore(applicationContext) }
                val legacyKeys = remember { LegacyVideoKeyStore(applicationContext) }
                val legacyKeyRevision by LegacyVideoKeyStore.revision.collectAsStateWithLifecycle()
                val settingsStore = remember { SettingsStore(applicationContext) }
                val settings by settingsStore.state.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                // Re-read only when the set of devices or a saved pairing changes,
                // never per recomposition: each lookup is a small file read on
                // the main thread. Keyed on the keys rather than the devices so a
                // TXT merge does not trigger a re-read.
                val deviceKeys = discovery.devices.map { it.key }
                val saved = remember(deviceKeys, pairingRevision) {
                    deviceKeys.mapNotNull { key -> pairings.summary(key)?.let { key to it } }.toMap()
                }
                // Keyed on the default too: changing it moves every receiver that
                // has no override of its own.
                val legacyKeySeeds = remember(deviceKeys, legacyKeyRevision, settings.defaultLegacyKeySeed) {
                    deviceKeys.associateWith { legacyKeys.get(it, settings.defaultLegacyKeySeed) }
                }

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

                // Held only while a session runs, and only if asked for: the flag
                // is released when mirroring ends, when this activity goes away,
                // or when the setting is turned off mid-session.
                KeepScreenAwake(enabled = settings.keepScreenAwake && mirror.active)

                // System back leaves settings, the way the toolbar arrow does.
                // Enabled only there, so it never swallows a back press on the
                // picker itself.
                BackHandler(enabled = showSettings) { showSettings = false }

                if (showSettings) {
                    SettingsScreen(
                        settings = settings,
                        onClientName = settingsStore::setClientName,
                        onKeepScreenAwake = settingsStore::setKeepScreenAwake,
                        onDefaultLegacyKeySeed = settingsStore::setDefaultLegacyKeySeed,
                        onBack = { showSettings = false },
                    )
                } else {
                    DevicePickerScreen(
                        state = discovery,
                        playback = playback,
                        mirror = mirror,
                        pairings = saved,
                        snackbarHostState = snackbarHostState,
                        onMirror = { device, password ->
                            if (!MirrorController.request(device, password)) {
                                PlaybackController.reportRefusal(
                                    getString(
                                        R.string.mirror_already_active,
                                        MirrorController.state.value.device?.displayName.orEmpty(),
                                    )
                                )
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
                        onForgetPairing = { pairings.forget(it.key) },
                        onPlay = PlaybackController::play,
                        onTogglePlayPause = PlaybackController::togglePlayPause,
                        onStop = PlaybackController::stop,
                        onSubmitPin = PlaybackController::submitPin,
                        onCancelPin = PlaybackController::cancelPin,
                        onRefused = { message ->
                            // Shown immediately: this is a local decision, no socket involved.
                            PlaybackController.reportRefusal(message)
                        },
                        legacyKeySeeds = legacyKeySeeds,
                        onCycleLegacyKey = { legacyKeys.cycle(it.key, settings.defaultLegacyKeySeed) },
                        onOpenSettings = { showSettings = true },
                        onAddByAddress = { host, port ->
                            lifecycleScope.launch {
                                val found = runCatching {
                                    withContext(Dispatchers.IO) { AddressLookup.lookup(host, port) }
                                }
                                found.onSuccess { device ->
                                    DiscoveryRepository.addManual(device)
                                    snackbarHostState.showSnackbar(
                                        getString(R.string.add_address_added, device.displayName)
                                    )
                                }.onFailure { e ->
                                    snackbarHostState.showSnackbar(e.message ?: "No AirPlay receiver at $host:$port")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Discovery runs only while this activity is at least STARTED, so nothing
     * scans in the background. STARTED rather than RESUMED on purpose: the
     * RECORD_AUDIO and screen-capture prompts are translucent activities that
     * only pause this one, so browsing carries on through the mirror start flow.
     */
    private fun startDiscoveryWhileVisible() {
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
     * exactly the scope wanted here, since mirroring is started from the picker
     * that is on screen.
     */
    @Composable
    private fun KeepScreenAwake(enabled: Boolean) {
        val window = this.window
        DisposableEffect(enabled) {
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    /** Requested on first launch; never gates discovery or mirroring. */
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

    private companion object {
        const val TAG = "MainActivity"
        const val LEGACY_DISCOVERY_CHANNEL = "airplay_discovery"
    }
}
