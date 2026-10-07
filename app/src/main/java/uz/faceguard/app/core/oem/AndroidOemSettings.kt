package uz.faceguard.app.core.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.domain.oem.ComponentSpec
import uz.faceguard.app.domain.oem.OemCompatibilityProfile
import uz.faceguard.app.domain.oem.OemCompatibilityProfiles
import uz.faceguard.app.domain.oem.OemFamily
import uz.faceguard.app.domain.oem.OemSettingsTarget

/**
 * Stage 6: the centralised OEM-compatibility settings layer.
 *
 * This is the only place in the app that knows about OEM settings pages. It turns a
 * detected [OemFamily] and an [OemSettingsTarget] into an **ordered candidate list**
 * of intents:
 *
 * ```
 * OEM-specific candidate(s)  ->  generic platform page  ->  app details page
 * ```
 *
 * Every candidate is resolved by the caller's launcher (`AndroidSettingsStarter`
 * via `openFirstAvailable`), so an unresolved/stale OEM component is skipped and the
 * user is never dead-ended or crashed. The layer decides only *where to send the
 * user*; it never changes recognition, policy or enforcement.
 */
@Singleton
class AndroidOemSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: AndroidOemDetector,
) {

    val family: OemFamily get() = detector.family

    fun profile(): OemCompatibilityProfile = OemCompatibilityProfiles.forFamily(family)

    /** True when the OS will not battery-optimize QALQON; null when not applicable. */
    fun isIgnoringBatteryOptimizations(): Boolean? =
        AndroidBatteryOptimization.isIgnoringOptimizations(context)

    /**
     * The ordered intents to try for an OEM-specific page, ending with a generic
     * fallback that always exists (the app's own details page). Never empty.
     */
    fun settingsIntentCandidates(target: OemSettingsTarget): List<Intent> {
        val oemIntents = profile().candidatesFor(target).mapNotNull { it.toIntentOrNull() }
        val generic = when (target) {
            OemSettingsTarget.BATTERY_OPTIMIZATION ->
                listOf(AndroidBatteryOptimization.settingsIntent())
            OemSettingsTarget.AUTOSTART,
            OemSettingsTarget.BACKGROUND_RESTRICTION,
            -> emptyList()
        }
        return oemIntents + generic + appDetailsIntent()
    }

    /** Battery optimization guidance: the platform list page, then app details. */
    fun batteryOptimizationIntentCandidates(): List<Intent> =
        listOf(AndroidBatteryOptimization.settingsIntent()) + appDetailsIntent()

    /**
     * Resolves a candidate component to an intent **only if the activity exists**.
     * A component the platform does not know is dropped rather than risked at launch
     * time. (The launch itself is still wrapped by the caller's safe starter.)
     */
    private fun ComponentSpec.toIntentOrNull(): Intent? {
        val component = ComponentName(packageName, className)
        val exists = runCatching {
            context.packageManager.getActivityInfo(component, 0)
            true
        }.getOrDefault(false)
        if (!exists) return null
        return Intent().setComponent(component)
    }

    private fun appDetailsIntent(): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )
}
