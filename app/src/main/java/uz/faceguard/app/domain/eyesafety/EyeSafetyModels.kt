package uz.faceguard.app.domain.eyesafety

import uz.faceguard.app.domain.policy.EyeSafetyState

/**
 * Phase 6 Step 1: the eye-safety domain foundation (pure, no Android, no I/O).
 *
 * The engine answers one question: *is the face in front of the camera too large in the frame to
 * be a comfortable viewing distance?* It does **not** measure distance. There is no camera
 * intrinsics, no focal length and no calibration in this build, so an absolute claim such as
 * "25 cm" would be a fabrication. What is measured is the face's *relative* size in the frame,
 * which grows monotonically as the face approaches the camera and is therefore a valid
 * "closer / farther" signal while remaining an explicitly relative one.
 *
 * This package is deliberately free of Android, Room, Compose and coroutines: the camera pipeline
 * projects a frame into [EyeSafetyFrame] at the `core` boundary, and everything here is JVM
 * testable and deterministic.
 */

// ---------------------------------------------------------------------------
// Frame
// ---------------------------------------------------------------------------

/**
 * One frame's eye-safety evidence, decoupled from the Android frame types so the evaluator stays
 * pure Kotlin.
 *
 * [faceWidthRatio] is the detected face's bounding-box width divided by the frame width — the
 * `FaceQuality.faceWidthRatio` the existing camera pipeline already computes for every analysed
 * frame, so the engine needs no new camera pass and no new model.
 *
 * **Only [usableRatio] is ever classified.** That is what makes invalid input safe: a `NaN`,
 * infinite, negative, zero or greater-than-one ratio carries no measurement, and no measurement
 * can never be mistaken for "very close" (a fabricated DANGER) or for "safe". Such a frame is
 * reported as [EyeSafetyState.UNKNOWN] instead.
 */
data class EyeSafetyFrame(
    val timestampMs: Long,
    val facePresent: Boolean,
    val faceWidthRatio: Float,
) {

    /**
     * The ratio this frame contributes to classification, or `null` when the frame carries no
     * usable measurement.
     *
     * `null` covers every case that must not be treated as a distance: no face at all, a
     * non-finite ratio, a zero-width box, and a ratio above [MAX_RATIO]. A detected face with a
     * zero ratio is not "very far away" — a zero-width bounding box is not a usable measurement.
     */
    val usableRatio: Float?
        get() = when {
            !facePresent -> null
            !faceWidthRatio.isFinite() -> null
            faceWidthRatio <= 0f -> null
            faceWidthRatio > MAX_RATIO -> null
            else -> faceWidthRatio
        }

    companion object {
        /**
         * Largest ratio the domain accepts. A face wider than the frame is not a real measurement
         * of a face at any distance, so it is rejected rather than clamped.
         */
        const val MAX_RATIO: Float = 1f

        /** A frame in which the detector saw a face with [faceWidthRatio]. */
        fun measured(timestampMs: Long, faceWidthRatio: Float): EyeSafetyFrame =
            EyeSafetyFrame(timestampMs = timestampMs, facePresent = true, faceWidthRatio = faceWidthRatio)

        /**
         * A frame in which the detector saw no face. The ratio is meaningless here and is stored as
         * `0f` so that a caller can never accidentally read it as a measurement — [usableRatio] is
         * `null` regardless of the stored value.
         */
        fun noFace(timestampMs: Long): EyeSafetyFrame =
            EyeSafetyFrame(timestampMs = timestampMs, facePresent = false, faceWidthRatio = 0f)
    }
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/**
 * Thresholds and temporal parameters for [TemporalEyeSafetyDetector].
 *
 * Everything is expressed as a **normalized** ratio (`0..1`), never in centimetres, and every
 * boundary is explicit: there is no silent clamping or correction, so an inconsistent
 * configuration fails loudly at construction instead of quietly enforcing something the parent
 * did not ask for.
 *
 * Entry and exit thresholds are separate on purpose. Leaving a state uses that state's *exit*
 * threshold, entering a stricter one uses its *enter* threshold, and a ratio landing between the
 * two (the dead band) leaves the current state alone — which is what stops a jittering bounding
 * box from toggling the restriction on and off. Equality is permitted
 * (`warningExitThreshold == warningEnterThreshold`), which simply means no hysteresis at that
 * boundary; [DEFAULT] provides strictly separated thresholds.
 *
 * The constructor deliberately has **no defaults** for the thresholds: a caller must state them,
 * so no arbitrary production threshold can slip in unnoticed. [DEFAULT] is the single documented
 * place they are chosen.
 *
 * Note that [confirmFrames] describes *observed frames*, and a frame count is not a duration:
 * frames only arrive while the existing scan window is open, so a configuration in which
 * [confirmFrames] exceeds [maxWindowFrames] can never confirm a transition. That is permitted
 * here (no silent correction) but it is almost certainly a mistake.
 */
data class EyeSafetyConfig(
    val enabled: Boolean,
    /** Ratio at or above which the state becomes [EyeSafetyState.WARNING]. */
    val warningEnterThreshold: Float,
    /** Ratio at or below which an existing [EyeSafetyState.WARNING] is left. */
    val warningExitThreshold: Float,
    /** Ratio at or above which the state becomes [EyeSafetyState.DANGER]. */
    val dangerEnterThreshold: Float,
    /** Ratio at or below which an existing [EyeSafetyState.DANGER] is left. */
    val dangerExitThreshold: Float,
    /**
     * Consecutive usable observations that must agree before the state is allowed to change.
     *
     * Counted in **observed frames**, never in wall-clock time: frames arrive only while the
     * existing scan window is open, so a frame count cannot be translated into seconds.
     */
    val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    val maxWindowAgeMs: Long = DEFAULT_MAX_WINDOW_AGE_MS,
    val maxWindowFrames: Int = DEFAULT_MAX_WINDOW_FRAMES,
    /** Share of the window that must contain a face for any state other than UNKNOWN. */
    val minimumPresenceRatio: Float = DEFAULT_MINIMUM_PRESENCE_RATIO,
) {

    init {
        requireIsRatio("warningEnterThreshold", warningEnterThreshold, allowZero = false, allowOne = false)
        requireIsRatio("warningExitThreshold", warningExitThreshold, allowZero = true, allowOne = true)
        requireIsRatio("dangerEnterThreshold", dangerEnterThreshold, allowZero = false, allowOne = false)
        requireIsRatio("dangerExitThreshold", dangerExitThreshold, allowZero = true, allowOne = true)

        require(warningEnterThreshold < dangerEnterThreshold) {
            "warningEnterThreshold ($warningEnterThreshold) must be below dangerEnterThreshold " +
                "($dangerEnterThreshold)"
        }
        require(warningExitThreshold <= warningEnterThreshold) {
            "warningExitThreshold ($warningExitThreshold) must not exceed warningEnterThreshold " +
                "($warningEnterThreshold)"
        }
        require(dangerExitThreshold <= dangerEnterThreshold) {
            "dangerExitThreshold ($dangerExitThreshold) must not exceed dangerEnterThreshold " +
                "($dangerEnterThreshold)"
        }
        require(dangerExitThreshold >= warningExitThreshold) {
            "dangerExitThreshold ($dangerExitThreshold) must not be below warningExitThreshold " +
                "($warningExitThreshold)"
        }
        require(confirmFrames > 0) { "confirmFrames must be positive, was $confirmFrames" }
        require(maxWindowAgeMs > 0L) { "maxWindowAgeMs must be positive, was $maxWindowAgeMs" }
        require(maxWindowFrames > 0) { "maxWindowFrames must be positive, was $maxWindowFrames" }
        require(minimumPresenceRatio.isFinite() && minimumPresenceRatio > 0f && minimumPresenceRatio <= 1f) {
            "minimumPresenceRatio must be in (0, 1], was $minimumPresenceRatio"
        }
    }

    companion object {
        /**
         * The documented starting point, chosen from the physics of a front camera rather than
         * from a device measurement: a roughly 11 cm wide child's face fills about 22% of the frame
         * at 40 cm and about 34% at 25 cm for a typical ~65° front-camera field of view.
         *
         * These are **heuristic and device dependent** — field of view varies between devices and
         * a turned head foreshortens the bounding box — so they are a default to be calibrated,
         * not a claimed distance.
         */
        val DEFAULT = EyeSafetyConfig(
            enabled = true,
            warningEnterThreshold = DEFAULT_WARNING_ENTER_THRESHOLD,
            warningExitThreshold = DEFAULT_WARNING_EXIT_THRESHOLD,
            dangerEnterThreshold = DEFAULT_DANGER_ENTER_THRESHOLD,
            dangerExitThreshold = DEFAULT_DANGER_EXIT_THRESHOLD,
        )

        const val DEFAULT_WARNING_ENTER_THRESHOLD: Float = 0.30f
        const val DEFAULT_WARNING_EXIT_THRESHOLD: Float = 0.27f
        const val DEFAULT_DANGER_ENTER_THRESHOLD: Float = 0.40f
        const val DEFAULT_DANGER_EXIT_THRESHOLD: Float = 0.35f

        /** Mirrors the liveness window's sizing so the two temporal signals age alike. */
        const val DEFAULT_CONFIRM_FRAMES: Int = 3
        const val DEFAULT_MAX_WINDOW_AGE_MS: Long = 2_500L
        const val DEFAULT_MAX_WINDOW_FRAMES: Int = 24
        const val DEFAULT_MINIMUM_PRESENCE_RATIO: Float = 0.5f
    }
}

private fun requireIsRatio(name: String, value: Float, allowZero: Boolean, allowOne: Boolean) {
    require(value.isFinite()) { "$name must be finite, was $value" }
    val lowOk = if (allowZero) value >= 0f else value > 0f
    val highOk = if (allowOne) value <= 1f else value < 1f
    require(lowOk && highOk) { "$name is outside its permitted range, was $value" }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

/**
 * The outcome of one evaluation.
 *
 * [ratio] is the measurement the state was decided from, or `null` when there was none — the
 * result never invents a number. There is deliberately no confidence score: the ratio is not a
 * calibrated probability, and reporting one would be a claim this build cannot support.
 */
data class EyeSafetyResult(
    val state: EyeSafetyState,
    val ratio: Float?,
    /** How many frames the decision was made from (0 when the window was empty). */
    val observedFrames: Int,
    /** The newest observation's timestamp, or 0 when nothing was observed. */
    val timestampMs: Long,
) {
    companion object {
        /** Nothing was observed, so nothing is claimed. */
        val UNKNOWN = EyeSafetyResult(
            state = EyeSafetyState.UNKNOWN,
            ratio = null,
            observedFrames = 0,
            timestampMs = 0L,
        )
    }
}
