package uz.faceguard.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinAttemptState
import uz.faceguard.app.domain.security.PinVerification
import uz.faceguard.app.domain.security.formatLockoutRemaining

/**
 * Typed PIN verification and the lockout feedback the emergency-unlock UI shows.
 *
 * Pure JVM: the storage-backed behaviour (AccountRepositoryImpl.verifyPin) is
 * covered by the instrumented PinSecurityPersistenceTest; here the *decision* and
 * *presentation* rules are pinned.
 */
class PinVerificationTest {

    // ---- typed result ------------------------------------------------------

    @Test
    fun aLockedOutResultAlwaysCarriesAPositiveWait() {
        val state = PinAttemptPolicy.onFailure(PinAttemptState(failedCount = 4), nowMillis = 1_000L)
        val outcome = PinAttemptPolicy.outcomeForFailure(state, nowMillis = 1_000L)

        assertTrue(outcome is PinVerification.LockedOut)
        assertTrue("a lockout must show a non-zero wait", (outcome as PinVerification.LockedOut).remainingMillis > 0L)
    }

    @Test
    fun aWrongPinThatDoesNotTripTheThresholdIsInvalidPin() {
        val state = PinAttemptPolicy.onFailure(PinAttemptState(), nowMillis = 1_000L) // 1 failure

        assertEquals(PinVerification.InvalidPin, PinAttemptPolicy.outcomeForFailure(state, nowMillis = 1_000L))
    }

    @Test
    fun theTippingFailureReportsTheLockoutImmediately() {
        // Four failures recorded, the fifth trips the threshold on this very attempt.
        val state = PinAttemptPolicy.onFailure(PinAttemptState(failedCount = 4), nowMillis = 5_000L)
        val outcome = PinAttemptPolicy.outcomeForFailure(state, nowMillis = 5_000L)

        assertEquals(PinVerification.LockedOut(PinAttemptPolicy.LOCKOUT_STEPS_MS[0]), outcome)
    }

    @Test
    fun anElapsedLockoutIsNoLongerLockedOut() {
        val expired = PinAttemptState(failedCount = PinAttemptPolicy.MAX_ATTEMPTS, lockedUntilMillis = 1_000L)

        assertEquals(PinVerification.InvalidPin, PinAttemptPolicy.outcomeForFailure(expired, nowMillis = 2_000L))
    }

    @Test
    fun escalationIncreasesTheReportedWaitAndIsCapped() {
        // First lockout.
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 0L) }
        assertEquals(
            PinVerification.LockedOut(PinAttemptPolicy.LOCKOUT_STEPS_MS[0]),
            PinAttemptPolicy.outcomeForFailure(state, nowMillis = 0L),
        )

        // Second lockout (continue the same failure count) escalates one step.
        state = state.copy(lockedUntilMillis = 0L)
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 0L) }
        assertEquals(
            PinVerification.LockedOut(PinAttemptPolicy.LOCKOUT_STEPS_MS[1]),
            PinAttemptPolicy.outcomeForFailure(state, nowMillis = 0L),
        )

        // Many more lockouts never exceed the cap.
        repeat(PinAttemptPolicy.MAX_ATTEMPTS * 50) { state = PinAttemptPolicy.onFailure(state, 0L) }
        val capped = PinAttemptPolicy.outcomeForFailure(state, nowMillis = 0L) as PinVerification.LockedOut
        assertTrue(capped.remainingMillis <= PinAttemptPolicy.MAX_LOCKOUT_MS)
        assertTrue(capped.remainingMillis > 0L)
    }

    @Test
    fun theWaitCountsDownAsTheClockAdvances() {
        val locked = PinAttemptState(
            failedCount = PinAttemptPolicy.MAX_ATTEMPTS,
            lockedUntilMillis = 30_000L,
        )

        assertEquals(30_000L, locked.remainingLockMillis(0L))
        assertEquals(20_000L, locked.remainingLockMillis(10_000L))
        assertEquals(0L, locked.remainingLockMillis(30_000L))
        assertFalse(locked.isLocked(30_000L))
        assertTrue(locked.isLocked(29_999L))
    }

    // ---- lockout timer presentation ---------------------------------------

    @Test
    fun theLockoutLabelRendersMinutesAndSeconds() {
        assertEquals("0:00", formatLockoutRemaining(0L))
        assertEquals("0:05", formatLockoutRemaining(5_000L))
        assertEquals("0:30", formatLockoutRemaining(30_000L))
        assertEquals("1:05", formatLockoutRemaining(65_000L))
        assertEquals("15:00", formatLockoutRemaining(PinAttemptPolicy.MAX_LOCKOUT_MS))
    }

    @Test
    fun partialSecondsRoundUpSoAnActiveLockoutNeverShowsZero() {
        assertEquals("0:01", formatLockoutRemaining(1L))
        assertEquals("0:01", formatLockoutRemaining(999L))
        assertEquals("0:01", formatLockoutRemaining(1_000L))
    }

    @Test
    fun aNegativeRemainingRendersAsZero() {
        assertEquals("0:00", formatLockoutRemaining(-5_000L))
    }
}
