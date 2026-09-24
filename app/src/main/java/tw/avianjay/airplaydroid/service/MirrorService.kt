package tw.avianjay.airplaydroid.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import tw.avianjay.airplaydroid.MainActivity
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.MirrorUiState.Phase
import tw.avianjay.airplaydroid.mirror.PairingStore
import tw.avianjay.airplaydroid.mirror.ScreenEncoder
import tw.avianjay.airplaydroid.protocol.mirror.MirrorSession
import tw.avianjay.airplaydroid.protocol.pairing.HomeKitPairing
import java.util.concurrent.Executors

/**
 * Runs one screen-mirroring session: MediaProjection -> [ScreenEncoder] ->
 * [MirrorSession].
 *
 * Its own service, separate from [AirPlaySessionService], because the
 * foreground type differs: from Android 14 a MediaProjection can only be
 * obtained by a service already in the foreground as `mediaProjection`, and
 * that type may only be entered after the user granted capture consent.
 */
class MirrorService : Service() {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mirror-setup") }
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var session: MirrorSession? = null
    private var encoder: ScreenEncoder? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(error = null)
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val grant: Intent? = intent?.getParcelableExtra(EXTRA_GRANT)
        val device = MirrorController.state.value.device
        if (grant == null || device == null || projection != null) {
            // A duplicate start, or a restart with nothing to resume.
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(device.displayName),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, grant)
        if (mp == null) {
            finish("Screen capture permission was not granted.")
            return START_NOT_STICKY
        }
        projection = mp
        // Required before createVirtualDisplay from Android 14; also how we learn
        // the user stopped sharing from the system UI.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                finish(error = null)
            }
        }, main)

        val password = MirrorController.takePassword()
        worker.execute { connect(device, password, mp) }
        return START_NOT_STICKY
    }

    private fun connect(device: tw.avianjay.airplaydroid.protocol.AirPlayDevice, typedPassword: String?, mp: MediaProjection) {
        val endpoint = device.videoEndpoint ?: return finish("No address for ${device.displayName}.")
        val store = PairingStore(this)
        try {
            var saved = store.load(device.key)
            val password = typedPassword?.takeIf { it.isNotEmpty() } ?: saved?.password

            if (saved == null) {
                if (password == null) return finish("${device.displayName} needs its AirPlay password to pair.")
                MirrorController.setPhase(Phase.Pairing)
                saved = PairingStore.Entry(MirrorSession.pair(endpoint, password), password)
                store.save(device.key, saved)
            } else if (password != saved.password) {
                saved = PairingStore.Entry(saved.credentials, password)
                store.save(device.key, saved)
            }

            MirrorController.setPhase(Phase.Connecting)
            val opened = try {
                open(endpoint, saved.credentials, password)
            } catch (e: HomeKitPairing.Failure) {
                // The receiver no longer knows us (reset, or pairing removed in
                // its settings). Pair again once, if we can.
                Log.w(TAG, "pair-verify failed, re-pairing", e)
                store.forget(device.key)
                if (password == null) throw e
                MirrorController.setPhase(Phase.Pairing)
                val fresh = PairingStore.Entry(MirrorSession.pair(endpoint, password), password)
                store.save(device.key, fresh)
                MirrorController.setPhase(Phase.Connecting)
                open(endpoint, fresh.credentials, password)
            }
            if (stopping) {
                opened.close()
                return
            }
            session = opened

            val (width, height) = screenSize()
            val (encodedWidth, encodedHeight) = ScreenEncoder.encodedSize(width, height)
            val dpi = resources.displayMetrics.densityDpi
            encoder = ScreenEncoder(mp, opened, encodedWidth, encodedHeight, dpi) { reason ->
                finish(reason)
            }.also { it.start() }
            Log.i(TAG, "mirroring ${width}x$height as ${encodedWidth}x$encodedHeight to ${device.displayName}")
            MirrorController.setPhase(Phase.Mirroring)
        } catch (e: MirrorSession.Failure.Refused) {
            Log.w(TAG, "mirroring refused", e)
            finish(
                if (e.status == 401) "${device.displayName} did not accept that password."
                else "${device.displayName} refused mirroring (${e.message})."
            )
        } catch (e: HomeKitPairing.Failure) {
            Log.w(TAG, "pairing failed", e)
            finish(
                if (e is HomeKitPairing.Failure.Rejected && e.error == tw.avianjay.airplaydroid.protocol.pairing.Tlv8.PairError.AUTHENTICATION)
                    "${device.displayName} did not accept that password."
                else "Could not pair with ${device.displayName}: ${e.message}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "mirroring failed", e)
            finish("Could not mirror to ${device.displayName}: ${e.message}")
        }
    }

    private fun open(endpoint: tw.avianjay.airplaydroid.protocol.Endpoint, credentials: HomeKitPairing.Credentials, password: String?) =
        MirrorSession.open(endpoint, credentials, password, Build.MODEL) { reason -> finish(reason) }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        return metrics.widthPixels to metrics.heightPixels
    }

    /** Idempotent. [error] null means the user ended it. Safe from any thread. */
    private fun finish(error: String?) {
        if (stopping) return
        stopping = true
        main.post {
            val e = encoder
            val s = session
            val p = projection
            encoder = null
            session = null
            projection = null
            // Network teardown must not run on the main thread.
            Thread({
                runCatching { e?.stop() }
                runCatching { s?.close() }
            }, "mirror-teardown").start()
            runCatching { p?.stop() }
            MirrorController.ended(error)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!stopping) finish(error = null)
        worker.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mirror_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(deviceName: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.mirror_notification_title, deviceName))
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            0,
            getString(R.string.action_stop),
            PendingIntent.getService(
                this, 1,
                Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "airplay_mirroring"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "tw.avianjay.airplaydroid.action.STOP_MIRROR"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_GRANT = "grant"

        fun start(context: Context, resultCode: Int, grant: Intent) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_GRANT, grant)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
