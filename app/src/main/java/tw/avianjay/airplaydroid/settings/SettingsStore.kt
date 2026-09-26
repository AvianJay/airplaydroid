package tw.avianjay.airplaydroid.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.avianjay.airplaydroid.protocol.mirror.LegacyRtspMirrorSession.KeySeed
import tw.avianjay.airplaydroid.protocol.update.UpdateChannel

/**
 * The user-facing settings, as one immutable value.
 *
 * [clientName] is what every receiver shows for this phone -- the name in the
 * TV's AirPlay list and in the `X-Apple-Client-Name` header of the pairing
 * requests. It defaults to the device's own model name, which is what a real
 * sender shows; the previous behaviour used `Build.MODEL` for the mirroring
 * session but the hard-coded `"AirPlayDroid"` for pairing, so the two
 * disagreed and the receiver could list one name and record another.
 */
data class Settings(
    val clientName: String,
    /**
     * Hold the screen on while a mirroring session runs. Mirroring is driven by
     * `MediaProjection`, which keeps capturing when the screen sleeps, but the
     * encoder's surface stops producing frames, so the TV freezes on the last
     * one. On by default.
     */
    val keepScreenAwake: Boolean,
    /**
     * The [KeySeed] used for a legacy receiver with no choice of its own. A
     * per-receiver override, set from the row's menu, still wins.
     */
    val defaultLegacyKeySeed: KeySeed,
    /**
     * Which releases the in-app updater may offer. Defaults to [UpdateChannel.OFF]:
     * an app that starts phoning home the moment it is installed is a surprise,
     * and on this project every build before the first tagged release would
     * otherwise check a manifest that does not exist yet.
     */
    val updateChannel: UpdateChannel,
)

/**
 * Reads and writes [Settings] in a private `SharedPreferences` file.
 *
 * Every field has a working default, so a fresh install -- and a store read
 * before anything was ever saved -- behaves exactly as the app did before
 * settings existed.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    /**
     * Ignored when blank: the receiver needs a name, and an empty one would show
     * as a gap in its AirPlay list. The field in the UI keeps whatever was typed,
     * so clearing it mid-edit is still possible.
     */
    fun setClientName(name: String) {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || trimmed == _state.value.clientName) return
        prefs.edit { putString(KEY_CLIENT_NAME, trimmed) }
        _state.value = _state.value.copy(clientName = trimmed)
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        if (enabled == _state.value.keepScreenAwake) return
        prefs.edit { putBoolean(KEY_KEEP_AWAKE, enabled) }
        _state.value = _state.value.copy(keepScreenAwake = enabled)
    }

    fun setDefaultLegacyKeySeed(seed: KeySeed) {
        if (seed == _state.value.defaultLegacyKeySeed) return
        // AUTO is the default, so an explicit choice of it is stored as absence.
        prefs.edit { if (seed == KeySeed.AUTO) remove(KEY_LEGACY_KEY) else putString(KEY_LEGACY_KEY, seed.name) }
        _state.value = _state.value.copy(defaultLegacyKeySeed = seed)
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (channel == _state.value.updateChannel) return
        // OFF is the default, so an explicit choice of it is stored as absence.
        prefs.edit {
            if (channel == UpdateChannel.OFF) remove(KEY_UPDATE_CHANNEL)
            else putString(KEY_UPDATE_CHANNEL, channel.name)
        }
        _state.value = _state.value.copy(updateChannel = channel)
    }

    /** The client name alone, for callers that run off the main thread. */
    fun clientName(): String = _state.value.clientName

    fun keepScreenAwake(): Boolean = _state.value.keepScreenAwake

    fun defaultLegacyKeySeed(): KeySeed = _state.value.defaultLegacyKeySeed

    fun updateChannel(): UpdateChannel = _state.value.updateChannel

    private fun read(): Settings = Settings(
        clientName = prefs.getString(KEY_CLIENT_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultClientName(),
        keepScreenAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true),
        defaultLegacyKeySeed = prefs.getString(KEY_LEGACY_KEY, null)
            ?.let { runCatching { KeySeed.valueOf(it) }.getOrNull() }
            ?: KeySeed.AUTO,
        updateChannel = prefs.getString(KEY_UPDATE_CHANNEL, null)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.OFF,
    )

    companion object {
        private const val PREFS = "settings"
        private const val KEY_CLIENT_NAME = "client_name"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"
        private const val KEY_LEGACY_KEY = "default_legacy_key_seed"
        private const val KEY_UPDATE_CHANNEL = "update_channel"

        /** Long enough for any real device name, short enough for a SETUP body. */
        const val MAX_NAME_LENGTH = 64

        /**
         * `Build.MODEL` is the name a phone shows as an AirPlay sender, so it is
         * the least surprising default. Some emulator and OEM builds leave it
         * blank or set it to a placeholder, hence the fallback.
         */
        fun defaultClientName(): String =
            Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_NAME

        private const val FALLBACK_NAME = "AirPlayDroid"
    }
}
