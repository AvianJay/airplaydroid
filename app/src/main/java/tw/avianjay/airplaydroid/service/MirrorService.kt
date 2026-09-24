package tw.avianjay.airplaydroid.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import tw.avianjay.airplaydroid.MainActivity
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.mirror.AudioCapture
import tw.avianjay.airplaydroid.mirror.MirrorController
import tw.avianjay.airplaydroid.mirror.MirrorLog
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

    /**
     * Guards [stopping] and the handoff of every resource below. The setup
     * worker creates them while [finish] may run on any thread (the session's
     * listener, the projection callback, a Stop tap), so each resource is
     * published under this lock and re-checked against [stopping]: whatever is
     * created after finish() has run is released by its creator, never leaked.
     */
    private val lock = Any()
    private var stopping = false
    private var projection: MediaProjection? = null
    private var session: MirrorSession? = null
    private var encoder: ScreenEncoder? = null
    private var audio: AudioCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MirrorLog.init(this)
        if (intent?.action == ACTION_STOP) {
            finish(error = null, why = "stopped from the app or the notification")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val grant: Intent? = intent?.getParcelableExtra(EXTRA_GRANT)
        val device = MirrorController.state.value.device
        if (grant == null || device == null || synchronized(lock) { projection != null || stopping }) {
            // A duplicate start, or a restart with nothing to resume. (MirrorController
            // refuses a second request while one is active, so this is defensive.)
            if (synchronized(lock) { projection == null }) stopSelf()
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
            finish("Screen capture permission was not granted.", why = "getMediaProjection returned null")
            return START_NOT_STICKY
        }
        synchronized(lock) { projection = mp }
        // Required before createVirtualDisplay from Android 14; also how we learn
        // the user stopped sharing from the system UI.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                finish(error = null, why = "the system ended screen capture (MediaProjection.onStop)")
            }
        }, main)

        // Start audio capture now, while the consent prompt has just returned us
        // to the foreground: Android 11 refuses to start it from the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            val capture = AudioCapture.start(mp) { reason ->
                MirrorLog.write("screen audio stopped: $reason")
            }
            synchronized(lock) { audio = capture }
        }
        MirrorLog.write("start: ${device.displayName} at ${device.videoEndpoint}, audio capture=${audio != null}")

        val password = MirrorController.takePassword()
        worker.execute { connect(device, password, mp) }
        return START_NOT_STICKY
    }

    private fun connect(device: tw.avianjay.airplaydroid.protocol.AirPlayDevice, typedPassword: String?, mp: MediaProjection) {
        val endpoint = device.videoEndpoint
            ?: return finish("No address for ${device.displayName}.", why = "no endpoint")
        val store = PairingStore(this)
        try {
            var saved = store.load(device.key)
            val password = typedPassword?.takeIf { it.isNotEmpty() } ?: saved?.password

            if (saved == null) {
                if (password == null) {
                    return finish("${device.displayName} needs its AirPlay password to pair.", why = "no password")
                }
                MirrorController.setPhase(Phase.Pairing)
                saved = PairingStore.Entry(MirrorSession.pair(endpoint, password), password)
                store.save(device.key, saved)
            } else if (password != saved.password) {
                saved = PairingStore.Entry(saved.credentials, password)
                store.save(device.key, saved)
            }

            MirrorController.setPhase(Phase.Connecting)
            val opened = try {
                open(device, saved.credentials, password)
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
                open(device, fresh.credentials, password)
            }
            val adopted = synchronized(lock) { if (stopping) false else { session = opened; true } }
            if (!adopted) {
                opened.close()
                return
            }

            val (width, height) = ScreenEncoder.canvasFor(opened.receiverDisplay)
            val dpi = resources.displayMetrics.densityDpi
            val started = ScreenEncoder(mp, opened, width, height, dpi) { reason ->
                finish(reason, why = "encoder: $reason")
            }
            started.start()
            val kept = synchronized(lock) { if (stopping) false else { encoder = started; true } }
            if (!kept) {
                // finish() ran while the encoder was starting and could not see it.
                started.stop()
                return
            }

            val capture = synchronized(lock) { audio }
            if (capture != null && opened.hasAudio) {
                capture.attach(opened)
            } else if (capture != null) {
                synchronized(lock) { audio = null }
                capture.stop()
            }
            MirrorLog.write(
                "mirroring as ${width}x$height (receiver display ${opened.receiverDisplay}) to ${device.displayName}, " +
                    "audio=${opened.hasAudio}" + (opened.audioFailure?.let { " ($it)" } ?: ""),
            )
            MirrorController.setPhase(Phase.Mirroring)
        } catch (e: MirrorSession.Failure.Refused) {
            Log.w(TAG, "mirroring refused", e)
            finish(
                if (e.status == 401) "${device.displayName} did not accept that password."
                else "${device.displayName} refused mirroring (${e.message}).",
                why = "refused: ${e.message}",
            )
        } catch (e: HomeKitPairing.Failure) {
            Log.w(TAG, "pairing failed", e)
            finish(
                if (e is HomeKitPairing.Failure.Rejected && e.error == tw.avianjay.airplaydroid.protocol.pairing.Tlv8.PairError.AUTHENTICATION)
                    "${device.displayName} did not accept that password."
                else "Could not pair with ${device.displayName}: ${e.message}",
                why = "pairing: ${e.message}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "mirroring failed", e)
            finish("Could not mirror to ${device.displayName}: ${e.message}", why = "setup: $e")
        }
    }

    private fun open(
        device: tw.avianjay.airplaydroid.protocol.AirPlayDevice,
        credentials: HomeKitPairing.Credentials,
        password: String?,
    ) = MirrorSession.open(
        requireNotNull(device.videoEndpoint), credentials, password, Build.MODEL,
        withAudio = audio != null,
        features = device.airPlayTxt?.features?.raw ?: 0uL,
    ) { reason -> finish("The connection to ${device.displayName} ended: $reason", why = "session: $reason") }

    /**
     * Ends the session. Idempotent and safe from any thread. [error] is shown to
     * the user (null when they ended it themselves); [why] is recorded in
     * [MirrorLog] so a drop can be diagnosed after the fact.
     */
    private fun finish(error: String?, why: String) {
        val e: ScreenEncoder?
        val a: AudioCapture?
        val s: MirrorSession?
        val p: MediaProjection?
        synchronized(lock) {
            if (stopping) return
            stopping = true
            e = encoder; a = audio; s = session; p = projection
            encoder = null; audio = null; session = null; projection = null
        }
        MirrorLog.write("end: $why")
        // Network teardown must not run on the main thread.
        Thread({
            runCatching { e?.stop() }
            runCatching { a?.stop() }
            runCatching { s?.close() }
        }, "mirror-teardown").start()
        main.post {
            runCatching { p?.stop() }
            MirrorController.ended(error)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (synchronized(lock) { !stopping }) finish(error = null, why = "service destroyed")
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
