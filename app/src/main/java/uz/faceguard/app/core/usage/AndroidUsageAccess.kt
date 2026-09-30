package uz.faceguard.app.core.usage

import android.app.AppOpsManager
import android.content.Context
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
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
