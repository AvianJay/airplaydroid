package tw.avianjay.airplaydroid.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.discovery.DiscoveryUiState
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.MirrorTap
import tw.avianjay.airplaydroid.mirror.MirrorUiState
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackUiState
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession.KeySeed
import tw.avianjay.airplaydroid.protocol.DeviceCapability
import tw.avianjay.airplaydroid.protocol.VideoHandoff
import kotlin.math.roundToInt

/**
 * The device list. Tapping a row mirrors to it; the video-URL handoff and
 * pairing management live in each row's overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerScreen(
    state: DiscoveryUiState,
    playback: PlaybackUiState,
    mirror: MirrorUiState,
    pairings: Map<String, PairingStore.Summary>,
    snackbarHostState: SnackbarHostState,
    onMirror: (AirPlayDevice, String?) -> Unit,
    onStopMirror: () -> Unit,
    onForgetPairing: (AirPlayDevice) -> Unit,
    onPlay: (AirPlayDevice, String, String?) -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onCancelPin: () -> Unit,
    onRefused: (String) -> Unit,
    onAddByAddress: (host: String, port: Int) -> Unit,
    legacyKeySeeds: Map<String, KeySeed>,
    onCycleLegacyKey: (AirPlayDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordFor by remember { mutableStateOf<AirPlayDevice?>(null) }
    var addingAddress by rememberSaveable { mutableStateOf(false) }
    var playUrlFor by remember { mutableStateOf<AirPlayDevice?>(null) }
    var forgetFor by remember { mutableStateOf<AirPlayDevice?>(null) }
    // Resolved here because the row's click lambda cannot call stringResource.
    val alreadyMirroring = stringResource(R.string.mirror_already_active, mirror.device?.displayName.orEmpty())

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.picker_title)) },
                actions = {
                    TextButton(onClick = { addingAddress = true }) {
                        Text(stringResource(R.string.action_add_by_address))
                    }
                },
            )
        },
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
                // Not `device != null`: a failed attempt keeps its device for the
                // error message, and would leave a dead bar behind.
                if (playback.connecting || playback.connected) {
                    PlaybackBar(playback, onTogglePlayPause, onStop)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            state.lastError?.let { DiscoveryError(it) }

            if (state.devices.isEmpty()) {
                EmptyState(state)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.devices, key = { it.key }) { device ->
                        val saved = pairings[device.key]
                        val mirroringHere = mirror.active && mirror.device?.key == device.key
                        Column(modifier = Modifier.animateItem()) {
                            DeviceRow(
                                device = device,
                                status = rowStatusFor(device, saved, mirror),
                                mirroringHere = mirroringHere,
                                canPlayUrl = VideoHandoff.refusalFor(device) == null,
                                paired = saved != null,
                                onClick = {
                                    when (val tap = MirrorController.tapActionFor(device, saved, mirror)) {
                                        is MirrorTap.Refused -> onRefused(tap.message)
                                        is MirrorTap.Busy -> onRefused(alreadyMirroring)
                                        MirrorTap.AskPassword -> passwordFor = device
                                        MirrorTap.Start -> onMirror(device, null)
                                    }
                                },
                                onPlayUrl = { playUrlFor = device },
                                onForget = { forgetFor = device },
                                legacyKeySeed = legacyKeySeeds[device.key]
                                    ?: KeySeed.AUTO.takeIf { MirrorController.usesLegacyPath(device) },
                                onCycleLegacyKey = { onCycleLegacyKey(device) },
                            )
                            // Inset to the text column, past the leading icon.
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
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

    // The receiver is showing a one-time code; the service waits for it.
    if (mirror.phase == MirrorUiState.Phase.AwaitingPin) {
        PinDialog(
            deviceName = mirror.device?.displayName.orEmpty(),
            onDismiss = onStopMirror,
            onConfirm = MirrorController::submitPin,
        )
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

    playUrlFor?.let { device ->
        PlayUrlDialog(
            device = device,
            onDismiss = { playUrlFor = null },
            onConfirm = { url, password ->
                playUrlFor = null
                onPlay(device, url, password)
            },
        )
    }

    if (addingAddress) {
        AddAddressDialog(
            onDismiss = { addingAddress = false },
            onConfirm = { host, port ->
                addingAddress = false
                onAddByAddress(host, port)
            },
        )
    }

    forgetFor?.let { device ->
        ForgetPairingDialog(
            device = device,
            onDismiss = { forgetFor = null },
            onConfirm = {
                forgetFor = null
                onForgetPairing(device)
            },
        )
    }
}

private enum class RowStatus(@param:StringRes val label: Int) {
    Mirroring(R.string.device_status_mirroring),
    Pairing(R.string.mirror_status_pairing),
    Connecting(R.string.mirror_status_connecting),
    Paired(R.string.device_status_paired),
    CannotMirror(R.string.device_status_cannot_mirror),
}

private fun rowStatusFor(device: AirPlayDevice, saved: PairingStore.Summary?, mirror: MirrorUiState): RowStatus? {
    if (mirror.active && mirror.device?.key == device.key) {
        return when (mirror.phase) {
            MirrorUiState.Phase.Mirroring -> RowStatus.Mirroring
            MirrorUiState.Phase.Pairing -> RowStatus.Pairing
            else -> RowStatus.Connecting
        }
    }
    // Only for receivers that advertise mirroring (e.g. one in PIN mode), so
    // every speaker on the network does not say "Can't mirror".
    if (device.airPlayTxt?.features?.supportsScreenMirroring == true &&
        MirrorController.refusalFor(device, saved != null) != null
    ) {
        return RowStatus.CannotMirror
    }
    if (saved != null) return RowStatus.Paired
    return null
}

@Composable
private fun DeviceRow(
    device: AirPlayDevice,
    status: RowStatus?,
    mirroringHere: Boolean,
    canPlayUrl: Boolean,
    paired: Boolean,
    onClick: () -> Unit,
    onPlayUrl: () -> Unit,
    onForget: () -> Unit,
    legacyKeySeed: KeySeed? = null,
    onCycleLegacyKey: () -> Unit = {},
) {
    val features = device.airPlayTxt?.features
    val icon = when {
        mirroringHere -> R.drawable.ic_cast_connected
        features?.supportsScreenMirroring == true || features?.supportsVideoUrl == true -> R.drawable.ic_tv
        else -> R.drawable.ic_speaker
    }
    val colors = if (mirroringHere) {
        val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            headlineColor = onContainer,
            supportingColor = onContainer,
            leadingIconColor = onContainer,
            trailingIconColor = onContainer,
        )
    } else {
        ListItemDefaults.colors()
    }
    val statusText = status?.let { stringResource(it.label) }
    // On the highlighted row, or for a refusal, the status takes the row's own
    // colour; elsewhere it is picked out in the accent.
    val statusColor = if (mirroringHere || status == RowStatus.CannotMirror) Color.Unspecified else MaterialTheme.colorScheme.primary
    val details = listOfNotNull(device.model, device.primaryEndpoint?.toString())
        .joinToString(" · ")
        .ifEmpty { stringResource(R.string.device_resolving) }

    ListItem(
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.action_mirror),
            role = Role.Button,
            onClick = onClick,
        ),
        colors = colors,
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        headlineContent = { Text(device.displayName) },
        supportingContent = {
            Column {
                Text(
                    text = buildAnnotatedString {
                        if (statusText != null) {
                            withStyle(SpanStyle(color = statusColor, fontWeight = FontWeight.Medium)) {
                                append(statusText)
                            }
                            append(" · ")
                        }
                        append(details)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                CapabilityBadges(device.capabilities.filterNot { it in ConnectionBadges })
            }
        },
        trailingContent = {
            DeviceMenu(
                deviceName = device.displayName,
                canPlayUrl = canPlayUrl,
                paired = paired,
                // Forgetting the pairing a live session runs on would only make
                // the next session pair again; not worth offering mid-session.
                forgetEnabled = !mirroringHere,
                onPlayUrl = onPlayUrl,
                onForget = onForget,
                legacyKeySeed = legacyKeySeed,
                onCycleLegacyKey = onCycleLegacyKey,
            )
        },
    )
}

@Composable
private fun DeviceMenu(
    deviceName: String,
    canPlayUrl: Boolean,
    paired: Boolean,
    forgetEnabled: Boolean,
    onPlayUrl: () -> Unit,
    onForget: () -> Unit,
    legacyKeySeed: KeySeed?,
    onCycleLegacyKey: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.device_menu_description, deviceName),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_play_url)) },
                enabled = canPlayUrl,
                onClick = {
                    expanded = false
                    onPlayUrl()
                },
            )
            // Legacy receivers only. Tapping cycles the choice and keeps the menu
            // open, so the new label is visible; it applies from the next session.
            legacyKeySeed?.let { seed ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                stringResource(
                                    when (seed) {
                                        KeySeed.AUTO -> R.string.legacy_key_auto
                                        KeySeed.RAW -> R.string.legacy_key_raw
                                        KeySeed.MIXED -> R.string.legacy_key_mixed
                                    }
                                )
                            )
                            Text(stringResource(R.string.legacy_key_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = onCycleLegacyKey,
                )
            }
            if (paired) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_forget_pairing)) },
                    enabled = forgetEnabled,
                    onClick = {
                        expanded = false
                        onForget()
                    },
                )
            }
        }
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

/**
 * A receiver discovery cannot see: across a VPN or tunnel, or the host seen from
 * an Android emulator, where multicast does not pass. The port is the
 * `_airplay._tcp` one, 7000 on nearly every receiver.
 */
@Composable
private fun AddAddressDialog(
    onDismiss: () -> Unit,
    onConfirm: (host: String, port: Int) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("7000") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && portNumber != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_address_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.add_address_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_address_host_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_address_port_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(host, portNumber!!) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(host, portNumber!!) }, enabled = valid) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Asked only when the receiver wants a password and none is saved for it. */
@Composable
private fun MirrorPasswordDialog(
    device: AirPlayDevice,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Plain remember, not rememberSaveable: the secret must not be written into
    // the saved-state Bundle, and configChanges already keeps it across rotation.
    var password by remember(device.key) { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mirror_password_title, device.displayName)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.mirror_password_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.play_dialog_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onConfirm(password) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                )
                // Inside the dialog's own composition, so the field is attached
                // by the time this runs.
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.action_mirror))
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
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var url by rememberSaveable(device.key) { mutableStateOf("") }
    // Not saveable, for the same reason as MirrorPasswordDialog's.
    var password by remember(device.key) { mutableStateOf("") }
    // bit 7 -> a Digest password, the same predicate PlaybackController pairs on.
    // A PIN (bit 9) cannot be asked for up front: it only appears after pairing
    // starts, and PinDialog collects it then.
    val needsPassword = device.airPlayTxt?.flags?.passwordRequired == true

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
                        label = { Text(stringResource(R.string.play_dialog_password_label)) },
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, password.ifBlank { null }) },
                enabled = url.isNotBlank() && (!needsPassword || password.isNotBlank()),
            ) { Text(stringResource(R.string.action_play)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Confirmed first: pairing again needs the AirPlay password, which may not be at hand. */
@Composable
private fun ForgetPairingDialog(
    device: AirPlayDevice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forget_dialog_title, device.displayName)) },
        text = { Text(stringResource(R.string.forget_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_forget)) }
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mirror.phase == MirrorUiState.Phase.Mirroring) {
                Icon(painterResource(R.drawable.ic_cast_connected), contentDescription = null)
            } else {
                SmallSpinner()
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = mirror.device?.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                )
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
                )
            }
            FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
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
        Column(modifier = Modifier.fillMaxWidth()) {
            val position = playback.info.positionSeconds
            val duration = playback.info.durationSeconds
            if (position != null && duration != null && duration > 0) {
                LinearProgressIndicator(
                    progress = { (position / duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (playback.connecting) {
                    SmallSpinner()
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playback.device?.displayName.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (playback.connecting) {
                            stringResource(R.string.playback_connecting)
                        } else {
                            progressLabel(playback, loading = stringResource(R.string.playback_loading))
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onTogglePlayPause, enabled = playback.connected) {
                    Text(
                        stringResource(
                            if (playback.isPlaying) R.string.action_pause else R.string.action_resume
                        )
                    )
                }
                FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            }
        }
    }
}

/** Sized to sit where a 24 dp icon would. */
@Composable
private fun SmallSpinner() {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

private fun progressLabel(playback: PlaybackUiState, loading: String): String {
    val position = playback.info.positionSeconds
    val duration = playback.info.durationSeconds
    if (position == null) return if (playback.connected) loading else ""
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

/**
 * Not shown as badges: how to get in is the tap's business now -- it asks for
 * the password when one is needed -- and the row's status says when it cannot.
 */
private val ConnectionBadges = setOf(DeviceCapability.NeedsPassword, DeviceCapability.NeedsPin)

/**
 * Read-only badges. Deliberately NOT AssistChip(enabled = false): a disabled chip
 * is a button that cannot be pressed, so it renders greyed out and TalkBack
 * announces it as "disabled". These are labels, not controls.
 *
 * A FlowRow of single-line labels: in a plain Row the badges that do not fit
 * are squeezed instead, and their text breaks a word per line.
 */
@Composable
private fun CapabilityBadges(capabilities: List<DeviceCapability>) {
    if (capabilities.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Nothing resolved yet. Also where the discovery diagnostics live: which service
 * types are being browsed is what tells a broken network from an empty one.
 */
@Composable
private fun EmptyState(state: DiscoveryUiState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isScanning) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 24.dp))
        }
        Text(
            text = stringResource(
                if (state.isScanning) R.string.picker_scanning else R.string.picker_empty_title
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
        if (state.isScanning) {
            Text(
                text = stringResource(
                    R.string.picker_browsing,
                    state.scanning.sortedBy { it.ordinal }.joinToString(" · ") { it.mdnsType },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * The last discovery failure, shown whether or not any devices are listed.
 *
 * Deliberately NOT classifying EPERM / local-network-denial here: at targetSdk 36
 * local network access is implicitly granted, so that path cannot be exercised
 * yet. It lands in milestone 2 together with `am compat enable RESTRICT_LOCAL_NETWORK`.
 */
@Composable
private fun DiscoveryError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
