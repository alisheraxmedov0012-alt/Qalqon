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

    private var armedAt = 0L
    private var armedDelay = 0L
    private var pendingAction: ProtectionAction? = null

    val isPending: Boolean get() = pendingAction != null

    /** True when [action] is already active (no pending window). */
    fun isActive(): Boolean = pendingAction == null && armedDelay == 0L && armedAt != 0L

    /**
     * Requests [action] with [delayMs]. Returns true when it may be applied
     * immediately (delay <= 0), false while the steady window is still open.
     */
    fun request(action: ProtectionAction, delayMs: Long): Boolean {
        if (delayMs <= 0L) {
            armedAt = clock()
            armedDelay = 0L
            pendingAction = null
            return true
        }
        // Same action already counting down -> keep the original window.
        if (pendingAction != action || armedAt == 0L) {
            armedAt = clock()
            armedDelay = delayMs
            pendingAction = action
        }
        return false
    }

    /** Call while the same action still applies; returns the elapsed window. */
    fun elapsedMs(): Long = if (armedAt == 0L) 0L else (clock() - armedAt).coerceAtLeast(0L)

    fun progress(): Float {
        if (pendingAction == null || armedDelay <= 0L) return if (isActive()) 1f else 0f
        return (elapsedMs().toFloat() / armedDelay.toFloat()).coerceIn(0f, 1f)
    }

    /** True when the pending window has elapsed and the action should apply. */
    fun isReady(): Boolean = pendingAction != null && elapsedMs() >= armedDelay

    /** Cancels any pending or active action (e.g. parent override). */
    fun cancel() {
        armedAt = 0L
        armedDelay = 0L
        pendingAction = null
    }
}
