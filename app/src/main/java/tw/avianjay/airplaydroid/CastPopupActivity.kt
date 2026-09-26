package tw.avianjay.airplaydroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.mirror.LegacyVideoKeyStore
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.ui.AirPlayDroidTheme
import tw.avianjay.airplaydroid.ui.CastPopupScreen
import tw.avianjay.airplaydroid.ui.PopupScrim

/**
 * The casting popup: the device list, floating over whatever the user was doing,
 * opened from the Quick Settings tile.
 *
 * A translucent, dialog-themed activity rather than a `Dialog` or a system
 * overlay. Three reasons, in order of how much they constrain the design:
 *
 *  1. **Screen-capture consent cannot be requested from a background app.** The
 *     consent prompt is a real activity launched for result, so whoever asks for
 *     it must be an activity in the foreground. A `TileService` is a service and
 *     a system overlay is not an activity either, so both would fail at the one
 *     step this whole feature exists to reach.
 *  2. **The user's app must stay behind it.** The theme is translucent, so the
 *     popup reads as a dialog over the current screen. A separate task with
 *     `excludeFromRecents` keeps it out of the recents list, so closing it
 *     returns to the app underneath rather than to a new entry in the switcher.
 *  3. **Nothing here needs a service.** The tile starts this activity; the
 *     session itself is still [tw.avianjay.airplaydroid.service.MirrorService]'s,
 *     which is what keeps mirroring alive after the popup is gone.
 *
 * Closing the popup is *not* stopping the session: mirroring continues behind it
 * exactly as it does when the picker is closed. Stop is the button in the popup
 * and the action on the mirroring notification.
 */
class CastPopupActivity : MirrorHostActivity() {

    // A dialog over another app: the system bars keep that app's own colour, and
    // enableEdgeToEdge would repaint them for this activity instead.
    override val edgeToEdge: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Discovery for the popup's own lifetime, so the list is live while it is
        // open and nothing scans once it is gone.
        discoverWhileStarted()

        setContent {
            AirPlayDroidTheme {
                val discovery by DiscoveryRepository.state.collectAsStateWithLifecycle()
                val mirror by MirrorController.state.collectAsStateWithLifecycle()
                val pairingRevision by PairingStore.revision.collectAsStateWithLifecycle()
                val settings by settingsStore.state.collectAsStateWithLifecycle()
                val legacyKeyRevision by LegacyVideoKeyStore.revision.collectAsStateWithLifecycle()

                // Local refusals (a tap that cannot be honoured) and the
                // controller's asynchronous failures share one slot, because the
                // popup has no snackbar host and only one line to say it in.
                var error by rememberSaveable { mutableStateOf<String?>(null) }

                // The controller keeps its error until something dismisses it, so
                // it is adopted here and cleared there in the same step: leaving
                // it set would show the same message again on the next popup.
                LaunchedEffect(mirror.error) {
                    mirror.error?.let { message ->
                        error = message
                        MirrorController.dismissError()
                    }
                }

                val deviceKeys = discovery.devices.map { it.key }
                val saved = remember(deviceKeys, pairingRevision) {
                    deviceKeys.mapNotNull { key -> pairings.summary(key)?.let { key to it } }.toMap()
                }
                val legacyKeySeeds = remember(deviceKeys, legacyKeyRevision, settings.defaultLegacyKeySeed) {
                    deviceKeys.associateWith { legacyKeys.get(it, settings.defaultLegacyKeySeed) }
                }

                // Back closes the popup, the way tapping outside it does.
                BackHandler { finish() }

                PopupScrim(onDismiss = { finish() }) {
                    CastPopupScreen(
                        state = discovery,
                        mirror = mirror,
                        pairings = saved,
                        errorMessage = error,
                        onMirror = { device, password ->
                            // The previous attempt's failure no longer applies.
                            error = null
                            startMirroring(device, password)
                        },
                        onStopMirror = { MirrorController.stop(this) },
                        onRefused = { error = it },
                        onOpenApp = ::openApp,
                        onDismiss = { finish() },
                        legacyKeySeeds = legacyKeySeeds,
                    )
                }
            }
        }
    }

    /** The full app, for everything the popup deliberately leaves out. */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        /**
         * The popup's intent. `NEW_TASK` because it is started from a tile, and
         * `EXCLUDE_FROM_RECENTS` so closing it returns to the app underneath
         * rather than leaving an entry in the switcher. `MULTIPLE_TASK` is
         * deliberately absent: a second tap on the tile raises the popup that is
         * already open instead of stacking another one on top of it.
         */
        fun intent(context: Context): Intent =
            Intent(context, CastPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
