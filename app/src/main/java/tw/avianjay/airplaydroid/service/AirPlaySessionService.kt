package tw.avianjay.airplaydroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.avianjay.airplaydroid.MainActivity
import tw.avianjay.airplaydroid.R
import tw.avianjay.airplaydroid.discovery.DiscoveryRepository
import tw.avianjay.airplaydroid.discovery.NsdDeviceDiscovery

/**
 * Owns mDNS discovery, and later the RTSP session.
 *
 * Foreground type is `connectedDevice`, chosen deliberately:
 *  - `dataSync` is capped at 6 hours per 24 and gets killed via Service.onTimeout.
 *  - `mediaProjection` requires screen-capture consent before startForeground.
 *
 * Its runtime prerequisite is satisfied purely by declaring
 * CHANGE_WIFI_MULTICAST_STATE (a normal, install-time permission), so this starts
 * with zero runtime prompts. POST_NOTIFICATIONS being denied only hides the
 * notification -- it never prevents the service from running.
 */
class AirPlaySessionService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        startDiscovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // NOT sticky: discovery holds no state worth restoring, and a silent
        // system restart would resurrect mDNS scanning and an ongoing
        // notification with no UI to dismiss them. MainActivity restarts the
        // service on next launch.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // lifecycleScope is cancelled for us, which closes the callbackFlow and
        // tears down every DiscoveryListener and ServiceInfoCallback.
        DiscoveryRepository.clear()
        super.onDestroy()
    }

    private fun startDiscovery() {
        val nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager unavailable on this device")
            return
        }

        val discovery = NsdDeviceDiscovery(nsdManager)
        lifecycleScope.launch {
            discovery.events().collect(DiscoveryRepository::apply)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.discovery_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.discovery_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.discovery_notification_title))
        .setContentText(getString(R.string.discovery_notification_text))
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        // Gives the user a way to end discovery without uninstalling the app.
        .addAction(
            0,
            getString(R.string.discovery_notification_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, AirPlaySessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "AirPlaySessionService"
        private const val CHANNEL_ID = "airplay_discovery"
        const val ACTION_STOP = "tw.avianjay.airplaydroid.action.STOP"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // minSdk is 26, so startForegroundService is always available.
            context.startForegroundService(Intent(context, AirPlaySessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlaySessionService::class.java))
        }
    }
}
