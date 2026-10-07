package uz.faceguard.app.core.oem

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Stage 6: the battery-optimization capability.
 *
 * Android's Doze/app-standby can suspend background work, which matters for an
 * always-on parental-control service. This is a **recommended** capability, never
 * a required one: QALQON reads the state and offers the platform settings page, but
 * never forces the user to change it and never treats "optimized" as a failure.
 *
 * The direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog requires the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission and a Play-policy
 * justification, so QALQON deliberately opens the *list* page
 * (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) instead — no new permission.
 */
object AndroidBatteryOptimization {

    /**
     * True when the OS will not battery-optimize QALQON; `null` when the concept
     * does not exist on this platform (below API 23), which the caller reports as
     * UNAVAILABLE rather than a false "optimized" or "not optimized".
     *
     * A missing service or a thrown exception reads as `null` (unknown), never a
     * crash and never a fabricated answer.
     */
    fun isIgnoringOptimizations(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@runCatching null
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
    }

    /** The platform battery-optimization list page. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** The universal fallback: the app's own details page, from which battery is reachable. */
    fun fallbackIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
}
