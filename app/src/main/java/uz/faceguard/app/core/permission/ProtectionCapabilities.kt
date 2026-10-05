package uz.faceguard.app.core.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import uz.faceguard.app.core.accessibility.AccessibilityCapability
import uz.faceguard.app.core.usage.AndroidUsageAccess

/**
 * The three *special* access capabilities Qalqon's protection depends on: Usage Access
 * (foreground detection), draw-over-other-apps (the blocking window) and the
 * accessibility service (window transitions + the touch-blocking overlay). Camera is a
 * runtime permission and is read separately.
 *
 * Kept as a plain data class so the runtime can be driven from a deterministic fake in
 * JVM tests, independent of Android system state.
 */
data class ProtectionCapabilities(
    val usageAccessGranted: Boolean,
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
) {
    val allGranted: Boolean
        get() = usageAccessGranted && overlayGranted && accessibilityEnabled
}

/**
 * Read-only seam over the special-access probes.
 *
 * Isolating the three Android reads behind one interface means the runtime's
 * capability handling is unit-testable with a fake, and a future OEM-specific probe can
 * be added inside [AndroidProtectionCapabilitySource] without touching the runtime or
 * any screen. Each probe is failure-safe (a missing service or a thrown exception reads
 * as "not granted", never a crash).
 */
fun interface ProtectionCapabilitySource {
    fun read(): ProtectionCapabilities
}

/** Production source: reads the real system state through the existing capability seams. */
class AndroidProtectionCapabilitySource(
    private val context: Context,
) : ProtectionCapabilitySource {

    override fun read(): ProtectionCapabilities = ProtectionCapabilities(
        usageAccessGranted = AndroidUsageAccess.isGranted(context),
        overlayGranted = canDrawOverlays(context),
        accessibilityEnabled = AccessibilityCapability.isEnabled(context),
    )

    companion object {
        /**
         * Draw-over-other-apps state. Pre-M there is no such permission, so it is always
         * available; M+ uses the platform check. `Settings.canDrawOverlays` is the
         * documented, OEM-independent source of truth.
         */
        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }
}

/**
 * The system settings pages Qalqon deep-links to. Centralised so every entry point
 * targets the same, correct page and so a missing handler has a defined fallback
 * (the app's own details page, from which every permission can still be reached).
 */
object SettingsIntents {

    fun usageAccess(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * Draw-over-other-apps settings. A package-scoped Uri is used on API 23+ so some
     * OEMs open the app's own overlay page; the fallback covers devices that ignore it.
     */
    fun overlay(packageName: String): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(packageName))

    fun accessibility(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun notification(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    fun appDetails(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(packageName))

    private fun packageUri(packageName: String): Uri = Uri.fromParts("package", packageName, null)
}

/** Starts a settings [Intent]; returns false when the device has no activity for it. */
fun interface SettingsStarter {
    fun start(intent: Intent): Boolean
}

/**
 * Pure orchestration: try the primary page, and fall back only when the device has no
 * handler for it. Generic over the page type so it is deterministic and JVM-testable
 * without Android.
 */
fun <T> openWithFallback(primary: T, fallback: T, start: (T) -> Boolean): Boolean =
    start(primary) || start(fallback)

/** Android [SettingsStarter]: resolves a handler first and never throws. */
class AndroidSettingsStarter(private val context: Context) : SettingsStarter {
    override fun start(intent: Intent): Boolean {
        val safe = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            if (safe.resolveActivity(context.packageManager) == null) return false
            context.startActivity(safe)
            true
        }.getOrDefault(false)
    }
}

/**
 * Opens [primary] settings with a generic fallback (the app's own details page), so a
 * deep-link the device does not handle can never crash the app or dead-end the user.
 */
fun Context.openSettingsOrFallback(primary: Intent, pkg: String = packageName): Boolean =
    openWithFallback(
        primary = primary,
        fallback = SettingsIntents.appDetails(pkg),
        start = AndroidSettingsStarter(this)::start,
    )
