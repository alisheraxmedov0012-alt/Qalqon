package uz.faceguard.app.domain.security

/**
 * Phase 12: local brute-force resistance for the parent PIN.
 *
 * Pure policy so the escalation is unit-testable and identical everywhere it is
 * applied (login, emergency unlock, protected-setting authorization). It is
 * account-scoped by the caller and never permanently locks a legitimate parent out:
 * the lockout is temporary and grows only with repeated failures.
 */
data class PinAttemptState(
    val failedCount: Int = 0,
    val lockedUntilMillis: Long = 0L,
) {
    fun isLocked(nowMillis: Long): Boolean = lockedUntilMillis > nowMillis

    fun remainingLockMillis(nowMillis: Long): Long = (lockedUntilMillis - nowMillis).coerceAtLeast(0L)
}

object PinAttemptPolicy {

    /** Failures allowed before a temporary lockout starts. */
    const val MAX_ATTEMPTS = 5

    /** Escalating, capped lockout durations (ms) for successive lockouts. */
    val LOCKOUT_STEPS_MS = longArrayOf(30_000L, 60_000L, 300_000L, 900_000L)

    const val MAX_LOCKOUT_MS = 900_000L

    /**
     * Records a failed attempt. Every [MAX_ATTEMPTS] failures start a temporary
     * lockout, escalating one step at a time and capped at [MAX_LOCKOUT_MS].
     */
    fun onFailure(current: PinAttemptState, nowMillis: Long): PinAttemptState {
        val failed = current.failedCount + 1
        if (failed % MAX_ATTEMPTS != 0) {
            return current.copy(failedCount = failed, lockedUntilMillis = 0L)
        }
        val lockoutIndex = (failed / MAX_ATTEMPTS - 1).coerceIn(0, LOCKOUT_STEPS_MS.lastIndex)
        return PinAttemptState(
            failedCount = failed,
            lockedUntilMillis = nowMillis + LOCKOUT_STEPS_MS[lockoutIndex],
        )
    }

    /** A successful authentication resets both the counter and the lockout. */
    fun onSuccess(): PinAttemptState = PinAttemptState()

    /** Remaining failures before the next lockout (never negative). */
    fun remainingAttempts(state: PinAttemptState): Int =
        (MAX_ATTEMPTS - (state.failedCount % MAX_ATTEMPTS)).let { if (it == 0) MAX_ATTEMPTS else it }
}
