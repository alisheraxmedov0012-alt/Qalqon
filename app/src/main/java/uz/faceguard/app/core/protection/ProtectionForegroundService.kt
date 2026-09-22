package uz.faceguard.app.core.protection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uz.faceguard.app.R

/**
 * Background protection foundation.
 *
 * Responsibility is *only* the process lifecycle: it keeps the app process alive
 * (so the app-scoped [ProtectionRuntime] and its foreground-app monitoring keep
 * running while Qalqon is not on screen) and surfaces an honest, user-visible
 * ongoing notification. It contains no policy logic, no recognition, no
 * database access and creates no second runtime — everything goes through the
 * injected singleton [ProtectionRuntime], exactly like the UI does.
 *
 * Declared as `specialUse`: the service does not run the camera (background
 * camera use is restricted by the platform), so it deliberately does not claim
 * the `camera` foreground-service type.
 *
 * Limitation (documented, not hidden): with the app in the background there is
 * no camera-bound frame source, so recognition observes "no face" and follows the
 * configured no-face policy. System-wide enforcement therefore still needs the
 * AccessibilityService stage; this service is the lifecycle foundation for it.
 */
@AndroidEntryPoint
class ProtectionForegroundService : Service() {

    @Inject lateinit var runtime: ProtectionRuntime

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Promote to foreground first: if the OS rejects it the service crashes
        // here instead of silently reporting itself as running.
        startForeground(NOTIFICATION_ID, buildNotification())
        _running.value = true
        runtime.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: repeated starts keep exactly one runtime and one notification.
        runtime.start()
        startForeground(NOTIFICATION_ID, buildNotification())
        // Not sticky: a system restart from the background would be rejected by
        // Android 12+ start restrictions, so no guaranteed resurrection is claimed.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _running.value = false
        // Clean, idempotent deactivation: stops overlay/monitor/engine but keeps
        // the runtime's settings/account observers, which stay the source of truth.
        runtime.stop()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.protection_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.protection_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.protection_service_notification_title))
        .setContentText(getString(R.string.protection_service_notification_text))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                protectionNotificationIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "protection_service"

        private val _running = MutableStateFlow(false)

        /**
         * Lifecycle observability: true while the service is alive. Used by the
         * UI-less lifecycle tests to assert start/stop without relying on
         * restricted ActivityManager APIs.
         */
        val running: StateFlow<Boolean> = _running
    }
}
