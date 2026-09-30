package uz.faceguard.app.core.liveness

import uz.faceguard.app.domain.policy.LivenessState

/**
 * Group 9: the on-device liveness decision, made over a short temporal window.
 *
 * Two honest signal paths, in priority order:
 *
 * 1. **Model path** — when the window holds enough frames carrying a real
 *    anti-spoofing model score ([LivenessFrame.modelScore]), the mean probability
 *    decides LIVE / SPOOF / UNSTABLE and [LivenessResult.confidence] is that
 *    mean. This path only activates when a model is actually supplied; no
 *    anti-spoofing model is bundled in this build.
 *
 * 2. **Passive heuristic path** — otherwise a passive, motion-based signal is
 *    used. A live 3D face exhibits natural micro-movement / head-pose variation
 *    within the window; a flat, perfectly static presentation does not. Frames
 *    with enough pose variation are LIVE, frames that are essentially motionless
 *    (see [staticMotionDegrees]) are reported as UNKNOWN rather than LIVE — a
 *    still photo *must not* be trusted, but neither is it positively labelled
 *    SPOOF (that would need a model; labelling every still real person a spoof
 *    would be a worse failure). Values in between are UNSTABLE.
 *
 * Honest limitation: the heuristic is a weak signal. It cannot reliably separate
 * a printed photo / phone screen / video replay from a real face (a hand-shaken
 * photo moves too), so this build makes **no real-world spoofing-efficacy claim**
 * — see the README. It never emits [LivenessState.SPOOF] without a model.
 */
class TemporalLivenessDetector(
    private val minFrames: Int = DEFAULT_MIN_FRAMES,
    private val presenceRatioMin: Float = DEFAULT_PRESENCE_RATIO,
    private val modelMinFrames: Int = DEFAULT_MODEL_MIN_FRAMES,
    private val modelSpoofThreshold: Float = DEFAULT_MODEL_SPOOF_THRESHOLD,
    private val modelLiveThreshold: Float = DEFAULT_MODEL_LIVE_THRESHOLD,
    private val liveMotionDegrees: Float = DEFAULT_LIVE_MOTION_DEGREES,
    private val staticMotionDegrees: Float = DEFAULT_STATIC_MOTION_DEGREES,
) : LivenessDetector {

    override fun detect(frames: List<LivenessFrame>): LivenessResult {
        if (frames.isEmpty()) {
            return LivenessResult(LivenessState.UNKNOWN, null, 0L, LivenessSource.NONE)
        }
        val timestamp = frames.last().timestamp
        val faceFrames = frames.filter { it.facePresent }
        val source = sourceOf(frames)

        if (faceFrames.isEmpty()) {
            return LivenessResult(LivenessState.NO_FACE, null, timestamp, source)
        }
        // Not enough evidence yet to decide anything but "no face".
        if (frames.size < minFrames) {
            return LivenessResult(LivenessState.UNKNOWN, null, timestamp, source)
        }
        if (faceFrames.size.toFloat() / frames.size.toFloat() < presenceRatioMin) {
            return LivenessResult(LivenessState.NO_FACE, null, timestamp, source)
        }

        val modelScores = faceFrames.mapNotNull { it.modelScore }
        if (modelScores.size >= modelMinFrames) {
            val mean = modelScores.average().toFloat().coerceIn(0f, 1f)
            return when {
                mean <= modelSpoofThreshold ->
                    LivenessResult(LivenessState.SPOOF, (1f - mean).coerceIn(0f, 1f), timestamp, LivenessSource.MODEL)
                mean >= modelLiveThreshold ->
                    LivenessResult(LivenessState.LIVE, mean, timestamp, LivenessSource.MODEL)
                else ->
                    LivenessResult(LivenessState.UNSTABLE, null, timestamp, LivenessSource.MODEL)
            }
        }

        val motion = maxOf(
            range(faceFrames.map { it.yawDegrees }),
            range(faceFrames.map { it.pitchDegrees }),
        )
        return when {
            motion >= liveMotionDegrees ->
                LivenessResult(LivenessState.LIVE, null, timestamp, LivenessSource.HEURISTIC)
            motion <= staticMotionDegrees ->
                LivenessResult(LivenessState.UNKNOWN, null, timestamp, LivenessSource.HEURISTIC)
            else ->
                LivenessResult(LivenessState.UNSTABLE, null, timestamp, LivenessSource.HEURISTIC)
        }
    }

    private fun sourceOf(frames: List<LivenessFrame>): LivenessSource =
        if (frames.any { it.modelScore != null }) LivenessSource.MODEL else LivenessSource.HEURISTIC

    private fun range(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        var min = values.first()
        var max = values.first()
        for (value in values) {
            if (value < min) min = value
            if (value > max) max = value
        }
        return max - min
    }

    companion object {
        /** Minimum frames in the window before any state beyond NO_FACE/UNKNOWN. */
        const val DEFAULT_MIN_FRAMES = 3

        /** Minimum share of the window that must contain a face. */
        const val DEFAULT_PRESENCE_RATIO = 0.5f

        /** Minimum scored frames before the model path is used. */
        const val DEFAULT_MODEL_MIN_FRAMES = 3

        /** Mean model probability at/below which the presentation is SPOOF. */
        const val DEFAULT_MODEL_SPOOF_THRESHOLD = 0.35f

        /** Mean model probability at/above which the presentation is LIVE. */
        const val DEFAULT_MODEL_LIVE_THRESHOLD = 0.65f

        /** Head-pose range (degrees) at/above which the heuristic reports LIVE. */
        const val DEFAULT_LIVE_MOTION_DEGREES = 3.0f

        /** Head-pose range (degrees) at/below which the heuristic holds UNKNOWN. */
        const val DEFAULT_STATIC_MOTION_DEGREES = 0.8f
    }
}
