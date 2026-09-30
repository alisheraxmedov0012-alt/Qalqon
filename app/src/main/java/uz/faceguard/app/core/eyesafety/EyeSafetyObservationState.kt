package uz.faceguard.app.core.eyesafety

import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyEvaluator
import uz.faceguard.app.domain.eyesafety.EyeSafetyFrame
import uz.faceguard.app.domain.policy.EyeSafetyState
import uz.faceguard.app.domain.policy.PolicySettings

/**
 * Phase 6 Step 4: the eye-safety observation state of the currently recognised child.
 *
 * It is deliberately small and owns exactly one thing: **which child's evaluator is active, and
 * when it must be replaced**. The thresholds, hysteresis, `confirmFrames`, presence ratio and
 * window eviction all stay in the Phase 6 Step 1 [EyeSafetyEvaluator] this delegates to — nothing
 * about that behaviour is reimplemented here.
 *
 * The gate is applied once, in [observe]:
 *
 *  - **no recognised child** (the caller passes `null` for a parent, an unknown user, no face, an
 *    obstructed camera or an unstable recognition) → no session at all, so a child's configuration
 *    is never consulted and a child's restriction can never reach a non-child;
 *  - **no configuration, or a disabled one** → no session, so eye safety is a no-op (never a
 *    fabricated DANGER);
 *  - **a different child, or a changed configuration** → the session is rebuilt rather than reused,
 *    so the previous child's confirmed state cannot leak into the new one.
 *
 * Being free of Android, Room and coroutines, this is directly testable on the JVM — which matters,
 * because the transition and reset rules above are the part of the runtime integration most likely
 * to regress.
 */
class EyeSafetyObservationState {

    private var session: Session? = null

    /**
     * One child's active session. The evaluator is built from that child's own configuration, so
     * `confirmFrames` and the four thresholds are the child's — the runtime never hard-codes them.
     */
    private class Session(val child: ChildEyeSafetyConfig) {
        val evaluator = EyeSafetyEvaluator(child.config)
    }

    /** The child whose configuration is currently active, or `null` when eye safety is a no-op. */
    val activeChildId: Long? get() = session?.child?.childId

    /** The active child's configuration, for the caller that needs its configured actions. */
    val activeChild: ChildEyeSafetyConfig? get() = session?.child

    /** The last settled state; [EyeSafetyState.UNKNOWN] while no session is active. */
    val currentState: EyeSafetyState get() = session?.evaluator?.currentState ?: EyeSafetyState.UNKNOWN

    /**
     * Feeds one observation in and returns the state the policy context should carry.
     *
     * A `null` [observation] is **not** an observation: the window ages out on its own, so a
     * stopped camera decays to UNKNOWN instead of holding a stale verdict. A frame with no usable
     * face is fed as "no measurement" (see [eyeSafetyFrameOf]), never as a zero ratio.
     */
    fun observe(
        observation: EyeSafetyFrame?,
        childId: Long?,
        child: ChildEyeSafetyConfig?,
        now: Long,
    ): EyeSafetyState {
        val active = child?.takeIf { it.config.enabled && it.childId == childId }
        if (childId == null || active == null) {
            reset()
            return EyeSafetyState.UNKNOWN
        }

        val existing = session
        val current = if (existing != null && existing.child == active) {
            existing
        } else {
            // A different child or a changed configuration: rebuild, so no state is inherited.
            reset()
            Session(active).also { session = it }
        }

        if (observation != null) current.evaluator.observe(observation)
        return current.evaluator.result(now).state
    }

    /** Drops the session (no child, disabled, unconfigured, config changed, or session stop). */
    fun reset() {
        session = null
    }

    /**
     * [base] with the active child's configured eye-safety actions applied, or [base] unchanged
     * when no session is active.
     *
     * Only the two eye-safety fields are replaced, so every other policy input — the child action,
     * the recovery delay, the parent-device bypass — is exactly what the runtime supplied. The
     * actions are the child's own persisted values, which is why they take precedence over the
     * global defaults while that child's session is active.
     */
    fun policySettings(base: PolicySettings): PolicySettings {
        val child = session?.child ?: return base
        return base.copy(
            eyeSafetyWarningAction = child.warningAction,
            eyeSafetyDangerAction = child.dangerAction,
        )
    }
}
