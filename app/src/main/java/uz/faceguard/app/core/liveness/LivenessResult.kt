package uz.faceguard.app.core.liveness

import uz.faceguard.app.domain.policy.LivenessState

/**
 * Where a liveness observation came from.
 *
 * - [NONE]      — no observation was available (pipeline idle / window empty).
 * - [HEURISTIC] — the on-device passive motion heuristic (no model bundled).
 * - [MODEL]     — a real anti-spoofing model scored the frames.
 */
enum class LivenessSource { NONE, HEURISTIC, MODEL }

/**
 * Group 9: the liveness signal — "is a real person in front of the camera?".
 *
 * This is intentionally a *separate* signal from the identity signal
 * (`IdentitySnapshot`): identity answers "who", liveness answers "is it real".
 * Downstream code (policy/runtime) keeps them independent, so a spoofed face can
 * be recognised as a parent/child without being trusted.
 *
 * [confidence] is deliberately nullable. It is only populated when the detector
 * actually produces a score with defined semantics (currently only the
 * model-backed path, whose value is the mean model probability). The passive
 * heuristic does not yield a calibrated probability, so it reports `null`
 * rather than inventing one.
 */
data class LivenessResult(
    val state: LivenessState,
    val confidence: Float?,
    /** When this observation was made (newest frame in the window). */
    val timestamp: Long,
    val source: LivenessSource,
)
