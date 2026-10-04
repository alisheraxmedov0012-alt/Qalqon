package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.domain.protection.PROTECTION_CAPABILITY_PRIORITY
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.degradationKey
import uz.faceguard.app.domain.protection.highestPriorityMissing
import uz.faceguard.app.domain.protection.missingProtectionCapabilities

/**
 * Degraded-protection detection.
 *
 * Pure JVM: the "which capability is missing" mapping and the state-level gate that
 * stops a switched-off device being reported as degraded.
 */
class ProtectionDegradationTest {

    @Test
    fun aFullyCapableDeviceHasNothingMissing() {
        assertTrue(
            missingProtectionCapabilities(
                accessibilityEnabled = true,
                overlayGranted = true,
                usageAccessGranted = true,
                cameraGranted = true,
            ).isEmpty(),
        )
    }

    @Test
    fun eachLostCapabilityIsReportedIndividually() {
        assertEquals(
            setOf(ProtectionCapability.ACCESSIBILITY),
            missingProtectionCapabilities(false, true, true, true),
        )
        assertEquals(
            setOf(ProtectionCapability.OVERLAY),
            missingProtectionCapabilities(true, false, true, true),
        )
        assertEquals(
            setOf(ProtectionCapability.USAGE_ACCESS),
            missingProtectionCapabilities(true, true, false, true),
        )
        assertEquals(
            setOf(ProtectionCapability.CAMERA),
            missingProtectionCapabilities(true, true, true, false),
        )
    }

    @Test
    fun everySimultaneousLossIsReported() {
        assertEquals(
            ProtectionCapability.entries.toSet(),
            missingProtectionCapabilities(false, false, false, false),
        )
    }

    @Test
    fun thePriorityOrderIsFixedAndPutsBlockingFirst() {
        assertEquals(
            listOf(
                ProtectionCapability.OVERLAY,
                ProtectionCapability.ACCESSIBILITY,
                ProtectionCapability.USAGE_ACCESS,
                ProtectionCapability.CAMERA,
            ),
            PROTECTION_CAPABILITY_PRIORITY,
        )
    }

    @Test
    fun theHighestPriorityMissingChoiceIsDeterministic() {
        assertEquals(
            ProtectionCapability.OVERLAY,
            setOf(ProtectionCapability.CAMERA, ProtectionCapability.OVERLAY).highestPriorityMissing(),
        )
        assertEquals(
            ProtectionCapability.USAGE_ACCESS,
            setOf(ProtectionCapability.USAGE_ACCESS, ProtectionCapability.CAMERA).highestPriorityMissing(),
        )
        assertNull(emptySet<ProtectionCapability>().highestPriorityMissing())
    }

    @Test
    fun theDegradationKeyIsOrderIndependent() {
        val a = setOf(ProtectionCapability.CAMERA, ProtectionCapability.OVERLAY).degradationKey()
        val b = setOf(ProtectionCapability.OVERLAY, ProtectionCapability.CAMERA).degradationKey()
        assertEquals(a, b)
    }

    // ------------------------------------------------------- runtime state gate

    private fun state(
        enabled: Boolean,
        accessibility: Boolean = true,
        overlay: Boolean = true,
        usage: Boolean = true,
        camera: Boolean = true,
    ) = ProtectionRuntimeState(
        enabled = enabled,
        accessibilityEnabled = accessibility,
        overlayGranted = overlay,
        usageAccessGranted = usage,
        cameraGranted = camera,
    )

    @Test
    fun aSwitchedOffDeviceIsNotDegraded() {
        // Protection is off, so missing capabilities are not a degradation to nag about.
        val off = state(enabled = false, accessibility = false, overlay = false, usage = false, camera = false)
        assertTrue(off.degradedCapabilities.isEmpty())
        assertFalse(off.degraded)
    }

    @Test
    fun anEnabledButIncapableDeviceIsDegraded() {
        assertTrue(state(enabled = true, overlay = false).degraded)
        assertTrue(state(enabled = true, camera = false).degraded)
        assertEquals(
            setOf(ProtectionCapability.OVERLAY),
            state(enabled = true, overlay = false).degradedCapabilities,
        )
    }

    @Test
    fun anEnabledFullyCapableDeviceIsNotDegraded() {
        assertFalse(state(enabled = true).degraded)
    }

    @Test
    fun degradationIsIndependentOfTheReadyGate() {
        // A device can be enabled + degraded but not "ready" for a different reason
        // (no enrolment); degraded and ready answer different questions.
        val fullyCapableButNotReady = state(enabled = true)
        assertTrue(fullyCapableButNotReady.degradedCapabilities.isEmpty())
        assertFalse(fullyCapableButNotReady.ready)
    }
}
