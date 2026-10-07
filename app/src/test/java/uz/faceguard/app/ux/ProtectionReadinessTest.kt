package uz.faceguard.app.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.ProtectionReadiness
import uz.faceguard.app.domain.protection.protectionReadiness

/**
 * Stage 8: the honest readiness verdict.
 *
 * The single most important Stage 8 invariant: "Protection: ON" (a user setting) must
 * never be presented as "fully protecting". These tests pin the four states, and in
 * particular that a missing **accessibility** guardrail yields LIMITED, not READY.
 */
class ProtectionReadinessTest {

    private fun readiness(
        enabled: Boolean = true,
        active: Boolean = true,
        parentFace: Boolean = true,
        protectedApps: Int = 1,
        missing: Set<ProtectionCapability> = emptySet(),
    ) = protectionReadiness(
        enabled = enabled,
        active = active,
        parentFaceEnrolled = parentFace,
        protectedAppCount = protectedApps,
        missingCapabilities = missing,
    )

    // ---------------------------------------------------------- basic states

    @Test
    fun disabledIsOff_notAFault() {
        assertEquals(ProtectionReadiness.OFF, readiness(enabled = false))
    }

    @Test
    fun activeWithEverythingGrantedIsReady() {
        assertEquals(ProtectionReadiness.READY, readiness())
    }

    @Test
    fun notSignedInOrInactiveIsNotReady() {
        assertEquals(ProtectionReadiness.NOT_READY, readiness(active = false))
    }

    @Test
    fun noParentFaceIsNotReady() {
        assertEquals(ProtectionReadiness.NOT_READY, readiness(parentFace = false))
    }

    @Test
    fun noProtectedAppIsNotReady() {
        assertEquals(ProtectionReadiness.NOT_READY, readiness(protectedApps = 0))
    }

    // -------------------------------------------------- false-success prevention

    @Test
    fun aMissingAccessibilityGuardrailIsLimited_neverReady() {
        // Accessibility is the only mechanism that consumes touches; without it
        // protection is partial, so this must never read as READY.
        assertEquals(
            ProtectionReadiness.LIMITED,
            readiness(missing = setOf(ProtectionCapability.ACCESSIBILITY)),
        )
    }

    @Test
    fun everyMissingCapabilityYieldsLimited() {
        ProtectionCapability.entries.forEach { capability ->
            assertEquals(
                "$capability must yield LIMITED",
                ProtectionReadiness.LIMITED,
                readiness(missing = setOf(capability)),
            )
        }
    }

    @Test
    fun severalMissingCapabilitiesStillYieldLimited() {
        assertEquals(
            ProtectionReadiness.LIMITED,
            readiness(missing = setOf(ProtectionCapability.CAMERA, ProtectionCapability.OVERLAY)),
        )
    }

    @Test
    fun readinessIsOrderIndependentOfTheMissingSet() {
        val a = readiness(missing = setOf(ProtectionCapability.OVERLAY, ProtectionCapability.CAMERA))
        val b = readiness(missing = setOf(ProtectionCapability.CAMERA, ProtectionCapability.OVERLAY))
        assertEquals(a, b)
    }

    // ------------------------------------------------ wiring on the runtime state

    @Test
    fun runtimeStateReadinessMatchesItsDegradedCapabilities() {
        val ready = ProtectionRuntimeState(
            enabled = true, active = true, parentFaceEnrolled = true,
            protectedCount = 1, overlayGranted = true, usageAccessGranted = true,
            accessibilityEnabled = true, cameraGranted = true,
        )
        assertEquals(ProtectionReadiness.READY, ready.readiness)

        // Drop only accessibility: the regression that previously showed "active".
        assertEquals(ProtectionReadiness.LIMITED, ready.copy(accessibilityEnabled = false).readiness)
    }

    @Test
    fun aDisabledSessionIsOffAndNeverReportedAsDegraded() {
        val off = ProtectionRuntimeState(enabled = false, active = false, overlayGranted = false)
        assertEquals(ProtectionReadiness.OFF, off.readiness)
        assertTrue(off.degradedCapabilities.isEmpty())
    }

    @Test
    fun cameraRecoveryIsReportingOnlyAndDoesNotChangeReadiness() {
        val ready = ProtectionRuntimeState(
            enabled = true, active = true, parentFaceEnrolled = true, protectedCount = 1,
            overlayGranted = true, usageAccessGranted = true, accessibilityEnabled = true,
            cameraGranted = true,
        )
        val recovering = ready.copy(cameraRecovering = true)
        assertEquals(ready.readiness, recovering.readiness)
        assertEquals(ProtectionReadiness.READY, recovering.readiness)
    }

    @Test
    fun readinessCoversEveryStateForEveryCapabilityCombination() {
        // Exhaustive over the four capabilities: readiness is READY only when none are
        // missing, LIMITED when any is missing (given an active, set-up session).
        val caps = ProtectionCapability.entries
        (0 until (1 shl caps.size)).forEach { mask ->
            val missing = caps.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
            val expected = if (missing.isEmpty()) ProtectionReadiness.READY else ProtectionReadiness.LIMITED
            assertEquals("mask=$mask", expected, readiness(missing = missing))
        }
    }

    @Test
    fun readinessHasExactlyFourStates() {
        assertEquals(4, ProtectionReadiness.entries.size)
        assertFalse(ProtectionReadiness.entries.isEmpty())
    }
}
