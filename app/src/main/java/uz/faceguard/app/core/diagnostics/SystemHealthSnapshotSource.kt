package uz.faceguard.app.core.diagnostics

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import uz.faceguard.app.core.accessibility.AccessibilityCapability
import uz.faceguard.app.core.oem.AndroidOemSettings
import uz.faceguard.app.core.protection.ProtectionForegroundService
import uz.faceguard.app.core.usage.AndroidUsageAccess
import uz.faceguard.app.domain.diagnostics.SystemHealthEvaluator
import uz.faceguard.app.domain.diagnostics.SystemHealthReport
import uz.faceguard.app.domain.diagnostics.SystemHealthSnapshot
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.SettingsRepository

/**
 * Phase 12: the single Android reader behind the system-health audit.
 *
 * It reads each signal from the same source the rest of the app uses — the
 * persisted settings intent, [ProtectionForegroundService.running] for the
 * background binding, [AccessibilityCapability] for the guardrail,
 * [AndroidUsageAccess] for the usage app-op and the notification dispatcher for
 * visibility — so the audit cannot drift from real behaviour.
 *
 * Every read is isolated: a failing DataStore/portal query degrades that one
 * signal instead of crashing the diagnostics screen or the caller. The class is
 * intentionally the only Android-coupled part; [SystemHealthEvaluator] and
 * [RuntimeHealthMapper] stay pure.
 */
@Singleton
class SystemHealthSnapshotSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val notificationDispatcher: AppNotificationDispatcher,
    /** Stage 6: the OEM/battery compatibility readings, from the single OEM layer. */
    private val oemSettings: AndroidOemSettings,
) {

    private val evaluator = SystemHealthEvaluator()

    suspend fun snapshot(): SystemHealthSnapshot {
        val signedIn = runCatching { accountRepository.currentAccountId.first() }
            .getOrNull() != null
        val protectionEnabled = runCatching {
            settingsRepository.settings.first().protectionEnabled
        }.getOrDefault(false)

        return RuntimeHealthMapper.snapshot(
            signedIn = signedIn,
            protectionEnabled = protectionEnabled,
            foregroundServiceRunning = ProtectionForegroundService.running.value,
            overlayGranted = canDrawOverlays(),
            accessibilityEnabled = AccessibilityCapability.isEnabled(context),
            usageAccessGranted = AndroidUsageAccess.isGranted(context),
            notificationsEnabled = runCatching { notificationDispatcher.areNotificationsEnabled() }
                .getOrDefault(true),
            // Stage 6: read-only OEM/battery facts. Both are failure-safe: a missing
            // service or an unknown OEM degrades the guidance, never the audit.
            batteryOptimizationIgnored = runCatching { oemSettings.isIgnoringBatteryOptimizations() }
                .getOrNull(),
            oemFamily = oemSettings.family,
            oemGuidanceAvailable =
                oemSettings.profile().supportLevel == uz.faceguard.app.domain.oem.OemSupportLevel.SUPPORTED,
        )
    }

    /** Reads the live signals and evaluates them in one step. */
    suspend fun report(): SystemHealthReport = evaluator.evaluate(snapshot())

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}
