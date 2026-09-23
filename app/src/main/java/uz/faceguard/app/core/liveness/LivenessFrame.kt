package uz.faceguard.app.core.liveness

import uz.faceguard.app.core.pipeline.FrameEvent

/**
 * One frame's liveness evidence, decoupled from the Android frame types so the
 * temporal evaluator stays pure Kotlin and unit-testable on the JVM.
 *
 * [modelScore] is the anti-spoofing model's live probability for this frame, or
 * null when no model is available (the default in this build) — the detector
 * then falls back to the passive motion heuristic.
 */
data class LivenessFrame(
    val facePresent: Boolean,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val faceWidthRatio: Float = 0f,
    val modelScore: Float? = null,
    val timestamp: Long,
) {
    companion object {
        /**
         * Projects a detected [FrameEvent] into liveness evidence. A frame counts
         * as "face present" when the detector reported one or the embedder
         * produced a feature vector.
         */
        fun from(frame: FrameEvent): LivenessFrame {
            val quality = frame.quality
            return LivenessFrame(
                facePresent = (quality?.faceCount ?: 0) > 0 || (frame.features?.isNotEmpty() == true),
                yawDegrees = quality?.headEulerAngleY ?: 0f,
                pitchDegrees = quality?.headEulerAngleX ?: 0f,
                rollDegrees = quality?.headEulerAngleZ ?: 0f,
                faceWidthRatio = quality?.faceWidthRatio ?: 0f,
                modelScore = frame.liveProbability,
                timestamp = frame.timestamp,
            )
        }
    }
}
