package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.ActivationDelayGate
import uz.faceguard.app.domain.policy.ProtectionAction

class ActivationDelayGateTest {

    private var now = 0L
    private val gate = ActivationDelayGate { now }

    @Test
    fun `zero delay applies immediately`() {
        assertTrue(gate.request(ProtectionAction.HARD_BLOCK, 0L))
        assertFalse(gate.isPending)
    }

    @Test
    fun `positive delay waits for the whole steady window`() {
        assertFalse(gate.request(ProtectionAction.HARD_BLOCK, 3_000L))
        assertTrue(gate.isPending)

        now = 1_500L
        assertFalse(gate.isReady())
        assertEquals(0.5f, gate.progress(), 0.01f)

        now = 3_000L
        assertTrue(gate.isReady())
    }

    @Test
    fun `requesting a different action restarts the window`() {
        assertFalse(gate.request(ProtectionAction.HARD_BLOCK, 3_000L))
        now = 2_000L
        assertFalse(gate.request(ProtectionAction.SOFT_BLOCK, 3_000L))
        now = 4_000L
        assertFalse(gate.isReady())
        now = 5_000L
        assertTrue(gate.isReady())
    }

    @Test
    fun `repeating the same action keeps the original window`() {
        assertFalse(gate.request(ProtectionAction.HARD_BLOCK, 3_000L))
        now = 2_000L
        assertFalse(gate.request(ProtectionAction.HARD_BLOCK, 3_000L))
        now = 3_000L
        assertTrue(gate.isReady())
    }

    @Test
    fun `cancel clears pending activation`() {
        assertFalse(gate.request(ProtectionAction.HARD_BLOCK, 3_000L))
        gate.cancel()
        assertFalse(gate.isPending)
        assertFalse(gate.isReady())
        now = 10_000L
        assertFalse(gate.isReady())
    }
}
