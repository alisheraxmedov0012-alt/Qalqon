package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionRuntimeState

/**
 * Stage 5: the two new reporting fields must stay *reporting only*.
 *
 * [ProtectionRuntimeState.settingsLoaded] and
 * [ProtectionRuntimeState.cameraRecovering] exist so a restart and a camera
 * recovery can be described honestly. Neither may change readiness or the missing
 * capability set — those are decided by real capability probes, and coupling a
 * transient recovery state to them would make protection report itself degraded
 * (or ready) for a reason that is not a capability at all.
 */
class Stage5RuntimeStateContractTest {

    private fun readyState(
        settingsLoaded: Boolean = false,
        cameraRecovering: Boolean = false,
    ) = ProtectionRuntimeState(
        enabled = true,
        active = true,
        overlayGranted = true,
        usageAccessGranted = true,
        accessibilityEnabled = true,
        cameraGranted = true,
        protectedCount = 1,
        parentFaceEnrolled = true,
        settingsLoaded = settingsLoaded,
        cameraRecovering = cameraRecovering,
    )

    @Test
    fun theNewReportingFieldsDefaultToFalse() {
        val state = ProtectionRuntimeState()
        assertFalse(state.settingsLoaded)
        assertFalse(state.cameraRecovering)
    }

    @Test
    fun cameraRecoveryIsReportedButIsNotAMissingCapability() {
        val recovering = readyState(cameraRecovering = true)

        assertTrue("recovering must be visible", recovering.cameraRecovering)
        assertTrue(recovering.degradedCapabilities.isEmpty())
        assertFalse("a transient recovery is not a degraded capability set", recovering.degraded)
    }

    @Test
    fun settingsLoadedDoesNotChangeReadinessOrDegradation() {
        val loaded = readyState(settingsLoaded = true)
        val notLoaded = readyState(settingsLoaded = false)

        assertEquals(loaded.ready, notLoaded.ready)
        assertEquals(loaded.degradedCapabilities, notLoaded.degradedCapabilities)
        assertEquals(loaded.degraded, notLoaded.degraded)
    }

    @Test
    fun cameraRecoveryDoesNotChangeReadinessOrTheBootLimitation() {
        val recovering = readyState(cameraRecovering = true)
        val healthy = readyState(cameraRecovering = false)

        assertEquals(healthy.ready, recovering.ready)
        assertEquals(healthy.degradedCapabilities, recovering.degradedCapabilities)
        assertEquals(healthy.cameraLimitedAfterBoot, recovering.cameraLimitedAfterBoot)
    }
}
