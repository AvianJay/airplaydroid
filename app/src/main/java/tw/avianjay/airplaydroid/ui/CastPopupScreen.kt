package tw.avianjay.airplaydroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.discovery.DiscoveryUiState
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.MirrorTap
import tw.avianjay.airplaydroid.mirror.MirrorUiState
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession.KeySeed

/**
 * The Quick Settings popup: pick a receiver, start mirroring, or stop the
 * session already running.
 *
 * Deliberately not the picker in a different frame. The popup exists to be one
 * gesture away, so it carries only the two things worth a gesture -- the device
 * list and Stop -- and leaves pairing management, **Add by address**, **Play
 * video URL** and settings in the app, behind **Open app**. [DeviceRow] and
 * [rowStatusFor] are shared with the picker all the same: a row that renders
 * differently in the two places would be a second answer to "can this one be
 * mirrored to?", and the two answers would drift.
 */
@Composable
fun CastPopupScreen(
    state: DiscoveryUiState,
    mirror: MirrorUiState,
    pairings: Map<String, PairingStore.Summary>,
    errorMessage: String?,
    onMirror: (AirPlayDevice, String?) -> Unit,
    onStopMirror: () -> Unit,
    onRefused: (String) -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    legacyKeySeeds: Map<String, KeySeed>,
    modifier: Modifier = Modifier,
) {
    var passwordFor by remember { mutableStateOf<AirPlayDevice?>(null) }
    val alreadyMirroring = stringResource(R.string.mirror_already_active, mirror.device?.displayName.orEmpty())

    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = 420.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PopupHeader(mirror, onOpenApp, onDismiss)

            if (mirror.active) {
                PopupMirrorBar(mirror, onStopMirror)
            }

            if (state.devices.isEmpty()) {
                PopupEmptyState(state, errorMessage)
            } else {
                errorMessage?.let { PopupError(it) }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(state.devices, key = { it.key }) { device ->
                        val saved = pairings[device.key]
                        val mirroringHere = mirror.active && mirror.device?.key == device.key
                        DeviceRow(
                            device = device,
                            status = rowStatusFor(device, saved, mirror),
                            mirroringHere = mirroringHere,
                            // No video-URL handoff here, so the row has no menu;
                            // these are the values the picker's menu would use.
                            canPlayUrl = false,
                            paired = saved != null,
                            showMenu = false,
                            onClick = {
                                when (val tap = MirrorController.tapActionFor(device, saved, mirror)) {
                                    is MirrorTap.Refused -> onRefused(tap.message)
                                    is MirrorTap.Busy -> onRefused(alreadyMirroring)
                                    MirrorTap.AskPassword -> passwordFor = device
                                    MirrorTap.Start -> onMirror(device, null)
                                }
                            },
                            onPlayUrl = {},
                            onForget = {},
                            legacyKeySeed = legacyKeySeeds[device.key]
                                ?: KeySeed.AUTO.takeIf { MirrorController.usesLegacyPath(device) },
                            onCycleLegacyKey = {},
                        )
                    }
                }
            }
        }
    }

    passwordFor?.let { device ->
        MirrorPasswordDialog(
            device = device,
            onDismiss = { passwordFor = null },
            onConfirm = { password ->
                passwordFor = null
                onMirror(device, password)
            },
        )
    }

    // The receiver is showing a one-time code; the service waits for it. Shown
    // over the popup rather than handed to the app: the session is already
    // starting, and the popup is where the user is.
    if (mirror.phase == MirrorUiState.Phase.AwaitingPin) {
        PinDialog(
            deviceName = mirror.device?.displayName.orEmpty(),
            onDismiss = onStopMirror,
            onConfirm = MirrorController::submitPin,
        )
    }
}

@Composable
private fun PopupHeader(
    mirror: MirrorUiState,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.popup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (mirror.active) {
                Text(
                    text = mirror.device?.displayName.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The way to the rest of the app -- settings, add by address, the video
        // handoff. Without it the popup would be a dead end for anyone who
        // opened it by mistake.
        TextButton(onClick = onOpenApp) { Text(stringResource(R.string.popup_open_app)) }
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.popup_close_description),
            )
        }
    }
}

/** The session's own row, so Stop is reachable without scrolling to the device. */
@Composable
private fun PopupMirrorBar(mirror: MirrorUiState, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mirror.phase == MirrorUiState.Phase.Mirroring) {
                Icon(painterResource(R.drawable.ic_cast_connected), contentDescription = null)
            } else {
                PopupSpinner()
            }
            Text(
                text = stringResource(
                    when (mirror.phase) {
                        MirrorUiState.Phase.AwaitingConsent -> R.string.mirror_status_consent
                        MirrorUiState.Phase.AwaitingPin -> R.string.mirror_status_pin
                        MirrorUiState.Phase.Pairing -> R.string.mirror_status_pairing
                        MirrorUiState.Phase.Connecting -> R.string.mirror_status_connecting
                        else -> R.string.mirror_status_mirroring
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
        }
    }
}

/**
 * A failure.
 *
 * Rendered in the layout rather than through a snackbar host: the popup has no
 * Scaffold, because it is a card inside another app's window, and a snackbar
 * would have to be anchored to something that is not ours.
 */
@Composable
private fun PopupError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/**
 * Nothing resolved yet, or a receiver list that is still empty after a failure.
 * Also where the discovery diagnostics live, in the picker's own words.
 */
@Composable
private fun PopupEmptyState(state: DiscoveryUiState, error: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        error?.let { PopupError(it) }
        if (state.isScanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopupSpinner()
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.picker_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (error == null) {
            Text(
                text = stringResource(R.string.picker_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = stringResource(R.string.picker_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Sized to sit where a 24 dp icon would. */
@Composable
private fun PopupSpinner() {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/**
 * The dark backdrop, and the tap-outside-to-close target.
 *
 * A real, full-screen, clickable surface *behind* the card rather than a
 * `Modifier.clickable` on the card's parent: the card is a child, so a tap meant
 * for the list would otherwise be swallowed on its way down. The card consumes
 * its own taps separately -- see below.
 *
 * The card's own inset padding is [WindowInsets.safeDrawing], not a guess at the
 * status bar height: the popup's window is translucent, so whether it is laid out
 * behind the system bars depends on the platform version, and the card must not
 * land under them either way.
 */
@Composable
fun PopupScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScrimColor)
            .clickable(
                // No ripple and no role: this is a dismiss target, not a button.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.popup_close_description),
                onClick = onDismiss,
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // Consumes taps so they never reach the dismiss target behind.
                // `detectTapGestures` rather than a no-op `clickable`, which would
                // put a button role on the whole card and have TalkBack announce
                // the card itself as a button before reading anything inside it.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            content()
        }
    }
}

/** Dark enough to read the card against a bright app behind it. */
private val ScrimColor = Color(0x99000000)
