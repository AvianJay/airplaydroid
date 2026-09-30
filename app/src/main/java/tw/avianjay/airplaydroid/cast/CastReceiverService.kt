package tw.avianjay.airplaydroid.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tw.avianjay.airplaydroid.CastTargetActivity
import tw.avianjay.airplaydroid.MainActivity
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.mirror.MirrorLog
import tw.avianjay.airplaydroid.protocol.cast.CastDeviceInfo
import tw.avianjay.airplaydroid.protocol.cast.CastIdentitySource
import tw.avianjay.airplaydroid.protocol.cast.CastReceiver
import tw.avianjay.airplaydroid.protocol.cast.CastServer
import tw.avianjay.airplaydroid.protocol.cast.LocalAddresses
import tw.avianjay.airplaydroid.protocol.media.MediaHttpServer
import tw.avianjay.airplaydroid.settings.SettingsStore
import java.io.File
import kotlin.concurrent.thread

/**
 * The Chromecast receiver: the Cast listener, the phone's media server, and the
 * `_googlecast._tcp` advertisement that makes senders list this phone.
 *
 * A foreground service of type `connectedDevice` -- a network service for
 * other devices, which is what the type is for. Its prerequisite is met by
 * declaring `CHANGE_WIFI_MULTICAST_STATE`. It is foreground even when it only
 * runs while the app is open, because a cast accepted then must survive the
 * user switching to the sender app, which is exactly what they do next.
 *
 * When [CastReceiverController] runs it is decided there; this class only
 * starts, serves and stops.
 */
class CastReceiverService : Service(), CastBridge.Host {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { SettingsStore(this) }

    private val lock = Any()
    private var destroyed = false
    private var castServer: CastServer? = null
    private var media: MediaHttpServer? = null
    private var device: CastDeviceInfo? = null
    private var port = -1
    private var registration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MirrorLog.init(this)
        createChannels()
        enterForeground()
        // Also after a START_STICKY restart in a new process, where no update() asked for it.
        CastReceiverController.onServiceCreated()
        CastReceiverController.setStatus(CastReceiverController.Status.Starting)
        // RSA key generation and binding take a moment; not on the main thread.
        thread(name = "cast-start") { startServers() }

        // The notification follows the cast, and the advertisement follows the name.
        scope.launch {
            combine(CastBridge.state, settings.state) { _, _ -> Unit }.collect {
                notificationManager()?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
        scope.launch {
            settings.state.map { it.clientName }.distinctUntilChanged().collect { name ->
                val current = synchronized(lock) { device } ?: return@collect
                if (current.friendlyName != name) {
                    val renamed = current.copy(friendlyName = name)
                    synchronized(lock) { device = renamed }
                    thread(name = "cast-rename") { unregister(); register(renamed) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start, not only the first: startForegroundService must be answered each time.
        enterForeground()
        when (intent?.action) {
            ACTION_STOP_CAST -> CastBridge.cancel()
            ACTION_TURN_OFF -> {
                CastBridge.cancel()
                settings.setCastEnabled(false)
                CastReceiverController.update(this)
            }
        }
        return if (settings.state.value.cast.keepRunning) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) { destroyed = true }
        scope.cancel()
        CastBridge.detach()
        val server = synchronized(lock) { castServer.also { castServer = null } }
        val mediaServer = synchronized(lock) { media.also { media = null } }
        thread(name = "cast-stop") {
            unregister()
            runCatching { server?.close() }
            runCatching { mediaServer?.close() }
        }
        notificationManager()?.cancel(REQUEST_NOTIFICATION_ID)
        CastReceiverController.onServiceGone()
        MirrorLog.write("cast: receiver stopped")
        super.onDestroy()
    }

    private fun startServers() {
        try {
            // The certificate lives for two days and is replaced daily: senders
            // refuse a long-lived one. Made fresh on every start, never stored.
            File(noBackupFilesDir, LEGACY_IDENTITY_FILE).delete()
            val identities = CastIdentitySource("AirPlayDroid")
            val info = CastDeviceInfo(id = loadDeviceId(), friendlyName = settings.clientName())
            val receiver = CastReceiver(identities.prepare(), CastBridge, log = { MirrorLog.write("cast: $it") })
            // Read on every connection, so switching "only this phone" takes effect at once.
            val server = CastServer(
                receiver = receiver,
                identities = identities::current,
                accept = { address -> !settings.state.value.cast.localOnly || LocalAddresses.isOwnAddress(address) },
                log = { MirrorLog.write("cast: $it") },
            )
            val bound = server.start(CastServer.DEFAULT_PORT)
            val mediaServer = MediaHttpServer(log = { MirrorLog.write("cast media: $it") })
            mediaServer.start()

            val keep = synchronized(lock) {
                if (destroyed) false else {
                    castServer = server; media = mediaServer; device = info; port = bound
                    true
                }
            }
            if (!keep) {
                server.close()
                mediaServer.close()
                return
            }
            CastBridge.attach(this, receiver, mediaServer, this)
            register(info)
            MirrorLog.write("cast: receiver listening on $bound as \"${info.friendlyName}\"" +
                if (settings.state.value.cast.localOnly) " (this phone only)" else "")
            CastReceiverController.setStatus(CastReceiverController.Status.Listening(bound))
        } catch (e: Exception) {
            MirrorLog.write("cast: receiver failed to start: $e")
            CastReceiverController.setStatus(CastReceiverController.Status.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    // --------------------------------------------------------- advertisement

    private fun register(info: CastDeviceInfo) {
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val bound = synchronized(lock) { port }
        if (bound <= 0) return
        val service = NsdServiceInfo().apply {
            serviceName = info.serviceName
            serviceType = SERVICE_TYPE
            port = bound
            info.txtRecord().forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) =
                MirrorLog.write("cast: advertised as ${registered.serviceName}")
            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) =
                MirrorLog.write("cast: advertisement failed ($errorCode)")
            override fun onServiceUnregistered(unregistered: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) = Unit
        }
        synchronized(lock) {
            if (destroyed) return
            registration = listener
        }
        runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { MirrorLog.write("cast: registerService threw $it") }
    }

    private fun unregister() {
        val listener = synchronized(lock) { registration.also { registration = null } } ?: return
        runCatching { getSystemService(NsdManager::class.java)?.unregisterService(listener) }
    }

    // ------------------------------------------------------------ identity

    /** Senders key their device lists on the id; a new one each start would list this phone twice. */
    private fun loadDeviceId(): String {
        val file = File(noBackupFilesDir, DEVICE_ID_FILE)
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.length == 32 }?.let { return it }
        return CastDeviceInfo.newId().also { id -> runCatching { file.writeText(id) } }
    }

    // --------------------------------------------------------- CastBridge.Host

    override fun askForTarget() {
        // Starting an activity from the background is allowed with "display over
        // other apps", or while one of the app's own screens is showing. Without
        // either, Android drops the start silently, so a notification asks instead.
        if (Settings.canDrawOverlays(this) || CastReceiverController.hasVisibleHost) {
            runCatching { startActivity(CastTargetActivity.intent(this)) }
                .onFailure { postRequestNotification() }
        } else {
            postRequestNotification()
        }
    }

    override fun onCastChanged() {
        scope.launch {
            if (!CastBridge.state.value.choosing) notificationManager()?.cancel(REQUEST_NOTIFICATION_ID)
            // Not keeping running: a cast that has ended is the service's last reason to stay.
            CastReceiverController.update(this@CastReceiverService)
        }
    }

    // --------------------------------------------------------- notifications

    private fun enterForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun buildNotification(): Notification {
        val cast = CastBridge.state.value
        val name = settings.clientName()
        val casting = cast.target != null && cast.playing
        val title = when {
            casting -> getString(R.string.cast_notification_casting, cast.title ?: getString(R.string.cast_untitled), cast.target!!.displayName)
            cast.choosing -> getString(R.string.cast_notification_choosing)
            else -> getString(R.string.cast_notification_title)
        }
        val text = getString(
            if (settings.state.value.cast.localOnly) R.string.cast_notification_body_local else R.string.cast_notification_body,
            name,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    if (cast.choosing) CastTargetActivity.intent(this)
                    else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        if (cast.sessionActive) {
            builder.addAction(0, getString(R.string.cast_action_stop), serviceIntent(ACTION_STOP_CAST, 1))
        }
        builder.addAction(0, getString(R.string.cast_action_turn_off), serviceIntent(ACTION_TURN_OFF, 2))
        return builder.build()
    }

    private fun postRequestNotification() {
        val cast = CastBridge.state.value
        val notification = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_cast)
            .setContentTitle(getString(R.string.cast_request_title))
            .setContentText(cast.title?.let { getString(R.string.cast_request_body_titled, it) } ?: getString(R.string.cast_request_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 3, CastTargetActivity.intent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(0, getString(R.string.action_cancel), serviceIntent(ACTION_STOP_CAST, 4))
            .build()
        notificationManager()?.notify(REQUEST_NOTIFICATION_ID, notification)
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, CastReceiverService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannels() {
        val manager = notificationManager() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.cast_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        // High importance, so it arrives as a heads-up the user can tap straight from the sender app.
        manager.createNotificationChannel(
            NotificationChannel(REQUEST_CHANNEL_ID, getString(R.string.cast_request_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val SERVICE_TYPE = "_googlecast._tcp"
        private const val CHANNEL_ID = "cast_receiver"
        private const val REQUEST_CHANNEL_ID = "cast_requests"
        private const val NOTIFICATION_ID = 3
        private const val REQUEST_NOTIFICATION_ID = 4
        private const val ACTION_STOP_CAST = "tw.avianjay.airplaydroid.action.STOP_CAST"
        private const val ACTION_TURN_OFF = "tw.avianjay.airplaydroid.action.CAST_OFF"
        /** Where the first version kept a twenty-year certificate senders refused; removed on sight. */
        private const val LEGACY_IDENTITY_FILE = "cast-identity"
        private const val DEVICE_ID_FILE = "cast-device-id"
    }
}
