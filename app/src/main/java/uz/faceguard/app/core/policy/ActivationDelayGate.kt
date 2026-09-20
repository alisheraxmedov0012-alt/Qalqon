package uz.faceguard.app.core.policy

import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Steady-window gate used before an action is enforced.
 *
 * A child must stay recognised for `activationDelayMs` before the configured
 * action applies; any contradicting observation cancels the pending action.
 * Pure Kotlin and clock-injectable so it is unit-testable.
 */
class ActivationDelayGate(private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * Explicit armed flag: the clock may legitimately return 0 (tests use a
     * zero-based clock), so a magic timestamp must not double as "not armed".
     */
    private var armed = false
    private var armedAt = 0L
    private var armedDelay = 0L
    private var pendingAction: ProtectionAction? = null

    /** True while an action is counting down its steady window. */
    val isPending: Boolean get() = armed && pendingAction != null

    /** True when an action is armed with no pending window (already applied). */
    fun isActive(): Boolean = armed && pendingAction == null

    /**
     * Requests [action] with [delayMs]. Returns true when it may be applied
     * immediately (delay <= 0), false while the steady window is still open.
     */
    fun request(action: ProtectionAction, delayMs: Long): Boolean {
        if (delayMs <= 0L) {
            armed = true
            armedAt = clock()
            armedDelay = 0L
            pendingAction = null
            return true
        }
        // Same action already counting down -> keep the original window.
        if (!armed || pendingAction != action) {
            armed = true
            armedAt = clock()
            armedDelay = delayMs
            pendingAction = action
        }
        return false
    }

    /** Milliseconds elapsed since the current action was armed. */
    fun elapsedMs(): Long = if (!armed) 0L else (clock() - armedAt).coerceAtLeast(0L)

    fun progress(): Float {
        if (!armed) return 0f
        if (pendingAction == null || armedDelay <= 0L) return 1f
        return (elapsedMs().toFloat() / armedDelay.toFloat()).coerceIn(0f, 1f)
    }

    /** True when the pending window has elapsed and the action should apply. */
    fun isReady(): Boolean = armed && (pendingAction == null || elapsedMs() >= armedDelay)

    /** Cancels any pending or active action (e.g. parent override). */
    fun cancel() {
        armed = false
        armedAt = 0L
        armedDelay = 0L
        pendingAction = null
    }
}
