package uz.faceguard.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.diagnostics.DiagnosticCheck
import uz.faceguard.app.domain.diagnostics.DiagnosticStatus
import uz.faceguard.app.domain.diagnostics.ScheduleSyncMechanism
import uz.faceguard.app.domain.diagnostics.SystemHealthEvaluator
import uz.faceguard.app.domain.diagnostics.SystemHealthLevel
import uz.faceguard.app.domain.diagnostics.SystemHealthSnapshot

/**
 * Phase 12: the system-health auditor rules.
 *
 * A healthy device is the baseline; every test then breaks exactly one signal so
 * the resulting status and the overall level are pinned. The important contract
 * is that a check can only FAIL while protection is actually requested: a
 * signed-out or protection-off device reports UNKNOWN, never a false failure.
 */
class SystemHealthEvaluatorTest {

    private val evaluator = SystemHealthEvaluator()

    /** A device where every prerequisite and mechanism is in place. */
    private fun healthy(
        signedIn: Boolean = true,
        protectionEnabled: Boolean = true,
        foregroundServiceRunning: Boolean = true,
        overlayGranted: Boolean = true,
        accessibilityEnabled: Boolean = true,
        usageAccessGranted: Boolean = true,
        notificationsEnabled: Boolean = true,
        bootRestoreWired: Boolean = true,
        scheduleSyncMechanism: ScheduleSyncMechanism = ScheduleSyncMechanism.EVENT_DRIVEN,
    ) = SystemHealthSnapshot(
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

    // --------------------------------------------------------------- healthy

    @Test
    fun fullyHealthyDevice_isHealthyAndReportsEveryCheckOk() {
        val report = evaluator.evaluate(healthy())

        assertEquals(SystemHealthLevel.HEALTHY, report.level)
        assertTrue(report.isReady())
        assertTrue(report.blocker.isEmpty())
        assertTrue(report.warnings.isEmpty())
        assertEquals(DiagnosticCheck.entries.size, report.findings.size)
        DiagnosticCheck.entries.forEach { check ->
            assertEquals("$check", DiagnosticStatus.OK, report.statusOf(check))
        }
    }

    @Test
    fun findingsCoverEveryCheckExactlyOnceInStableOrder() {
        val report = evaluator.evaluate(healthy())
        assertEquals(DiagnosticCheck.entries, report.findings.map { it.check })
    }

    // ------------------------------------------------------------- signed out

    @Test
    fun signedOut_isUnknownAndNeverAFailure() {
        val report = evaluator.evaluate(healthy(signedIn = false))

        assertEquals(SystemHealthLevel.UNKNOWN, report.level)
        assertTrue(report.blocker.isEmpty())
        report.findings.forEach { finding ->
            assertEquals(finding.check.name, DiagnosticStatus.UNKNOWN, finding.status)
            assertEquals("not_signed_in", finding.detail)
        }
    }

    // --------------------------------------------------------- protection off

    @Test
    fun protectionOff_degradesButDoesNotFail() {
        val report = evaluator.evaluate(healthy(protectionEnabled = false))

        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertTrue(report.blocker.isEmpty())
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.PROTECTION_INTENT))
        // Runtime prerequisites are simply not applicable while protection is off.
        assertEquals(DiagnosticStatus.UNKNOWN, report.statusOf(DiagnosticCheck.FOREGROUND_SERVICE))
        assertEquals(DiagnosticStatus.UNKNOWN, report.statusOf(DiagnosticCheck.OVERLAY_PERMISSION))
        assertEquals(DiagnosticStatus.UNKNOWN, report.statusOf(DiagnosticCheck.USAGE_ACCESS))
        // Mechanisms are still audited and still pass.
        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.BOOT_RESTORE))
        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.SCHEDULE_SYNC))
    }

    // --------------------------------------------------- background binding

    @Test
    fun protectionRequestedButServiceNotBound_isCritical() {
        val report = evaluator.evaluate(healthy(foregroundServiceRunning = false))

        assertEquals(SystemHealthLevel.CRITICAL, report.level)
        assertEquals(DiagnosticStatus.FAILED, report.statusOf(DiagnosticCheck.FOREGROUND_SERVICE))
        assertEquals(listOf(DiagnosticCheck.FOREGROUND_SERVICE), report.blocker.map { it.check })
    }

    // -------------------------------------------------------- blocking window

    @Test
    fun neitherOverlayNorAccessibility_failsTheBlockingWindowCheck() {
        val report = evaluator.evaluate(
            healthy(overlayGranted = false, accessibilityEnabled = false),
        )

        assertEquals(SystemHealthLevel.CRITICAL, report.level)
        assertEquals(DiagnosticStatus.FAILED, report.statusOf(DiagnosticCheck.OVERLAY_PERMISSION))
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.ACCESSIBILITY_GUARDRAIL))
    }

    @Test
    fun overlayFallbackWithoutAccessibility_degradesButBlocks() {
        val report = evaluator.evaluate(healthy(accessibilityEnabled = false))

        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.OVERLAY_PERMISSION))
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.ACCESSIBILITY_GUARDRAIL))
    }

    // ------------------------------------------------------------- usage access

    @Test
    fun usageAccessMissingWithoutAccessibility_fails() {
        val report = evaluator.evaluate(
            healthy(usageAccessGranted = false, accessibilityEnabled = false),
        )

        assertEquals(SystemHealthLevel.CRITICAL, report.level)
        assertEquals(DiagnosticStatus.FAILED, report.statusOf(DiagnosticCheck.USAGE_ACCESS))
    }

    @Test
    fun usageAccessMissingWithAccessibility_degrades() {
        val report = evaluator.evaluate(healthy(usageAccessGranted = false))

        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.USAGE_ACCESS))
    }

    // ------------------------------------------------------------ notifications

    @Test
    fun notificationsDisabled_warnsWithoutBlocking() {
        val report = evaluator.evaluate(healthy(notificationsEnabled = false))

        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertTrue(report.blocker.isEmpty())
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.NOTIFICATIONS))
    }

    // ------------------------------------------------------------- boot restore

    @Test
    fun missingBootReceiver_failsTheRestoreCheck() {
        val report = evaluator.evaluate(healthy(bootRestoreWired = false))

        assertEquals(SystemHealthLevel.CRITICAL, report.level)
        assertEquals(DiagnosticStatus.FAILED, report.statusOf(DiagnosticCheck.BOOT_RESTORE))
    }

    // ------------------------------------------------------------- schedule sync

    @Test
    fun noScheduleSyncMechanism_degrades() {
        val report = evaluator.evaluate(healthy(scheduleSyncMechanism = ScheduleSyncMechanism.NONE))

        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertTrue(report.blocker.isEmpty())
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.SCHEDULE_SYNC))
    }

    @Test
    fun workManagerScheduleSync_isAcceptedAsAWorkingMechanism() {
        val report = evaluator.evaluate(
            healthy(scheduleSyncMechanism = ScheduleSyncMechanism.WORK_MANAGER),
        )

        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.SCHEDULE_SYNC))
        // Everything else is already healthy, so the device is fully healthy.
        assertEquals(SystemHealthLevel.HEALTHY, report.level)
    }

    // ------------------------------------------------------------------ level

    @Test
    fun failuresOutrankWarnings() {
        val report = evaluator.evaluate(
            healthy(foregroundServiceRunning = false, notificationsEnabled = false),
        )

        assertEquals(SystemHealthLevel.CRITICAL, report.level)
        assertEquals(1, report.blocker.size)
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test
    fun degradedDeviceIsNotReady() {
        assertFalse(evaluator.evaluate(healthy(notificationsEnabled = false)).isReady())
        assertFalse(evaluator.evaluate(healthy(signedIn = false)).isReady())
    }
}
