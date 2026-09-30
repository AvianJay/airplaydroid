package tw.avianjay.airplaydroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.avianjay.airplaydroid.cast.CastBridge
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackController
import tw.avianjay.airplaydroid.ui.AirPlayDroidTheme
import tw.avianjay.airplaydroid.ui.CastTargetScreen
import tw.avianjay.airplaydroid.ui.PopupScrim

/**
 * The Cast chooser, over whatever app is casting.
 *
 * Started by [tw.avianjay.airplaydroid.cast.CastReceiverService] when a sender
 * connects and the user wants to be asked -- directly when the app may start
 * activities from the background ("display over other apps"), otherwise from
 * the notification the service posts instead.
 *
 * A translucent activity for the same reasons as [CastPopupActivity], and in a
 * task of its own (`taskAffinity=""` in the manifest), so it opens over the
 * sender app rather than bringing this app's own task, and its picker, forward.
 *
 * It closes itself once nothing is left for the user to do: the receiver is
 * chosen and the media is on its way (or not sent yet), or the sender ended the
 * session. Closing it early is not cancelling: the notification brings it back.
 */
class CastTargetActivity : MirrorHostActivity() {

    override val edgeToEdge: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discoverWhileStarted()

        setContent {
            AirPlayDroidTheme {
                val discovery by DiscoveryRepository.state.collectAsStateWithLifecycle()
                val cast by CastBridge.state.collectAsStateWithLifecycle()
                val playback by PlaybackController.state.collectAsStateWithLifecycle()
                val pairingRevision by PairingStore.revision.collectAsStateWithLifecycle()

                val deviceKeys = discovery.devices.map { it.key }
                val saved = remember(deviceKeys, pairingRevision) {
                    deviceKeys.mapNotNull { key -> pairings.summary(key)?.let { key to it } }.toMap()
                }

                LaunchedEffect(cast.sessionActive) {
                    if (!cast.sessionActive) finish()
                }
                // Chosen, and nothing waits on the user any more: no PIN, no error.
                val done = cast.target != null && !cast.choosing && cast.error == null && !playback.awaitingPin &&
                    (playback.connected || !cast.playing)
                LaunchedEffect(done) {
                    if (done) finish()
                }

                BackHandler { finish() }

                PopupScrim(onDismiss = { finish() }) {
                    CastTargetScreen(
                        discovery = discovery,
                        cast = cast,
                        playback = playback,
                        pairings = saved,
                        onChoose = { device, password -> CastBridge.chooseTarget(this, device, password) },
                        onSubmitPin = { PlaybackController.submitPin(applicationContext, it) },
                        onCancelCast = {
                            CastBridge.cancel()
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        /** `NEW_TASK`: it is started from a service or a notification, never from one of the app's own screens. */
        fun intent(context: Context): Intent =
            Intent(context, CastTargetActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
