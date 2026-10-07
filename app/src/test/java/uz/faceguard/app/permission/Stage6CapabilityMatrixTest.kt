package uz.faceguard.app.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.permission.ProtectionCapabilities
import uz.faceguard.app.core.permission.ProtectionCapabilitySource
import uz.faceguard.app.core.protection.CameraSessionPolicy
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.domain.oem.OemDetector
import uz.faceguard.app.domain.oem.OemFamily
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.degradationKey
import uz.faceguard.app.domain.protection.highestPriorityMissing
import uz.faceguard.app.domain.protection.missingProtectionCapabilities

/**
 * Stage 6: the critical-capability revoke/regrant contract.
 *
 * For camera, Usage Access, accessibility and overlay the required flow is
 *
 * ```
 * GRANTED -> REVOKED -> DETECTED -> DEGRADED -> (guidance) -> REGRANTED -> READY
 * ```
 *
 * These are the pure, deterministic rules the runtime and banners are built on, so
 * the flow is asserted without a device. Battery/notification/OEM are deliberately
 * *not* part of the required capability set and are asserted as such.
 */
class Stage6CapabilityMatrixTest {

    private fun ready() = ProtectionRuntimeState(
        enabled = true,
        active = true,
        overlayGranted = true,
        usageAccessGranted = true,
        accessibilityEnabled = true,
        cameraGranted = true,
        notificationsEnabled = true,
        batteryOptimizationIgnored = false, // optimized: reported but not a capability
        oemFamily = OemFamily.UNKNOWN,
        protectedCount = 1,
        parentFaceEnrolled = true,
    )

    // ------------------------------------------------------- revoke detection

    @Test
    fun revokingCamera_isDetectedAsDegraded_andTheSessionWouldStop() {
        val revoked = ready().copy(cameraGranted = false)
        assertEquals(setOf(ProtectionCapability.CAMERA), revoked.degradedCapabilities)
        assertTrue(revoked.degraded)
        // Stage 5 regression: losing the permission must stop the latched session.
        assertFalse(
            CameraSessionPolicy.shouldRun(
                protectionActive = true, uiForeground = false,
                cameraGranted = false, sessionRunning = true,
            ),
        )
    }

    @Test
    fun revokingUsageAccess_isDetectedAsDegraded() {
        assertEquals(setOf(ProtectionCapability.USAGE_ACCESS), ready().copy(usageAccessGranted = false).degradedCapabilities)
    }

    @Test
    fun revokingOverlay_isDetectedAsDegraded() {
        assertEquals(setOf(ProtectionCapability.OVERLAY), ready().copy(overlayGranted = false).degradedCapabilities)
    }

    @Test
    fun disablingAccessibility_isDetectedAsDegraded() {
        assertEquals(
            setOf(ProtectionCapability.ACCESSIBILITY),
            ready().copy(accessibilityEnabled = false).degradedCapabilities,
        )
    }

    @Test
    fun revokingSeveralCapabilities_reportsExactlyThemissingSet() {
        val state = ready().copy(cameraGranted = false, overlayGranted = false)
        assertEquals(
            setOf(ProtectionCapability.CAMERA, ProtectionCapability.OVERLAY),
            state.degradedCapabilities,
        )
    }

    // ------------------------------------------------------- regrant recovery

    @Test
    fun regrantingEveryRevokedCapability_returnsToFullyReady() {
        var state = ready().copy(
            cameraGranted = false,
            usageAccessGranted = false,
            overlayGranted = false,
            accessibilityEnabled = false,
        )
        assertTrue(state.degraded)
        assertEquals(4, state.degradedCapabilities.size)

        state = state.copy(
            cameraGranted = true,
            usageAccessGranted = true,
            overlayGranted = true,
            accessibilityEnabled = true,
        )
        assertFalse(state.degraded)
        assertTrue(state.degradedCapabilities.isEmpty())
        assertTrue(state.ready)
    }

    @Test
    fun regrantingCamera_restartsALegalCameraSession() {
        assertTrue(
            CameraSessionPolicy.shouldRun(
                protectionActive = true, uiForeground = true,
                cameraGranted = true, sessionRunning = false,
            ),
        )
    }

    // --------------------------------------------- degraded ordering / keys

    @Test
    fun theMostConsequentialMissingCapabilityIsOfferedFirst() {
        val missing = setOf(
            ProtectionCapability.CAMERA,
            ProtectionCapability.USAGE_ACCESS,
            ProtectionCapability.OVERLAY,
            ProtectionCapability.ACCESSIBILITY,
        )
        assertEquals(ProtectionCapability.OVERLAY, missing.highestPriorityMissing())
    }

    @Test
    fun theDegradationKeyIsOrderIndependent() {
        val a = setOf(ProtectionCapability.OVERLAY, ProtectionCapability.CAMERA)
        val b = setOf(ProtectionCapability.CAMERA, ProtectionCapability.OVERLAY)
        assertEquals(a.degradationKey(), b.degradationKey())
    }

    // ---------------------------------------- recommended vs required signals

    @Test
    fun notificationAndBatteryAreNotRequiredCapabilities() {
        // Losing notifications or having Doze on must not appear as a missing
        // *protection* capability — they are recommended reliability signals only.
        val state = ready().copy(notificationsEnabled = false, batteryOptimizationIgnored = false)
        assertTrue(state.degradedCapabilities.isEmpty())
        assertFalse(state.degraded)
    }

    @Test
    fun aDisabledProtectionDeviceIsNeverReportedAsDegraded() {
        // A switched-off session must not nag about capabilities.
        val off = ready().copy(enabled = false, active = false, overlayGranted = false)
        assertTrue(off.degradedCapabilities.isEmpty())
        assertEquals(emptySet<ProtectionCapability>(), off.degradedCapabilities)
    }

    @Test
    fun aFakeCapabilitySourceModelsTheFullRevokeRegrantCycle() {
        var caps = ProtectionCapabilities(true, true, true)
        val source = ProtectionCapabilitySource { caps }
        assertTrue(source.read().allGranted)

        caps = caps.copy(usageAccessGranted = false) // revoked
        assertFalse(source.read().allGranted)

        caps = caps.copy(usageAccessGranted = true) // regranted
        assertTrue(source.read().allGranted)
    }

    @Test
    fun missingCapabilitySetMatchesTheRequiredFlags() {
        assertEquals(
            setOf(ProtectionCapability.CAMERA),
            missingProtectionCapabilities(
                accessibilityEnabled = true, overlayGranted = true,
                usageAccessGranted = true, cameraGranted = false,
            ),
        )
    }

    // --------------------------------------------------- OEM reporting only

    @Test
    fun oemFamilyAndBatteryAreReportingOnly_onTheRuntimeState() {
        val state = ready().copy(
            oemFamily = OemDetector.detect("Xiaomi", "Redmi", "Note 14"),
            oemGuidanceAvailable = true,
            batteryOptimizationIgnored = false,
        )
        assertEquals(OemFamily.REDMI, state.oemFamily)
        assertTrue(state.oemGuidanceAvailable)
        assertEquals(false, state.batteryOptimizationIgnored)
        assertTrue("OEM/battery never change the required capability set", state.degradedCapabilities.isEmpty())
        assertTrue(state.ready)
    }

    @Test
    fun batteryUnavailable_isRepresentableAsNull() {
        val state = ready().copy(batteryOptimizationIgnored = null)
        assertNull(state.batteryOptimizationIgnored)
        assertTrue(state.degradedCapabilities.isEmpty())
    }
}
