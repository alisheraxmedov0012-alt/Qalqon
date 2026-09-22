package uz.faceguard.app.core.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Read-only view of whether QALQON's accessibility service has been explicitly
 * enabled by the user.
 *
 * Enabling is a system-owned, user-driven action (Android Settings); the app can
 * only detect the state and deep-link the user there — it never enables or
 * bypasses the service itself.
 */
object AccessibilityCapability {

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val expected = ComponentName(context, ProtectionAccessibilityService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo?.serviceInfo ?: return@any false
                service.packageName == expected.packageName && service.name == expected.className
            }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
