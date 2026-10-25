package app.zsync.link

import app.zsync.log.QuickLog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.zsync.MainActivity
import app.zsync.R

/**
 * Keeps the camera session alive while Z-Sync is in the background.
 *
 * The PTP session and the `WifiNetworkSpecifier` request both live in this process. Without a
 * foreground service Android is free to put the process in the cached freezer as soon as the
 * activity goes to the background, which drops the request, makes the phone leave the camera
 * hotspot, and the camera turns its Wi-Fi off. Holding a connected-device foreground service keeps
 * the process running, so the transfer and the idle heartbeat carry on.
 *
 * The service owns no session state: it only holds the process up and shows what the app is doing.
 */
class CameraSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                build(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (denied: RuntimeException) {
            // Android 14+ can refuse the type when the prerequisites are missing. Failing to go
            // foreground must not take the app down; the session just stays unprotected.
            QuickLog.e(LOG_TAG, "foreground start refused ${denied.javaClass.simpleName}: ${denied.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        // A restart would only show a notification for a session that no longer exists.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "相机连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与相机的连接，传图时不被系统挂起"
                setShowBadge(false)
            },
        )
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zsync)
            .setContentTitle("Z-Sync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "camera-session"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"
        const val DEFAULT_TEXT = "正在运行"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, text: String = DEFAULT_TEXT) {
            val intent = Intent(context, CameraSessionService::class.java).putExtra(EXTRA_TEXT, text)
            // Starting from the background is refused on Android 12+; the caller runs this while
            // the activity is visible, but never let a refusal crash the app.
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { QuickLog.e(LOG_TAG, "foreground start failed ${it.javaClass.simpleName}: ${it.message}") }
        }

        /** Update the notification line, for example while saving. Ignored when not running. */
        fun update(context: Context, text: String) {
            if (!running) return
            val intent = Intent(context, CameraSessionService::class.java).putExtra(EXTRA_TEXT, text)
            // A save can keep running while the app is off screen, where a plain startService is
            // refused; the service is already in the foreground, so the FGS path is allowed.
            runCatching { context.startService(intent) }
                .recoverCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { QuickLog.e(LOG_TAG, "notification update failed ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            QuickLog.i(LOG_TAG, "foreground service stopped")
            context.stopService(Intent(context, CameraSessionService::class.java))
        }
    }
}
