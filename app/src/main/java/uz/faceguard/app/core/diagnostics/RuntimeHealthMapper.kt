package uz.faceguard.app.core.diagnostics

import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.domain.diagnostics.ScheduleSyncMechanism
import uz.faceguard.app.domain.diagnostics.SystemHealthSnapshot

/**
 * Phase 12: static facts about how this build is wired, verified against the
 * source rather than guessed at runtime.
 *
 * They live here (not in the evaluator) so the auditor can be told a platform
 * fact it could never read for itself without adding Android APIs to the pure
 * domain.
 */
object PlatformCapabilities {
    /**
     * The release manifest declares `.core.protection.ProtectionBootReceiver` for
     * `android.intent.action.BOOT_COMPLETED` (see `AndroidManifest.xml`). The
     * `Phase12IntegrationTest` re-reads the manifest so a removal fails the JVM
     * suite instead of silently degrading the audit.
     */
    val BOOT_RESTORE_WIRED: Boolean = true

    /**
     * Background schedule/scan state is kept in sync **event-driven** by
     * [uz.faceguard.app.core.scan.ScanScheduler] on the existing engine tick.
     * WorkManager is deliberately not a dependency: the app is offline-first and
     * must not add periodic OS wake-ups.
     */
    val SCHEDULE_SYNC_MECHANISM: ScheduleSyncMechanism = ScheduleSyncMechanism.EVENT_DRIVEN
}

/**
 * Pure adapter from concrete runtime state to the auditor's input.
 *
 * Kept separate from the Android reader so the mapping is JVM-tested: only the
 * reading itself needs a device.
 */
object RuntimeHealthMapper {

    @Suppress("LongParameterList")
    fun snapshot(
        signedIn: Boolean,
        protectionEnabled: Boolean,
        foregroundServiceRunning: Boolean,
        overlayGranted: Boolean,
        accessibilityEnabled: Boolean,
        usageAccessGranted: Boolean,
        notificationsEnabled: Boolean,
        bootRestoreWired: Boolean = PlatformCapabilities.BOOT_RESTORE_WIRED,
        scheduleSyncMechanism: ScheduleSyncMechanism = PlatformCapabilities.SCHEDULE_SYNC_MECHANISM,
    ): SystemHealthSnapshot = SystemHealthSnapshot(
        signedIn = signedIn,
        protectionEnabled = protectionEnabled,
        foregroundServiceRunning = foregroundServiceRunning,
        overlayGranted = overlayGranted,
        accessibilityEnabled = accessibilityEnabled,
        usageAccessGranted = usageAccessGranted,
        notificationsEnabled = notificationsEnabled,
        bootRestoreWired = bootRestoreWired,
        scheduleSyncMechanism = scheduleSyncMechanism,
    )

    /**
     * The runtime-facing mapping: every runtime signal comes from the one
     * [ProtectionRuntimeState] the app already publishes, so the audit can never
     * disagree with what the parent-facing protection screen shows.
     */
    fun snapshot(
        runtime: ProtectionRuntimeState,
        signedIn: Boolean,
        foregroundServiceRunning: Boolean,
        bootRestoreWired: Boolean = PlatformCapabilities.BOOT_RESTORE_WIRED,
        scheduleSyncMechanism: ScheduleSyncMechanism = PlatformCapabilities.SCHEDULE_SYNC_MECHANISM,
    ): SystemHealthSnapshot = snapshot(
        signedIn = signedIn,
        protectionEnabled = runtime.enabled,
        foregroundServiceRunning = foregroundServiceRunning,
        overlayGranted = runtime.overlayGranted,
        accessibilityEnabled = runtime.accessibilityEnabled,
        usageAccessGranted = runtime.usageAccessGranted,
        notificationsEnabled = runtime.notificationsEnabled,
        bootRestoreWired = bootRestoreWired,
        scheduleSyncMechanism = scheduleSyncMechanism,
    )
}
