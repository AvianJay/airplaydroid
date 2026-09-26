package tw.avianjay.airplaydroid

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.mirror.LegacyVideoKeyStore
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackController
import tw.avianjay.airplaydroid.protocol.AddressLookup
import tw.avianjay.airplaydroid.ui.AirPlayDroidTheme
import tw.avianjay.airplaydroid.ui.DevicePickerScreen
import tw.avianjay.airplaydroid.ui.SettingsScreen
import tw.avianjay.airplaydroid.update.Updater

class MainActivity : MirrorHostActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Intentionally ignored. A denial only hides the mirroring
            // notification and its Stop action; mirroring still runs.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Left behind by the discovery service that used to run in the
        // foreground; channels survive app upgrades, so it would otherwise
        // linger in the app's notification settings.
        getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(LEGACY_DISCOVERY_CHANNEL)

        discoverWhileStarted()
        requestNotificationPermissionIfNeeded()

        setContent {
            AirPlayDroidTheme {
                val discovery by DiscoveryRepository.state.collectAsStateWithLifecycle()
                val playback by PlaybackController.state.collectAsStateWithLifecycle()
                val mirror by MirrorController.state.collectAsStateWithLifecycle()
                val pairingRevision by PairingStore.revision.collectAsStateWithLifecycle()
                val legacyKeyRevision by LegacyVideoKeyStore.revision.collectAsStateWithLifecycle()
                val settings by settingsStore.state.collectAsStateWithLifecycle()
                val updateState by Updater.state.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                // Records the installed versionCode once, and re-checks whenever
                // the channel changes -- so switching to nightly immediately
                // offers the nightly instead of waiting for the next visit.
                LaunchedEffect(settings.updateChannel) {
                    Updater.recordInstalled(applicationContext)
                    if (!settings.updateChannel.isOff) {
                        Updater.check(applicationContext, settings.updateChannel)
                    }
                }

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
                        onUpdateChannel = settingsStore::setUpdateChannel,
                        updateState = updateState,
                        onCheckForUpdates = {
                            Updater.check(applicationContext, settings.updateChannel)
                        },
                        onInstallUpdate = {
                            updateState.release?.let { Updater.downloadAndInstall(this, it) }
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    DevicePickerScreen(
                        state = discovery,
                        playback = playback,
                        mirror = mirror,
                        pairings = saved,
                        snackbarHostState = snackbarHostState,
                        onMirror = ::startMirroring,
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
