package uz.faceguard.app.core.protection

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.MainActivity

/**
 * Background protection lifecycle seam.
 *
 * [ProtectionRuntime] is the single source of truth for whether protection is
 * active; it delegates *process* lifecycle to a launcher so the runtime can be
 * exercised without Android services (tests) and so the process-lifecycle
 * mechanism can change later without touching the runtime.
 */
interface ProtectionServiceLauncher {
    fun start()
    fun stop()
}

/** Pure rule the runtime and its tests share. */
object ProtectionServicePolicy {
    /** Background protection runs only for an enabled, signed-in session. */
    fun shouldRun(protectionEnabled: Boolean, accountId: Long?): Boolean =
        protectionEnabled && accountId != null
}

/**
 * Android implementation: keeps the process (and therefore the app-scoped
 * [ProtectionRuntime]) alive with a visible foreground service.
 *
 * Starting a foreground service can be rejected by the OS (Android 12+ restricts
 * background starts), so the call is guarded: protection then continues
 * best-effort within the app process instead of crashing, and the failure is
 * reported to logcat rather than hidden.
 */
@Singleton
class AndroidProtectionServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProtectionServiceLauncher {

    override fun start() {
        val intent = Intent(context, ProtectionForegroundService::class.java)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w(TAG, "foreground service start was not allowed", it) }
    }

    override fun stop() {
        val intent = Intent(context, ProtectionForegroundService::class.java)
        runCatching { context.stopService(intent) }
            .onFailure { Log.w(TAG, "foreground service stop failed", it) }
    }

    private companion object {
        const val TAG = "ProtectionService"
    }
}

/**
 * Returns the intent that opens the app from the ongoing service notification.
 * Kept here so the service has no navigation knowledge of its own.
 */
internal fun protectionNotificationIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
