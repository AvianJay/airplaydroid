package tw.avianjay.airplaydroid.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** The Chromecast receiver and its four options. See [CastSettings]. */
    val cast: CastSettings = CastSettings(),
)

/**
 * The Chromecast receiver: this phone shows up as a Cast device, and what is
 * cast to it is played on an AirPlay receiver.
 *
 * Off by default, and so is running in the background: a receiver that
 * listens on the network the moment the app is installed would be a surprise.
 */
data class CastSettings(
    /** Advertise and accept Cast senders at all. */
    val enabled: Boolean = false,
    /**
     * Keep the receiver running in a foreground service after the app is
     * closed, and start it again after a reboot. Off: it runs while the app is
     * on screen, and for as long as a cast it accepted is still playing.
     */
    val keepRunning: Boolean = false,
    /**
     * Ask which AirPlay receiver to play on each time a sender connects. Off: the
     * last one chosen is used without asking, as long as it can be found.
     */
    val askForDevice: Boolean = true,
    /**
     * Convert what AirPlay cannot play (WebM, Matroska, Ogg, FLAC...) on the
     * phone into HLS. Off: every URL is handed over as it is.
     */
    val convertFormats: Boolean = true,
    /**
     * Accept senders on this phone only. On by default: a Cast receiver has no
     * authentication of its own, so anyone on the Wi-Fi could otherwise play on
     * whatever this phone forwards to.
     */
    val localOnly: Boolean = true,
)

/** The receiver casts went to last, remembered so the next cast can go there without asking. */
data class CastTarget(
    val key: String,
    val name: String,
    /** Where it was last reached, for one discovery cannot find again (added by address). */
    val host: String?,
    val port: Int?,
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

    /**
     * One state for the whole process, however many stores are made. The
     * activities and services each make their own, and the Cast receiver runs
     * for hours in a service: a per-instance state would never see a switch
     * flipped on the settings screen.
     */
    private val _state: MutableStateFlow<Settings> = synchronized(Companion) {
        shared ?: MutableStateFlow(read()).also { shared = it }
    }
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

    fun setCastEnabled(enabled: Boolean) = updateCast(KEY_CAST_ENABLED, enabled) { copy(enabled = enabled) }

    fun setCastKeepRunning(enabled: Boolean) = updateCast(KEY_CAST_KEEP_RUNNING, enabled) { copy(keepRunning = enabled) }

    fun setCastAskForDevice(enabled: Boolean) = updateCast(KEY_CAST_ASK, enabled) { copy(askForDevice = enabled) }

    fun setCastConvertFormats(enabled: Boolean) = updateCast(KEY_CAST_CONVERT, enabled) { copy(convertFormats = enabled) }

    fun setCastLocalOnly(enabled: Boolean) = updateCast(KEY_CAST_LOCAL_ONLY, enabled) { copy(localOnly = enabled) }

    private fun updateCast(key: String, value: Boolean, change: CastSettings.() -> CastSettings) {
        val next = _state.value.cast.change()
        if (next == _state.value.cast) return
        prefs.edit { putBoolean(key, value) }
        _state.update { it.copy(cast = next) }
    }

    /** The receiver casts go to without asking, or null before one was ever chosen. */
    fun castTarget(): CastTarget? {
        val key = prefs.getString(KEY_CAST_TARGET_KEY, null) ?: return null
        return CastTarget(
            key = key,
            name = prefs.getString(KEY_CAST_TARGET_NAME, null) ?: key,
            host = prefs.getString(KEY_CAST_TARGET_HOST, null),
            port = prefs.getInt(KEY_CAST_TARGET_PORT, -1).takeIf { it > 0 },
        )
    }

    fun setCastTarget(target: CastTarget) {
        prefs.edit {
            putString(KEY_CAST_TARGET_KEY, target.key)
            putString(KEY_CAST_TARGET_NAME, target.name)
            if (target.host != null) putString(KEY_CAST_TARGET_HOST, target.host) else remove(KEY_CAST_TARGET_HOST)
            if (target.port != null) putInt(KEY_CAST_TARGET_PORT, target.port) else remove(KEY_CAST_TARGET_PORT)
        }
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
        cast = CastSettings().let { defaults ->
            CastSettings(
                enabled = prefs.getBoolean(KEY_CAST_ENABLED, defaults.enabled),
                keepRunning = prefs.getBoolean(KEY_CAST_KEEP_RUNNING, defaults.keepRunning),
                askForDevice = prefs.getBoolean(KEY_CAST_ASK, defaults.askForDevice),
                convertFormats = prefs.getBoolean(KEY_CAST_CONVERT, defaults.convertFormats),
                localOnly = prefs.getBoolean(KEY_CAST_LOCAL_ONLY, defaults.localOnly),
            )
        },
    )

    companion object {
        private const val PREFS = "settings"
        private const val KEY_CLIENT_NAME = "client_name"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"
        private const val KEY_LEGACY_KEY = "default_legacy_key_seed"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_CAST_ENABLED = "cast_enabled"
        private const val KEY_CAST_KEEP_RUNNING = "cast_keep_running"
        private const val KEY_CAST_ASK = "cast_ask_for_device"
        private const val KEY_CAST_CONVERT = "cast_convert_formats"
        private const val KEY_CAST_LOCAL_ONLY = "cast_local_only"
        private const val KEY_CAST_TARGET_KEY = "cast_target_key"
        private const val KEY_CAST_TARGET_NAME = "cast_target_name"
        private const val KEY_CAST_TARGET_HOST = "cast_target_host"
        private const val KEY_CAST_TARGET_PORT = "cast_target_port"

        @Volatile private var shared: MutableStateFlow<Settings>? = null

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
