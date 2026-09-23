package uz.faceguard.app.core.liveness

/**
 * Group 9: keeps the temporal window and turns it into the current
 * [LivenessResult].
 *
 * The evaluator owns no algorithm — [LivenessDetector] does — so it is pure,
 * deterministic and reusable outside the engine (the engine just calls
 * [observe] per frame and reads [result]).
 */
class LivenessEvaluator(
    private val detector: LivenessDetector = TemporalLivenessDetector(),
    private val window: LivenessWindow = LivenessWindow(),
) {

    /** Number of frames currently held; exposed for diagnostics/tests. */
    val frameCount: Int get() = window.size

    /** Feeds one frame of evidence; duplicates/out-of-order frames are ignored. */
    fun observe(frame: LivenessFrame) {
        window.add(frame)
    }

    /** Current liveness, decided from the window as observed at [now]. */
    fun result(now: Long = System.currentTimeMillis()): LivenessResult =
        detector.detect(window.snapshot(now))

    /** Drops all observations (session stop / account change / sign-out). */
    fun reset() {
        window.clear()
    }
}
