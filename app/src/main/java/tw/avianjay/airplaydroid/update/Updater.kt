package tw.avianjay.airplaydroid.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.avianjay.airplaydroid.BuildConfig
import tw.avianjay.airplaydroid.protocol.update.InstalledVersion
import tw.avianjay.airplaydroid.protocol.update.UpdateChannel
import tw.avianjay.airplaydroid.protocol.update.UpdateDecision
import tw.avianjay.airplaydroid.protocol.update.UpdateOutcome
import tw.avianjay.airplaydroid.protocol.update.UpdateRelease
import tw.avianjay.airplaydroid.protocol.update.UpdateSelector
import java.io.File

/** What the update row in Settings is showing. */
data class UpdateUiState(
    val phase: Phase = Phase.Idle,
    val installedVersionName: String = "",
    val installedVersionCode: Int = 0,
    val release: UpdateRelease? = null,
    val outcome: UpdateOutcome = UpdateOutcome.DISABLED_OR_EMPTY,
    /** 0f..1f, or null when the server did not state a total size. */
    val progress: Float? = null,
    val error: String? = null,
) {
    enum class Phase {
        Idle,

        /** Fetching the manifest. */
        Checking,

        /** A newer build is known and the user can start the download. */
        Available,

        Downloading,

        /** Downloaded and verified; the installer has been launched. */
        ReadyToInstall,

        /** Nothing newer, or the channel is off. */
        UpToDate,

        Failed,
    }

    val busy: Boolean get() = phase == Phase.Checking || phase == Phase.Downloading
}

/**
 * The in-app updater: check, download, verify, hand to the installer.
 *
 * Process-scoped like the other controllers, so a download survives the settings
 * screen being left and re-entered -- and, more importantly, so a rotation does
 * not restart it.
 *
 * The channel is passed in on every call rather than held here. The setting
 * lives in [tw.avianjay.airplaydroid.settings.SettingsStore], and one copy of it
 * is the only way the radio group and the check can never disagree.
 */
object Updater {

    private const val TAG = "Updater"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * Records what is installed. Called once from the settings screen, because
     * `PackageInfo` is a main-thread read and the row needs it before any check.
     */
    fun recordInstalled(context: Context) {
        val installed = installedVersion(context)
        _state.update {
            it.copy(
                installedVersionName = installed.versionName.orEmpty(),
                installedVersionCode = installed.versionCode,
            )
        }
    }

    /** Re-reads the manifest for [channel]. Safe to call repeatedly. */
    fun check(context: Context, channel: UpdateChannel) {
        checkJob?.cancel()
        downloadJob?.cancel()

        if (channel.isOff) {
            _state.update {
                it.copy(
                    phase = UpdateUiState.Phase.Idle,
                    release = null,
                    outcome = UpdateOutcome.DISABLED_OR_EMPTY,
                    progress = null,
                    error = null,
                )
            }
            return
        }

        val installed = installedVersion(context)
        _state.update {
            it.copy(
                phase = UpdateUiState.Phase.Checking,
                installedVersionName = installed.versionName.orEmpty(),
                installedVersionCode = installed.versionCode,
                release = null,
                progress = null,
                error = null,
            )
        }

        checkJob = scope.launch {
            val client = UpdateClient(BuildConfig.UPDATE_REPOSITORY)

            val result = runCatching { client.fetch(channel) }
            result.onSuccess { releases ->
                val decision: UpdateDecision = UpdateSelector.select(channel, installed, releases)
                _state.update {
                    it.copy(
                        phase = if (decision.available) {
                            UpdateUiState.Phase.Available
                        } else {
                            UpdateUiState.Phase.UpToDate
                        },
                        release = decision.release,
                        outcome = decision.outcome,
                        error = null,
                    )
                }
            }.onFailure { e ->
                // Cancellation is not a failure. Switching channel cancels the
                // in-flight check, and without this the cancelled coroutine would
                // write a "Failed" state *after* the new check had already set
                // "Checking" -- leaving the row stuck on an error for a request
                // nobody made.
                if (e is CancellationException) return@onFailure

                if (e is UpdateClient.NotPublished) {
                    // Nothing on this channel yet. Not an error: the UI reports
                    // it as "nothing published" rather than a failed check.
                    Log.i(TAG, "nothing published on $channel")
                    _state.update {
                        it.copy(
                            phase = UpdateUiState.Phase.UpToDate,
                            release = null,
                            outcome = UpdateOutcome.DISABLED_OR_EMPTY,
                            error = null,
                        )
                    }
                    return@onFailure
                }
                Log.i(TAG, "update check failed", e)
                _state.update {
                    it.copy(
                        phase = UpdateUiState.Phase.Failed,
                        release = null,
                        error = e.message ?: "Could not check for updates.",
                    )
                }
            }
        }
    }

    /**
     * Downloads the known release, verifies it, and launches the installer.
     *
     * The install intent is started from [context] after the download completes,
     * so a caller that has left the screen still gets the prompt.
     */
    fun downloadAndInstall(context: Context, release: UpdateRelease) {
        if (_state.value.phase == UpdateUiState.Phase.Downloading) return

        downloadJob?.cancel()
        _state.update { it.copy(phase = UpdateUiState.Phase.Downloading, progress = null, error = null) }

        val appContext = context.applicationContext
        downloadJob = scope.launch {
            val directory = File(appContext.cacheDir, DOWNLOAD_DIR)
            val downloader = ApkDownloader()

            // Bounded at one APK: a previous attempt's leftovers are removed
            // before this one writes anything.
            pruneDownloads(appContext, keep = downloader.fileNameFor(release))

            val result = runCatching {
                downloader.download(release, directory) { written, total ->
                    // The download loop is blocking and cannot see the coroutine's
                    // cancellation on its own, so this callback -- invoked once per
                    // chunk -- is where a cancelled download actually stops.
                    // Without it, switching channel mid-download would keep
                    // pulling megabytes nobody is waiting for.
                    ensureActive()
                    val fraction = total?.takeIf { it > 0 }?.let { written.toFloat() / it }
                    _state.update { it.copy(progress = fraction?.coerceIn(0f, 1f)) }
                }
            }

            result.onSuccess { apk ->
                _state.update { it.copy(phase = UpdateUiState.Phase.ReadyToInstall, progress = 1f) }
                // The installer is another app; on the main thread so the
                // activity transition is not started from a background thread.
                withContext(Dispatchers.Main) {
                    runCatching { ApkInstaller.install(appContext, apk) }
                        .onFailure { e ->
                            Log.w(TAG, "could not launch the installer", e)
                            _state.update {
                                it.copy(
                                    phase = UpdateUiState.Phase.Failed,
                                    error = "Downloaded the update, but could not open the installer. " +
                                        "Allow this app to install unknown apps, then try again.",
                                )
                            }
                        }
                }
            }.onFailure { e ->
                // Cancelled by a newer check or a fresh download; the state now
                // belongs to that one, so this must not overwrite it.
                if (e is CancellationException) return@onFailure

                Log.w(TAG, "download failed", e)
                _state.update {
                    it.copy(
                        phase = UpdateUiState.Phase.Failed,
                        progress = null,
                        error = e.message ?: "The download failed.",
                    )
                }
            }
        }
    }

    /** Clears a failure so the row returns to its last good state. */
    fun dismissError() {
        _state.update {
            it.copy(
                phase = if (it.release != null) UpdateUiState.Phase.Available else UpdateUiState.Phase.Idle,
                error = null,
            )
        }
    }

    /**
     * Deletes everything in the downloads directory except [keep].
     *
     * Called before each download rather than on shutdown: a process can be
     * killed without running any cleanup, so a "delete on exit" would leak the
     * very files this is meant to remove. Pruning at the start of a download
     * bounds the directory at one APK plus at most one `.part`.
     *
     * [keep] is the file about to be written; it is excluded so a retry of the
     * same release does not delete the very download it is resuming from.
     */
    fun pruneDownloads(context: Context, keep: String? = null) {
        runCatching {
            File(context.applicationContext.cacheDir, DOWNLOAD_DIR)
                .listFiles()
                ?.filter { it.name != keep }
                ?.forEach { it.delete() }
        }
    }

    private fun installedVersion(context: Context): InstalledVersion {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
        return InstalledVersion(versionCode = code, versionName = info.versionName)
    }

    private const val DOWNLOAD_DIR = "updates"
}
