package uz.faceguard.app.core.protection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uz.faceguard.app.R

/**
 * Background protection foundation and — since Phase 7.1 — the owner of the
 * process-scoped camera session.
 *
 * It keeps the app process alive (so the app-scoped [ProtectionRuntime] and its
 * foreground-app monitoring keep running while Qalqon is not on screen), surfaces
 * an honest, user-visible ongoing notification, and owns the
 * [ProtectionCameraSession] that feeds the shared recognizer. It contains no
 * policy logic and no second runtime — everything goes through the injected
 * singleton [ProtectionRuntime], exactly like the UI does.
 *
 * Camera foreground-service type (Android 14+): the service declares
 * `camera|specialUse`. It is promoted to the `camera` type only in the legal
 * while-in-use foreground moment in which the camera session actually starts, and
 * only when the camera permission is held, because starting a camera-type
 * foreground service from the background is prohibited. The base (specialUse)
 * promotion never claims the camera, so enabling protection without the camera
 * permission cannot crash the service.
 */
@AndroidEntryPoint
class ProtectionForegroundService : Service() {

    @Inject lateinit var runtime: ProtectionRuntime
    @Inject lateinit var cameraSession: ProtectionCameraSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * True while the service holds the camera foreground type. Kept so a duplicate
     * start can re-assert the foreground state without *dropping* the camera type
     * under a running session.
     */
    @Volatile
    private var cameraTypeClaimed = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Promote to foreground first with the base type only: if the OS rejects it
        // the service crashes here instead of silently reporting itself as running.
        startForegroundCompat(includeCamera = false)
        _running.value = true
        // Stage 5: re-derive the active session from the persisted intent. On a
        // same-process recreate this re-activates protection that a previous
        // onDestroy had deactivated; on a system restart it rebuilds the runtime.
        runtime.onServiceStarted()
        // Stage 5: the bounded camera-rebind retries run on the service scope.
        cameraSession.attachRecoveryScope(scope)
        // A background start (e.g. the post-boot restore) cannot claim the camera
        // foreground type until a legal while-in-use moment, so recognition is limited
        // until the app is next opened. Report that honestly; a foreground start
        // leaves readiness untouched so the UI never flashes a false warning.
        if (!runtime.uiForeground.value) runtime.setCameraForegroundReady(false)
        observeCameraSession()
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: repeated starts keep exactly one runtime and one notification,
        // and never downgrade the camera type held by a live camera session.
        runtime.onServiceStarted()
        startForegroundCompat(includeCamera = cameraTypeClaimed)
        // Stage 5: a null intent means the system recreated the service after killing
        // the process (sticky restart). Protection should have been enabled when that
        // happened, but confirm once the persisted settings are observed, and end a
        // restart that is no longer wanted instead of showing a false "protected".
        if (intent == null) {
            scope.launch {
                runtime.state.first { it.settingsLoaded }
                if (!runtime.protectionWanted()) stopSelf()
            }
        }
        // Stage 5: START_STICKY lets the OS recreate the service (and the runtime) after
        // an OS-initiated process kill. It is best-effort, never a force-stop bypass, and
        // disabling protection stops the service so it is not restarted.
        return if (ProtectionServiceLifecyclePolicy.restartMode == ServiceRestartMode.STICKY) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * Stage 5: Recents removal must not end protection. Android keeps a started
     * service running and only notifies it here; QALQON relies on that default, so
     * this is intentionally a no-op — calling `stopSelf()` (or declaring
     * `android:stopWithTask="true"`) would let a child disable protection by
     * swiping the app away.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ProtectionServiceLifecyclePolicy.shouldStopOnTaskRemoved()) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterScreenStateReceiver()
        // Close the process-scoped camera session before the process lifecycle goes.
        scope.cancel()
        cameraSession.stop()
        _running.value = false
        // Clean, idempotent deactivation: stops overlay/monitor/engine but keeps
        // the runtime's settings/account observers, which stay the source of truth.
        runtime.stop()
        super.onDestroy()
    }

    /** Stage 5: the runtime-registered screen-state receiver, unregistered with the service. */
    private var screenStateReceiver: BroadcastReceiver? = null

    /**
     * Stage 5: screen off/on handling. Registered at runtime (not in the manifest) so
     * it only exists while the service — and therefore protection — is alive, and is
     * unregistered with the service, so it can neither leak nor fire when protection
     * is off. It never bypasses a platform restriction: it only releases/rebinds the
     * camera of a session that is already latched.
     */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> runtime.onScreenOff()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> runtime.onScreenOn()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenStateReceiver = runCatching {
            registerReceiver(receiver, filter)
            receiver
        }.getOrNull()
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
    }

    /**
     * Drives the camera session from the single source of truth: protection active
     * ([ProtectionRuntime.state]) plus whether the UI is visible
     * ([ProtectionRuntime.uiForeground]). The permission is re-read here so losing
     * or granting it is observed with the next change.
     */
    private fun observeCameraSession() {
        scope.launch {
            combine(
                runtime.state.map { it.active }.distinctUntilChanged(),
                runtime.uiForeground,
            ) { active, foreground -> active to foreground }
                .distinctUntilChanged()
                .collect { (active, foreground) -> reconcileCameraSession(active, foreground) }
        }
    }

    private fun reconcileCameraSession(protectionActive: Boolean, uiForeground: Boolean) {
        val granted = hasCameraPermission()
        val willStart = !cameraSession.isActive && CameraSessionPolicy.shouldRun(
            protectionActive = protectionActive,
            uiForeground = uiForeground,
            cameraGranted = granted,
            sessionRunning = false,
        )
        // Claim the camera type in the same legal foreground moment the session
        // starts; Android 14 requires the while-in-use camera capability to be held
        // at that point.
        if (willStart) promoteToCameraForeground()
        cameraSession.reconcile(protectionActive, uiForeground, granted)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best-effort promotion to the camera foreground type. A failure is logged, not
     * fatal: the session keeps the base type and the camera bind reports its own
     * error if it cannot run.
     */
    private fun promoteToCameraForeground() {
        runCatching {
            startForegroundCompat(includeCamera = true)
            cameraTypeClaimed = true
        }.onSuccess {
            // The camera type is held now, so recognition is no longer limited.
            runtime.setCameraForegroundReady(true)
        }.onFailure { Log.w(TAG, "camera foreground type could not be claimed", it) }
    }

    private fun startForegroundCompat(includeCamera: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundTypes(includeCamera),
        )
    }

    /**
     * The runtime type bitmask. `specialUse` is only a real type from API 34; the
     * camera type from API 30. Below those the corresponding bit is omitted so the
     * platform is never handed a type it does not define.
     */
    private fun foregroundTypes(includeCamera: Boolean): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (includeCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
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
        private const val TAG = "ProtectionService"

        private val _running = MutableStateFlow(false)

        /**
         * Lifecycle observability: true while the service is alive. Used by the
         * UI-less lifecycle tests to assert start/stop without relying on
         * restricted ActivityManager APIs.
         */
        val running: StateFlow<Boolean> = _running
    }
}
