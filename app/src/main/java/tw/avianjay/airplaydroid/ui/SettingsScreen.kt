package tw.avianjay.airplaydroid.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import tw.avianjay.airplaydroid.BuildConfig
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.mirror.MirrorLog
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession.KeySeed
import tw.avianjay.airplaydroid.protocol.update.UpdateChannel
import tw.avianjay.airplaydroid.protocol.update.UpdateOutcome
import tw.avianjay.airplaydroid.settings.Settings
import tw.avianjay.airplaydroid.settings.SettingsStore
import tw.avianjay.airplaydroid.update.ApkInstaller
import tw.avianjay.airplaydroid.update.UpdateUiState

/**
 * The settings screen, reached from the picker's overflow menu.
 *
 * Everything here is app-wide. The one thing that is deliberately not is the
 * legacy video key: a per-receiver override still wins, because receivers
 * disagree with each other, and this is only the fallback for the ones that
 * have no choice of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onClientName: (String) -> Unit,
    onKeepScreenAwake: (Boolean) -> Unit,
    onDefaultLegacyKeySeed: (KeySeed) -> Unit,
    onUpdateChannel: (UpdateChannel) -> Unit,
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ClientNameSection(
                storedName = settings.clientName,
                onCommit = onClientName,
            )

            HorizontalDivider()

            SwitchRow(
                title = stringResource(R.string.settings_keep_awake_title),
                body = stringResource(R.string.settings_keep_awake_body),
                checked = settings.keepScreenAwake,
                onCheckedChange = onKeepScreenAwake,
            )

            HorizontalDivider()

            LegacyKeySection(
                selected = settings.defaultLegacyKeySeed,
                onSelect = onDefaultLegacyKeySeed,
            )

            HorizontalDivider()

            UpdateSection(
                channel = settings.updateChannel,
                onChannel = onUpdateChannel,
                state = updateState,
                onCheck = onCheckForUpdates,
                onInstall = onInstallUpdate,
            )

            HorizontalDivider()

            DiagnosticsSection()
        }
    }
}

/**
 * The name this phone is known by.
 *
 * The field keeps its own text while it is focused and only commits when focus
 * leaves it, so a half-typed name is never saved and the cursor is never yanked
 * back by the store's own trimming.
 */
@Composable
private fun ClientNameSection(
    storedName: String,
    onCommit: (String) -> Unit,
) {
    var name by remember { mutableStateOf(storedName) }
    var focused by remember { mutableStateOf(false) }

    // Adopt a change made elsewhere (the reset button, or another screen).
    // Ignored while the field has focus, so it cannot fight the user's typing.
    LaunchedEffect(storedName, focused) {
        if (!focused) name = storedName
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.settings_client_name_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_client_name_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(SettingsStore.MAX_NAME_LENGTH) },
            singleLine = true,
            label = { Text(stringResource(R.string.settings_client_name_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(name) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .onFocusChanged { state ->
                    // Committing on the way out also covers a name left blank:
                    // the store refuses it, and the adopt-effect above then puts
                    // the stored name back.
                    if (focused && !state.isFocused) onCommit(name)
                    focused = state.isFocused
                },
        )
        val deviceName = remember { SettingsStore.defaultClientName() }
        if (storedName != deviceName) {
            TextButton(
                onClick = { onCommit(deviceName) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.settings_client_name_reset, deviceName))
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        // The whole row toggles: the switch is a small target, and the text is
        // the larger, more obvious one.
        modifier = Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(body, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(
                checked = checked,
                // null: the row above already handles the click, and a switch
                // with its own handler would toggle twice.
                onCheckedChange = null,
            )
        },
    )
}

/** One radio row per seed. The labels are the picker's own, so they match the row menu. */
@Composable
private fun LegacyKeySection(
    selected: KeySeed,
    onSelect: (KeySeed) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.settings_legacy_key_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_legacy_key_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        KeySeed.entries.forEach { seed ->
            ListItem(
                modifier = Modifier.clickable(role = Role.RadioButton) { onSelect(seed) },
                headlineContent = { Text(stringResource(seed.label())) },
                leadingContent = {
                    RadioButton(selected = seed == selected, onClick = null)
                },
            )
        }
    }
}

/**
 * Where the session log is, so it can be pulled without guessing the path.
 *
 * Read with `run-as`, which is why the command rather than a share sheet: the
 * file lives in app-private storage and only a debuggable build can be read
 * this way.
 */
@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    // Resolved from the context rather than MirrorLog.path(): the log object is
    // only initialised once a session has started in this process, so it would
    // report "no log" for one left by an earlier run.
    val log = remember(context) { MirrorLog.fileFor(context) }
    val exists = remember(log) { log.isFile }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.settings_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_diagnostics_body, context.packageName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (exists) {
            Text(
                text = log.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.settings_diagnostics_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The same three labels the per-receiver menu uses. */
private fun KeySeed.label(): Int = when (this) {
    KeySeed.AUTO -> R.string.legacy_key_auto
    KeySeed.RAW -> R.string.legacy_key_raw
    KeySeed.MIXED -> R.string.legacy_key_mixed
}

/**
 * Sized to sit where a 24 dp icon would, matching the picker's own spinner.
 * Duplicated rather than shared because the picker's copy is private to that
 * file and this is four lines of layout.
 */
@Composable
private fun SmallSpinner() {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/**
 * The updater: channel choice, the installed version, and one action button
 * whose label follows the state.
 *
 * A single button rather than "Check" plus "Download" because the two are never
 * both useful: once a release is known the only sensible next step is to fetch
 * it, and re-checking is what the row does when it is up to date.
 */
@Composable
private fun UpdateSection(
    channel: UpdateChannel,
    onChannel: (UpdateChannel) -> Unit,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.settings_updates_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_updates_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        UpdateChannel.entries.forEach { option ->
            ListItem(
                modifier = Modifier.clickable(role = Role.RadioButton) { onChannel(option) },
                headlineContent = { Text(stringResource(option.label())) },
                supportingContent = {
                    Text(stringResource(option.body()), style = MaterialTheme.typography.bodySmall)
                },
                leadingContent = {
                    RadioButton(selected = option == channel, onClick = null)
                },
            )
        }

        UpdateStatus(
            channel = channel,
            state = state,
            onCheck = onCheck,
            onInstall = onInstall,
            onOpenInstallerSettings = {
                runCatching { context.startActivity(ApkInstaller.installPermissionIntent(context)) }
            },
        )
    }
}

@Composable
private fun UpdateStatus(
    channel: UpdateChannel,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallerSettings: () -> Unit,
) {
    val context = LocalContext.current

    // Re-read whenever the screen comes back to the foreground: the user may
    // have just granted "install unknown apps" on the Settings page this app
    // sent them to, and nothing else in the composition would change to say so.
    var canInstall by remember { mutableStateOf(ApkInstaller.canInstallPackages(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = ApkInstaller.canInstallPackages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = installedLabel(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.release?.let { release ->
            Text(
                text = release.sizeLabel()
                    ?.let { stringResource(R.string.settings_update_available_with_size, release.versionName, it) }
                    ?: stringResource(R.string.settings_update_available, release.versionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            release.notes?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (state.phase == UpdateUiState.Phase.Downloading) {
            val progress = state.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Text(
                text = progress?.let { stringResource(R.string.settings_update_downloading, (it * 100).toInt()) }
                    ?: stringResource(R.string.settings_update_downloading_indeterminate),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // The channel is off: there is nothing to check and no status to report.
        if (channel.isOff) {
            Text(
                text = stringResource(R.string.settings_update_channel_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            statusLine(state)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Android will not let this app open the installer until the user grants
        // it in Settings, so the download is not even started: fetching ~10 MB
        // and then failing would waste the user's data for nothing.
        if (state.phase == UpdateUiState.Phase.Available && !canInstall) {
            Text(
                text = stringResource(R.string.settings_update_allow_install_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onOpenInstallerSettings) {
                Text(stringResource(R.string.settings_update_allow_install))
            }
            return@Column
        }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.phase) {
                UpdateUiState.Phase.Checking -> {
                    SmallSpinner()
                    Text(
                        stringResource(R.string.settings_update_checking),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                UpdateUiState.Phase.Downloading -> Unit

                UpdateUiState.Phase.Available -> {
                    FilledTonalButton(onClick = onInstall) {
                        Text(stringResource(R.string.settings_update_download))
                    }
                    TextButton(onClick = onCheck) { Text(stringResource(R.string.settings_update_check)) }
                }

                UpdateUiState.Phase.ReadyToInstall -> {
                    Text(
                        text = stringResource(R.string.settings_update_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                else -> {
                    TextButton(onClick = onCheck) { Text(stringResource(R.string.settings_update_check)) }
                }
            }
        }

        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, BuildConfig.RELEASES_PAGE_URL.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.settings_update_open_release))
        }
    }
}

/** "Installed: 0.1.0 (build 10001)", or just the build when the name is absent. */
@Composable
private fun installedLabel(state: UpdateUiState): String =
    if (state.installedVersionName.isNotBlank()) {
        stringResource(
            R.string.settings_update_installed,
            state.installedVersionName,
            state.installedVersionCode,
        )
    } else {
        stringResource(R.string.settings_update_installed_unknown, state.installedVersionCode)
    }

/**
 * The one-line result of the last check, or null when there is nothing to say.
 *
 * [UpdateOutcome.NO_INSTALLABLE_BUILD] and
 * [UpdateOutcome.STABLE_ONLY_PRERELEASES] are spelled out rather than folded
 * into "up to date": both mean the user is *not* current, and one of them is
 * fixed by switching channel.
 */
@Composable
private fun statusLine(state: UpdateUiState): String? = when (state.phase) {
    UpdateUiState.Phase.Idle -> null
    UpdateUiState.Phase.UpToDate -> when (state.outcome) {
        UpdateOutcome.STABLE_ONLY_PRERELEASES -> stringResource(R.string.settings_update_no_stable)
        UpdateOutcome.NO_INSTALLABLE_BUILD -> stringResource(R.string.settings_update_not_installable)
        // Reached when the channel exists but has published nothing yet, which
        // is not the same claim as "your build is current".
        UpdateOutcome.DISABLED_OR_EMPTY -> stringResource(R.string.settings_update_nothing_published)
        else -> stringResource(R.string.settings_update_up_to_date)
    }
    else -> null
}

/** The three channel labels, matching the radio rows. */
private fun UpdateChannel.label(): Int = when (this) {
    UpdateChannel.OFF -> R.string.settings_update_channel_off
    UpdateChannel.STABLE -> R.string.settings_update_channel_stable
    UpdateChannel.NIGHTLY -> R.string.settings_update_channel_nightly
}

private fun UpdateChannel.body(): Int = when (this) {
    UpdateChannel.OFF -> R.string.settings_update_channel_off_body
    UpdateChannel.STABLE -> R.string.settings_update_channel_stable_body
    UpdateChannel.NIGHTLY -> R.string.settings_update_channel_nightly_body
}
