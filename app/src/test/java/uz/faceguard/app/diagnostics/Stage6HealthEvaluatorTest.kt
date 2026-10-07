package uz.faceguard.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.diagnostics.DiagnosticCheck
import uz.faceguard.app.domain.diagnostics.DiagnosticStatus
import uz.faceguard.app.domain.diagnostics.ScheduleSyncMechanism
import uz.faceguard.app.domain.diagnostics.SystemHealthEvaluator
import uz.faceguard.app.domain.diagnostics.SystemHealthLevel
import uz.faceguard.app.domain.diagnostics.SystemHealthSnapshot
import uz.faceguard.app.domain.oem.OemFamily

/**
 * Stage 6: the two new audit checks (battery optimization and OEM background).
 *
 * The contract that matters: battery optimization and OEM background restriction
 * are **recommended** signals. They can warn, they can be unavailable, but they
 * must never be a FAILED blocker — a device with Doze enabled is still protecting.
 */
class Stage6HealthEvaluatorTest {

    private val evaluator = SystemHealthEvaluator()

    private fun snapshot(
        protectionEnabled: Boolean = true,
        batteryOptimizationIgnored: Boolean? = true,
        oemFamily: OemFamily = OemFamily.UNKNOWN,
        oemGuidanceAvailable: Boolean = false,
    ) = SystemHealthSnapshot(
        signedIn = true,
        protectionEnabled = protectionEnabled,
        foregroundServiceRunning = true,
        overlayGranted = true,
        accessibilityEnabled = true,
        usageAccessGranted = true,
        notificationsEnabled = true,
        bootRestoreWired = true,
        scheduleSyncMechanism = ScheduleSyncMechanism.EVENT_DRIVEN,
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        oemFamily = oemFamily,
        oemGuidanceAvailable = oemGuidanceAvailable,
    )

    // ------------------------------------------------- battery optimization

    @Test
    fun batteryOptimizationIgnored_isOK() {
        val report = evaluator.evaluate(snapshot(batteryOptimizationIgnored = true))
        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.BATTERY_OPTIMIZATION))
    }

    @Test
    fun batteryOptimizationActive_isAWarning_neverABlocker() {
        val report = evaluator.evaluate(snapshot(batteryOptimizationIgnored = false))
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.BATTERY_OPTIMIZATION))
        assertTrue("battery optimization must never block", report.blocker.isEmpty())
        assertEquals(SystemHealthLevel.DEGRADED, report.level)
    }

    @Test
    fun batteryOptimizationOnAnUnsupportedPlatform_isUnavailable_notAFalseJudgement() {
        val report = evaluator.evaluate(snapshot(batteryOptimizationIgnored = null))
        assertEquals(DiagnosticStatus.UNAVAILABLE, report.statusOf(DiagnosticCheck.BATTERY_OPTIMIZATION))
        // UNAVAILABLE must not, on its own, degrade an otherwise healthy device.
        assertEquals(SystemHealthLevel.HEALTHY, report.level)
    }

    @Test
    fun batteryOptimizationWhileProtectionIsOff_isUnknown() {
        val report = evaluator.evaluate(snapshot(protectionEnabled = false, batteryOptimizationIgnored = false))
        assertEquals(DiagnosticStatus.UNKNOWN, report.statusOf(DiagnosticCheck.BATTERY_OPTIMIZATION))
    }

    // ------------------------------------------------------ OEM background

    @Test
    fun aFamilyWithoutKnownRestrictions_isOK() {
        listOf(OemFamily.GOOGLE, OemFamily.MOTOROLA, OemFamily.SAMSUNG, OemFamily.UNKNOWN)
            .forEach { family ->
                val report = evaluator.evaluate(snapshot(oemFamily = family))
                assertEquals(
                    "$family should audit OK",
                    DiagnosticStatus.OK,
                    report.statusOf(DiagnosticCheck.OEM_BACKGROUND),
                )
            }
    }

    @Test
    fun aRestrictiveFamilyWithGuidance_isAWarning_neverABlocker() {
        val report = evaluator.evaluate(
            snapshot(oemFamily = OemFamily.REDMI, oemGuidanceAvailable = true),
        )
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.OEM_BACKGROUND))
        assertTrue("OEM background restriction must never block", report.blocker.isEmpty())
    }

    @Test
    fun aRestrictiveFamilyWithoutAGuidancePage_stillWarnsWithTheGenericMessage() {
        val report = evaluator.evaluate(
            snapshot(oemFamily = OemFamily.XIAOMI, oemGuidanceAvailable = false),
        )
        val finding = report.findings.first { it.check == DiagnosticCheck.OEM_BACKGROUND }
        assertEquals(DiagnosticStatus.WARNING, finding.status)
        assertEquals("oem_background_restriction_generic", finding.detail)
    }

    @Test
    fun oemBackgroundWhileProtectionIsOff_isUnknown() {
        val report = evaluator.evaluate(snapshot(protectionEnabled = false, oemFamily = OemFamily.OPPO))
        assertEquals(DiagnosticStatus.UNKNOWN, report.statusOf(DiagnosticCheck.OEM_BACKGROUND))
    }

    // ---------------------------------------------------------- integration

    @Test
    fun aFullyHealthyDeviceWithRecommendedSettings_isHealthyAndAllOk() {
        val report = evaluator.evaluate(
            snapshot(batteryOptimizationIgnored = true, oemFamily = OemFamily.GOOGLE),
        )
        assertEquals(SystemHealthLevel.HEALTHY, report.level)
        DiagnosticCheck.entries.forEach { check ->
            assertEquals("$check", DiagnosticStatus.OK, report.statusOf(check))
        }
    }

    @Test
    fun aRestrictiveOemWithBatteryOptimized_degradesButStillProtects() {
        val report = evaluator.evaluate(
            snapshot(batteryOptimizationIgnored = false, oemFamily = OemFamily.POCO),
        )
        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertTrue("nothing is blocked — the device still protects", report.blocker.isEmpty())
    }

    @Test
    fun theNewChecksAreNeverTheSoleReasonForACriticalLevel() {
        // Only a real prerequisite failure may produce CRITICAL.
        val report = evaluator.evaluate(
            snapshot(
                batteryOptimizationIgnored = false,
                oemFamily = OemFamily.HONOR,
                oemGuidanceAvailable = true,
            ),
        )
        assertTrue(report.level != SystemHealthLevel.CRITICAL)
    }
}
