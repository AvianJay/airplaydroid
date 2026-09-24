package tw.avianjay.airplaydroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.discovery.AirPlayServiceType
import tw.avianjay.airplaydroid.discovery.DiscoveryUiState
import tw.avianjay.airplaydroid.mirror.MirrorUiState
import tw.avianjay.airplaydroid.playback.PlaybackUiState
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.DeviceCapability
import tw.avianjay.airplaydroid.protocol.VideoHandoff
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerScreen(
    state: DiscoveryUiState,
    playback: PlaybackUiState,
    snackbarHostState: SnackbarHostState,
    onPlay: (AirPlayDevice, String, String?) -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onRefused: (String) -> Unit,
    onSubmitPin: (String) -> Unit,
    onCancelPin: () -> Unit,
    mirror: MirrorUiState,
    mirrorRefusalFor: (AirPlayDevice) -> String?,
    hasSavedPairing: (AirPlayDevice) -> Boolean,
    onMirror: (AirPlayDevice, String?) -> Unit,
    onStopMirror: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDevice by remember { mutableStateOf<AirPlayDevice?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.picker_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Scaffold applies contentWindowInsets to the content slot
                    // only, so a custom bottomBar must handle its own insets or
                    // it draws underneath the navigation bar.
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
            ) {
                if (mirror.active) {
                    MirrorBar(mirror, onStopMirror)
                }
                if (playback.device != null) {
                    PlaybackBar(playback, onTogglePlayPause, onStop)
                }
                DiagnosticsBar(state)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (state.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.devices.isEmpty()) {
                EmptyState(scanning = state.isScanning)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.devices, key = { it.key }) { device ->
                        DeviceRow(
                            device = device,
                            onClick = {
                                val refusal = VideoHandoff.refusalFor(device)
                                // Open the dialog if either action is possible; it
                                // enables only the buttons that are.
                                if (refusal != null && mirrorRefusalFor(device) != null) {
                                    onRefused(refusal.message ?: "Cannot connect to this device.")
                                } else {
                                    pendingDevice = device
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (playback.awaitingPin) {
        PinDialog(
            deviceName = playback.device?.displayName.orEmpty(),
            onDismiss = onCancelPin,
            onConfirm = onSubmitPin,
        )
    }

    pendingDevice?.let { device ->
        PlayUrlDialog(
            device = device,
            canPlay = VideoHandoff.refusalFor(device) == null,
            mirrorRefusal = mirrorRefusalFor(device),
            hasSavedPairing = hasSavedPairing(device),
            onDismiss = { pendingDevice = null },
            onConfirm = { url, password ->
                pendingDevice = null
                onPlay(device, url, password)
            },
            onMirror = { password ->
                pendingDevice = null
                onMirror(device, password)
            },
        )
    }
}

/** Shown only once the receiver is actually displaying a PIN. */
@Composable
private fun PinDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_dialog_title, deviceName)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.play_dialog_pin_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length >= 4) {
                Text(stringResource(R.string.action_pair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PlayUrlDialog(
    device: AirPlayDevice,
    canPlay: Boolean,
    mirrorRefusal: String?,
    hasSavedPairing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
    onMirror: (String?) -> Unit,
) {
    var url by rememberSaveable(device.key) { mutableStateOf("") }
    var password by rememberSaveable(device.key) { mutableStateOf("") }
    // bit 7 -> a Digest password; bit 9 -> a PIN shown on the receiver's screen.
    val flags = device.airPlayTxt?.flags
    // A PIN cannot be asked for up front: it only appears after pairing starts.
    val needsPassword = flags?.passwordRequired == true
    val needsPin = false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.play_dialog_title, device.displayName)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.play_dialog_url_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(if (needsPin) R.string.play_dialog_pin_label else R.string.play_dialog_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.play_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                val mirrorNote = mirrorRefusal
                    ?: if (hasSavedPairing) stringResource(R.string.mirror_saved_pairing) else null
                if (mirrorNote != null) {
                    Text(
                        text = mirrorNote,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { onMirror(password.ifBlank { null }) },
                    // A saved pairing also saved the password; Digest still needs it.
                    enabled = mirrorRefusal == null &&
                        (!needsPassword || hasSavedPairing || password.isNotBlank()),
                ) { Text(stringResource(R.string.action_mirror)) }
                TextButton(
                    onClick = { onConfirm(url, password.ifBlank { null }) },
                    enabled = canPlay && url.isNotBlank() && (!needsPassword || password.isNotBlank()),
                ) { Text(stringResource(R.string.action_play)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun MirrorBar(mirror: MirrorUiState, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mirror.device?.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        when (mirror.phase) {
                            MirrorUiState.Phase.AwaitingConsent -> R.string.mirror_status_consent
                            MirrorUiState.Phase.Pairing -> R.string.mirror_status_pairing
                            MirrorUiState.Phase.Connecting -> R.string.mirror_status_connecting
                            else -> R.string.mirror_status_mirroring
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
        }
    }
}

@Composable
private fun PlaybackBar(
    playback: PlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = playback.device?.displayName.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when {
                    playback.connecting -> stringResource(R.string.playback_connecting)
                    else -> progressLabel(playback)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onTogglePlayPause, enabled = playback.connected) {
                    Text(
                        stringResource(
                            if (playback.isPlaying) R.string.action_pause else R.string.action_resume
                        )
                    )
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            }
        }
    }
}

private fun progressLabel(playback: PlaybackUiState): String {
    val position = playback.info.positionSeconds
    val duration = playback.info.durationSeconds
    if (position == null) return if (playback.connected) "Loading…" else ""
    return if (duration != null && duration > 0) {
        formatTime(position) + " / " + formatTime(duration)
    } else {
        formatTime(position)
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val minutes = total / 60
    val remainder = total % 60
    return minutes.toString() + ":" + remainder.toString().padStart(2, '0')
}

@Composable
private fun DeviceRow(device: AirPlayDevice, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(device.displayName) },
        supportingContent = {
            Column {
                val endpoint = device.primaryEndpoint
                Text(
                    text = listOfNotNull(device.model, endpoint?.toString())
                        .joinToString(" · ")
                        .ifEmpty { "Resolving…" },
                    style = MaterialTheme.typography.bodySmall,
                )
                CapabilityBadges(device.capabilities)
            }
        },
    )
}

/**
 * Read-only badges. Deliberately NOT AssistChip(enabled = false): a disabled chip
 * is a button that cannot be pressed, so it renders greyed out and TalkBack
 * announces it as "disabled". These are labels, not controls.
 */
@Composable
private fun CapabilityBadges(capabilities: List<DeviceCapability>) {
    if (capabilities.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        capabilities.forEach { capability ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = capability.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (scanning) R.string.picker_scanning else R.string.picker_empty_title
            ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.picker_empty_body),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Discovery state and per-service-type counts.
 *
 * Deliberately NOT classifying EPERM / local-network-denial here: at targetSdk 36
 * local network access is implicitly granted, so that path cannot be exercised
 * yet. It lands in milestone 2 together with `am compat enable RESTRICT_LOCAL_NETWORK`.
 */
@Composable
private fun DiagnosticsBar(state: DiscoveryUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = buildString {
                append(if (state.isScanning) "Scanning" else "Idle")
                append("  ·  _airplay._tcp: ")
                append(state.countOf(AirPlayServiceType.AirPlay))
                append("  ·  _raop._tcp: ")
                append(state.countOf(AirPlayServiceType.Raop))
            },
            style = MaterialTheme.typography.labelSmall,
        )
        state.lastError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
