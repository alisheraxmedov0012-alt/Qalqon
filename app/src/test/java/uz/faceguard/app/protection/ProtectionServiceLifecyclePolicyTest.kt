package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionServiceLifecyclePolicy
import uz.faceguard.app.core.protection.ProtectionServicePolicy
import uz.faceguard.app.core.protection.ServiceRestartMode

/**
 * Stage 5: the background-runtime restart/removal contract.
 *
 * These are pure decisions, so the *strategy* is asserted directly and cannot
 * silently drift (the service maps them onto real `Service` constants).
 */
class ProtectionServiceLifecyclePolicyTest {

    @Test
    fun theRestartStrategyIsSticky_soAnOsInitiatedKillCanBeRecovered() {
        // A protection service the user explicitly enabled must be restorable after an
        // OS low-memory kill; NOT_STICKY makes such a loss permanent and silent.
        assertEquals(ServiceRestartMode.STICKY, ProtectionServiceLifecyclePolicy.restartMode)
        assertTrue(
            "the only recovery for an OS-initiated process kill is a sticky restart",
            ProtectionServiceLifecyclePolicy.restartMode != ServiceRestartMode.NOT_STICKY,
        )
    }

    @Test
    fun removingTheAppFromRecents_mustNotEndProtection() {
        // A child swiping the task away is not a legitimate way to disable protection.
        assertFalse(ProtectionServiceLifecyclePolicy.shouldStopOnTaskRemoved())
    }

    @Test
    fun aSystemRestartSelfStops_whenProtectionWasTurnedOff() {
        assertTrue(
            ProtectionServiceLifecyclePolicy.shouldSelfStopAfterSystemRestart(
                protectionEnabled = false,
                accountId = 7L,
            ),
        )
    }

    @Test
    fun aSystemRestartSelfStops_whenSignedOut() {
        assertTrue(
            ProtectionServiceLifecyclePolicy.shouldSelfStopAfterSystemRestart(
                protectionEnabled = true,
                accountId = null,
            ),
        )
    }

    @Test
    fun aSystemRestartContinues_whenProtectionIsStillWanted() {
        assertFalse(
            ProtectionServiceLifecyclePolicy.shouldSelfStopAfterSystemRestart(
                protectionEnabled = true,
                accountId = 7L,
            ),
        )
    }

    @Test
    fun theSelfStopRuleIsExactlyTheInverseOfShouldRun() {
        // If these ever disagree, a sticky restart could keep running for a session
        // that the rest of the runtime considers inactive (or vice versa).
        val cases = listOf(
            true to 1L,
            true to null,
            false to 1L,
            false to null,
        )
        cases.forEach { (enabled, accountId) ->
            assertEquals(
                "self-stop must be the negation of shouldRun for enabled=$enabled account=$accountId",
                !ProtectionServicePolicy.shouldRun(enabled, accountId),
                ProtectionServiceLifecyclePolicy.shouldSelfStopAfterSystemRestart(enabled, accountId),
            )
        }
    }
}
