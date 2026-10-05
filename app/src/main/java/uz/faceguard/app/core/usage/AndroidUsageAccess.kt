package uz.faceguard.app.core.usage

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Phase 4 Step 1B-4: whether QALQON has been granted Usage Access.
 *
 * Usage Access is not a runtime permission: it is an app-op the user grants by hand in
 * Android Settings, so the app can only read the state, never request it. The check is
 * a read-only app-op query — no exception is thrown, and a missing service or a
 * SecurityException reads as "not granted" rather than crashing a caller.
 *
 * Kept as its own seam so the usage source does not depend on the Phase 7/8
 * `ForegroundAppMonitor` (whose job is *foreground enforcement*, not historical
 * aggregate usage). The two must not be entangled.
 */
object AndroidUsageAccess {

    fun isGranted(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@runCatching false
        modeGrantsUsageAccess(readMode(appOps, context.packageName))
    }.getOrDefault(false)

    /**
     * The app-op read. API 29+ uses the non-deprecated `unsafeCheckOpNoThrow(String, …)`;
     * older platforms use the int-op overload. Both read the same live system state —
     * this only avoids the deprecated API on modern devices.
     */
    @Suppress("DEPRECATION")
    private fun readMode(appOps: AppOpsManager, packageName: String): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }

    /**
     * Only `MODE_ALLOWED` grants the capability. `MODE_DEFAULT`/`MODE_IGNORED`/`MODE_ERRORED`
     * do not — a device that has not actually granted Usage Access must never read as
     * granted. Spelled locally (the platform value is a stable `3` since API 19) so the
     * mapping is deterministic and unit-tested without Android.
     */
    private const val MODE_ALLOWED_VALUE = 3

    fun modeGrantsUsageAccess(mode: Int): Boolean = mode == MODE_ALLOWED_VALUE
}
