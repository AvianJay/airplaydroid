package tw.avianjay.airplaydroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.cast.CastUiState
import tw.avianjay.airplaydroid.discovery.DiscoveryUiState
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.playback.PlaybackUiState
import tw.avianjay.airplaydroid.protocol.AirPlayDevice
import tw.avianjay.airplaydroid.protocol.VideoHandoff

/**
 * The Cast chooser: a sender has connected, and this is where the user picks the
 * AirPlay receiver its media plays on.
 *
 * Only receivers that take a video URL are listed -- a speaker could not play
 * what a Cast sender sends. The rows are the picker's own [DeviceRow], for the
 * same reason the Quick Settings popup uses them.
 */
@Composable
fun CastTargetScreen(
    discovery: DiscoveryUiState,
    cast: CastUiState,
    playback: PlaybackUiState,
    pairings: Map<String, PairingStore.Summary>,
    onChoose: (AirPlayDevice, String?) -> Unit,
    onSubmitPin: (String) -> Unit,
    onCancelCast: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordFor by remember { mutableStateOf<AirPlayDevice?>(null) }
    val devices = discovery.devices.filter { VideoHandoff.refusalFor(it) == null }

    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = 460.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cast_chooser_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = cast.title?.let { stringResource(R.string.cast_chooser_subtitle_titled, it) }
                            ?: stringResource(R.string.cast_chooser_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Cancel ends the sender's session; the close button only hides the chooser.
                TextButton(onClick = onCancelCast) { Text(stringResource(R.string.cast_chooser_cancel)) }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.popup_close_description),
                    )
                }
            }

            val status = when {
                cast.converting && playback.connecting -> stringResource(R.string.cast_status_converting)
                playback.connecting -> stringResource(R.string.playback_connecting)
                else -> null
            }
            status?.let { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PopupSpinner()
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
                }
            }

            if (devices.isEmpty()) {
                PopupEmptyState(discovery, cast.error)
            } else {
                cast.error?.let { PopupError(it) }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(devices, key = { it.key }) { device ->
                        val saved = pairings[device.key]
                        DeviceRow(
                            device = device,
                            status = if (saved != null) RowStatus.Paired else null,
                            mirroringHere = cast.target?.key == device.key,
                            canPlayUrl = true,
                            paired = saved != null,
                            showMenu = false,
                            onClick = {
                                // A password receiver asks once; a saved one is used without asking.
                                val wantsPassword = device.airPlayTxt?.flags?.passwordRequired == true
                                if (wantsPassword && saved?.hasPassword != true) passwordFor = device
                                else onChoose(device, null)
                            },
                            onPlayUrl = {},
                            onForget = {},
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
                onChoose(device, password)
            },
            titleRes = R.string.cast_password_title,
            confirmRes = R.string.action_play,
        )
    }

    // A receiver that pairs with an on-screen code shows it once the handoff starts.
    if (playback.awaitingPin) {
        PinDialog(
            deviceName = playback.device?.displayName.orEmpty(),
            onDismiss = onCancelCast,
            onConfirm = onSubmitPin,
        )
    }
}
