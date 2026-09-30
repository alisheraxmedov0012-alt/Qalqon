package uz.faceguard.app.domain.eyesafety

import uz.faceguard.app.domain.policy.EyeSafetyState

/**
 * Phase 6 Step 1: decides an [EyeSafetyResult] from a window of per-frame evidence.
 *
 * Pure Kotlin, synchronous and side-effect free, so the whole eye-safety decision is unit-testable
 * on the JVM and neither the engine nor the runtime ever contains the algorithm.
 *
 * Unlike the liveness detector, the signature also takes the **previous state**. Eye safety needs
 * it: its thresholds are hysteretic (leaving a state uses that state's exit threshold), so the same
 * ratio can legitimately mean "still dangerous" or "now only a warning" depending on where the
 * state already was. Passing it in keeps the decision a pure function of (evidence, previous state)
 * rather than a hidden mutation.
 */
fun interface EyeSafetyDetector {
    fun detect(frames: List<EyeSafetyFrame>, previous: EyeSafetyState): EyeSafetyResult
}

/**
 * The threshold + hysteresis + persistence decision.
 *
 * The decision runs in four steps, each of which can only ever make the engine *less* certain:
 *
 *  1. **enabled** — a disabled configuration reports UNKNOWN and enforces nothing;
 *  2. **presence** — the share of the window that contains a face must reach
 *     [EyeSafetyConfig.minimumPresenceRatio], otherwise UNKNOWN. No face is never a distance.
 *  3. **enough evidence** — at least [EyeSafetyConfig.confirmFrames] usable observations must be
 *     present, otherwise UNKNOWN. A ratio that is missing, zero, non-finite or above one is not a
 *     usable observation, so it can never be mistaken for "very close" or for "safe".
 *  4. **persistence + hysteresis** — the newest [EyeSafetyConfig.confirmFrames] usable
 *     observations are each classified against [previous]; only when they **agree** does the state
 *     change. Otherwise the previous state is held, so a single noisy frame (or a single far frame
 *     between near ones) cannot flip the state.
 *
 * The per-observation classification is the hysteresis itself: stay in DANGER while the ratio is at
 * or above `dangerExitThreshold`; otherwise enter DANGER at `dangerEnterThreshold`, and likewise
 * for WARNING with its own boundaries. A ratio inside a dead band therefore preserves the current
 * state instead of oscillating.
 */
class TemporalEyeSafetyDetector(
    private val config: EyeSafetyConfig,
) : EyeSafetyDetector {

    override fun detect(frames: List<EyeSafetyFrame>, previous: EyeSafetyState): EyeSafetyResult {
        if (!config.enabled) return unknown(frames)

        val newest = frames.lastOrNull()?.timestampMs ?: return EyeSafetyResult.UNKNOWN
        val faceFrames = frames.count { it.facePresent }
        val presenceRatio = faceFrames.toFloat() / frames.size.toFloat()
        if (presenceRatio < config.minimumPresenceRatio) return unknown(frames)

        val usableRatios = frames.mapNotNull { it.usableRatio }
        if (usableRatios.size < config.confirmFrames) return unknown(frames)

        val agreeing = usableRatios.takeLast(config.confirmFrames)
            .map { levelFor(it, previous) }
            .distinct()

        // A split verdict is not a transition: hold where the engine already is.
        val state = if (agreeing.size == 1) agreeing.single() else previous

        return EyeSafetyResult(
            state = state,
            ratio = usableRatios.last(),
            observedFrames = frames.size,
            timestampMs = newest,
        )
    }

    /**
     * The level one observation implies, given where the engine currently is.
     *
     * Order matters. Staying in the current state is tested before entering a stricter one, so a
     * [EyeSafetyState.DANGER] at 0.36 stays DANGER (above its exit threshold) rather than being
     * re-derived from the enter thresholds; but the WARNING "stay" is bounded above by
     * `dangerEnterThreshold`, so a warning that keeps growing still escalates to DANGER.
     */
    private fun levelFor(ratio: Float, previous: EyeSafetyState): EyeSafetyState = when {
        previous == EyeSafetyState.DANGER && ratio >= config.dangerExitThreshold -> EyeSafetyState.DANGER

        previous == EyeSafetyState.WARNING &&
            ratio >= config.warningExitThreshold &&
            ratio < config.dangerEnterThreshold -> EyeSafetyState.WARNING

        ratio >= config.dangerEnterThreshold -> EyeSafetyState.DANGER
        ratio >= config.warningEnterThreshold -> EyeSafetyState.WARNING
        else -> EyeSafetyState.SAFE
    }

    private fun unknown(frames: List<EyeSafetyFrame>) = EyeSafetyResult(
        state = EyeSafetyState.UNKNOWN,
        ratio = null,
        observedFrames = frames.size,
        timestampMs = frames.lastOrNull()?.timestampMs ?: 0L,
    )
}
