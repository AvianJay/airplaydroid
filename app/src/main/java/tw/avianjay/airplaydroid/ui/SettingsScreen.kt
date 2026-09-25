package tw.avianjay.airplaydroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.mirror.MirrorLog
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession.KeySeed
import tw.avianjay.airplaydroid.settings.Settings
import tw.avianjay.airplaydroid.settings.SettingsStore

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
