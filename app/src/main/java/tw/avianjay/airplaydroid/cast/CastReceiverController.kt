package tw.avianjay.airplaydroid.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.avianjay.airplaydroid.settings.SettingsStore

/**
 * Decides when [CastReceiverService] runs, from the settings and what is on
 * screen:
 *
 * | Receiver | Keep running | Runs                                               |
 * |----------|--------------|----------------------------------------------------|
 * | off      | –            | never                                              |
 * | on       | on           | always, and again after a reboot or an app update  |
 * | on       | off          | while one of the app's screens is on, and for as long as a cast it accepted lasts |
 *
 * [update] is called whenever any input changes; it is idempotent.
 */
object CastReceiverController {

    private const val TAG = "CastReceiverController"

    /** What the settings screen says about the receiver. */
    sealed interface Status {
        data object Off : Status
        data object Starting : Status
        data class Listening(val port: Int) : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    internal fun setStatus(status: Status) {
        _status.value = status
    }

    /** Set as soon as a start is asked for, so two quick updates cannot start it twice. */
    @Volatile private var running = false
    private var visibleHosts = 0

    /** Whether one of the app's screens is on: then the chooser may simply be started. */
    val hasVisibleHost: Boolean get() = visibleHosts > 0

    fun onHostStarted(context: Context) {
        visibleHosts++
        update(context)
    }

    fun onHostStopped(context: Context) {
        visibleHosts = (visibleHosts - 1).coerceAtLeast(0)
        update(context)
    }

    fun update(context: Context) {
        val cast = SettingsStore(context).state.value.cast
        val wanted = cast.enabled && (cast.keepRunning || visibleHosts > 0 || CastBridge.busy)
        val app = context.applicationContext
        if (wanted && !running) {
            running = true
            // Android 12+ refuses a foreground-service start from the background;
            // every caller that can want one is in the foreground or is the boot
            // broadcast, which is exempt, but a refusal must not crash the app.
            runCatching { app.startForegroundService(Intent(app, CastReceiverService::class.java)) }
                .onFailure {
                    Log.w(TAG, "could not start the Cast receiver", it)
                    running = false
                    _status.value = Status.Failed(it.message ?: it.javaClass.simpleName)
                }
        } else if (!wanted && running) {
            running = false
            app.stopService(Intent(app, CastReceiverService::class.java))
        }
    }

    internal fun onServiceCreated() {
        running = true
    }

    /** The service went away by itself (killed, or stopped from its notification). */
    internal fun onServiceGone() {
        running = false
        _status.value = Status.Off
    }
}

/**
 * Starts the receiver after a reboot or an app update, when it is set to keep
 * running. `connectedDevice` is not among the foreground-service types Android 15
 * forbids from `BOOT_COMPLETED`, so this is allowed.
 */
class CastBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CastReceiverController.update(context)
        }
    }
}
