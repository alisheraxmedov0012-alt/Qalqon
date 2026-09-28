package uz.faceguard.app.domain.eyesafety

import uz.faceguard.app.domain.policy.EyeSafetyState

/**
 * Phase 6 Step 1: owns the temporal window and the confirmed state, and turns them into the current
 * [EyeSafetyResult].
 *
 * The evaluator owns no algorithm — [EyeSafetyDetector] does — so it stays pure, deterministic and
 * reusable outside the engine. It is called from the existing protection tick, but this class knows
 * nothing about the engine, Android or coroutines.
 *
 * The confirmed state is held here because hysteresis is stateful: the detector needs to know where
 * the engine already is to decide whether a ratio is "still dangerous". [result] is therefore a
 * function of (window, previous state), and calling it repeatedly without new frames returns the
 * same answer — a fixed window keeps producing the same state.
 *
 * [reset] is part of the contract because the state is per-child: an account or child change must
 * drop both the window and the confirmed state so one child's eye-safety state can never be
 * inherited by another.
 */
class EyeSafetyEvaluator(
    private val config: EyeSafetyConfig,
    private val detector: EyeSafetyDetector = TemporalEyeSafetyDetector(config),
    private val window: EyeSafetyWindow = EyeSafetyWindow(config.maxWindowAgeMs, config.maxWindowFrames),
) {

    private var confirmedState: EyeSafetyState = EyeSafetyState.UNKNOWN

    /** Number of frames currently held; exposed for diagnostics and tests. */
    val frameCount: Int get() = window.size

    /** The state the last [result] settled on; [EyeSafetyState.UNKNOWN] before any observation. */
    val currentState: EyeSafetyState get() = confirmedState

    fun observe(frame: EyeSafetyFrame) {
        window.add(frame)
    }

    /**
     * The current eye-safety state as observed at [now].
     *
     * Because the window decays with `now`, an idle pipeline (frames stopped arriving) ages out to
     * an empty window and reports [EyeSafetyState.UNKNOWN] rather than holding a stale verdict.
     */
    fun result(now: Long): EyeSafetyResult {
        val result = detector.detect(window.snapshot(now), confirmedState)
        if (result.state != confirmedState) confirmedState = result.state
        return result
    }

    /** Drops the window and the confirmed state (account change / child change / session stop). */
    fun reset() {
        window.clear()
        confirmedState = EyeSafetyState.UNKNOWN
    }
}
