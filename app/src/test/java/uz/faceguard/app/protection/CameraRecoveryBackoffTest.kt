package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.CameraRecoveryBackoff

/**
 * Stage 5: the bounded camera-recovery backoff.
 *
 * The invariant: recovery is bounded (no infinite retry loop), delays grow, and a
 * success resets the budget so a later, independent interruption starts fresh.
 */
class CameraRecoveryBackoffTest {

    @Test
    fun firstAttemptUsesTheShortestDelay() {
        val backoff = CameraRecoveryBackoff(listOf(10L, 100L, 1_000L))
        assertEquals(10L, backoff.nextDelayMs())
    }

    @Test
    fun delaysGrowAndAreConsumedInOrder() {
        val schedule = listOf(10L, 100L, 1_000L)
        val backoff = CameraRecoveryBackoff(schedule)

        schedule.forEach { expected ->
            assertEquals(expected, backoff.nextDelayMs())
        }
    }

    @Test
    fun retriesAreBounded_thenItReportsExhaustion() {
        val backoff = CameraRecoveryBackoff(listOf(1L, 2L))

        assertEquals(1L, backoff.nextDelayMs())
        assertEquals(2L, backoff.nextDelayMs())
        assertNull("no attempt beyond the bound", backoff.nextDelayMs())
        assertTrue(backoff.isExhausted)
    }

    @Test
    fun attemptsCounterReflectsConsumedRetries() {
        val backoff = CameraRecoveryBackoff(listOf(1L, 2L, 3L))
        assertEquals(0, backoff.attempts)
        backoff.nextDelayMs()
        assertEquals(1, backoff.attempts)
        backoff.nextDelayMs()
        assertEquals(2, backoff.attempts)
    }

    @Test
    fun exhaustionDoesNotConsumeFurtherAttempts() {
        val backoff = CameraRecoveryBackoff(listOf(1L))
        backoff.nextDelayMs()
        assertNull(backoff.nextDelayMs())
        assertNull(backoff.nextDelayMs())
        assertEquals("exhaustion must not keep incrementing", 1, backoff.attempts)
    }

    @Test
    fun aSuccessfulRebindResetsTheBudget() {
        val backoff = CameraRecoveryBackoff(listOf(10L, 20L))
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertTrue(backoff.isExhausted)

        backoff.reset()

        assertFalse(backoff.isExhausted)
        assertEquals(0, backoff.attempts)
        assertEquals("the cycle starts from the shortest delay again", 10L, backoff.nextDelayMs())
    }

    @Test
    fun anEmptyScheduleIsImmediatelyExhausted() {
        val backoff = CameraRecoveryBackoff(emptyList())
        assertTrue(backoff.isExhausted)
        assertNull(backoff.nextDelayMs())
    }

    @Test
    fun productionScheduleIsBoundedAndEveryDelayIsPositive() {
        val schedule = CameraRecoveryBackoff.DEFAULT_DELAYS_MS
        assertTrue("recovery must be bounded", schedule.isNotEmpty())
        assertTrue("a non-positive delay would be a tight loop", schedule.all { it > 0L })
        assertTrue(
            "delays must not shrink (that would be a backoff, not a busy loop)",
            schedule.zipWithNext().all { (a, b) -> b >= a },
        )
    }
}
