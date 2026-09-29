package uz.faceguard.app.domain.enrollment

/**
 * Phase 12 (enrollment): the pure, Android-free enrollment quality domain.
 *
 * Everything here is a plain value so the entire quality gate, temporal
 * stability window, embedding validation and consistency logic can be verified
 * on the JVM with deterministic inputs — no camera, no ML Kit, no bitmap.
 *
 * The domain deliberately makes no liveness/anti-spoof claim: these checks are
 * face *quality* (count, size, position, pose, light, focus, occlusion) and
 * sample *consistency*, not proof that a real person is present (that is the
 * blocked anti-spoofing phase).
 */

/**
 * One analyzed frame, reduced to the metrics the gate needs.
 *
 * All ratios are `0..1`; angles are degrees as ML Kit reports them. [embedding]
 * is the per-frame feature vector (MobileFaceNet embedding, or the geometry
 * fallback) and may be null while no embedding could be produced.
 */
data class EnrollmentFrame(
    val timestampMs: Long,
    val faceCount: Int,
    val faceWidthRatio: Float,
    val centerXRatio: Float,
    val centerYRatio: Float,
    val pitchDegrees: Float,
    val yawDegrees: Float,
    val rollDegrees: Float,
    val brightness: Float,
    val sharpness: Float,
    val landmarkVisibility: Float,
    val embedding: FloatArray?,
) {
    // A data class would otherwise warn about the array property. Frames are
    // compared by identity (each is a distinct observation), which is also what
    // makes them safe keys in an identity map.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Why a frame may not be used as an enrollment sample. */
enum class EnrollmentRejection {
    NO_FACE,
    MULTIPLE_FACES,
    TOO_SMALL,
    TOO_CLOSE,
    OFF_CENTER,
    NOT_FRONTAL,
    TOO_DARK,
    TOO_BRIGHT,
    BLURRY,
    OCCLUDED,
    INVALID_EMBEDDING,

    /**
     * Not a single-frame failure: the collected samples did not agree with each
     * other, so an outlier frame was dropped and the user is asked to hold the
     * face straight and steady rather than restarting the whole attempt.
     */
    INCONSISTENT_SAMPLES,
}

/**
 * Coarse enrollment stage, derived from the latest frame and the collected
 * window. The UI maps each stage (and its [EnrollmentRejection]) to localized
 * copy; no user-visible text lives here.
 */
enum class EnrollmentStage {
    /** No face visible. */
    SEARCHING,

    /** A face is visible but its size/position is not usable yet. */
    POSITIONING,

    /** The face is positioned but the head is not frontal enough. */
    FRONTAL_REQUIRED,

    /** Position and pose are fine; light/focus/occlusion/embedding is not. */
    QUALITY_CHECK,

    /** Good frames are arriving and being accumulated. */
    STABILIZING,

    /** Enough frames exist but they are not mutually consistent yet. */
    VALIDATING,

    /** Enrollment is complete and the template has been produced. */
    SUCCESS,

    /** The enrollment attempt failed. */
    FAILED,
}

/** Snapshot of the collector after a frame was offered. */
data class EnrollmentProgress(
    val stage: EnrollmentStage,
    val rejection: EnrollmentRejection?,
    val acceptedFrames: Int,
    val requiredFrames: Int,
    val stableMs: Long,
    val complete: Boolean,
) {
    val progressFraction: Float
        get() = if (requiredFrames <= 0) 0f
        else (acceptedFrames.toFloat() / requiredFrames.toFloat()).coerceIn(0f, 1f)
}

/** Outcome of an enrollment attempt: the selected samples, or nothing yet. */
sealed class EnrollmentOutcome {
    /** Consistent, quality-checked samples ready to be aggregated into a template. */
    data class Complete(val frames: List<EnrollmentFrame>) : EnrollmentOutcome()

    object Incomplete : EnrollmentOutcome()
}

/**
 * Thresholds for the frontal enrollment gate and the temporal window.
 *
 * Every value is `require`-validated: a nonsensical configuration fails loudly
 * at construction instead of silently making enrollment impossible. The pose
 * limits are deliberately generous (a *natural* frontal face, not an exact 0°),
 * and are meant to be tuned against the bundled ML Kit detector on real devices.
 */
data class EnrollmentQualityConfig(
    /** Face box width relative to the frame; below this the face is too far. */
    val minFaceWidthRatio: Float = 0.28f,
    /** Above this the face is too close (and the crop would clip). */
    val maxFaceWidthRatio: Float = 0.85f,
    /** Max |centre - 0.5| on either axis before the face counts as off-centre. */
    val maxCenterOffsetRatio: Float = 0.28f,
    val maxYawDegrees: Float = 18f,
    val maxPitchDegrees: Float = 18f,
    val maxRollDegrees: Float = 15f,
    val minBrightness: Float = 0.20f,
    val maxBrightness: Float = 0.92f,
    val minSharpness: Float = 0.03f,
    val minLandmarkVisibility: Float = 0.6f,
    /** Accepted frames required before a template may be produced. */
    val framesRequired: Int = 5,
    /** Best frames kept for the final template; never more than [framesRequired]. */
    val framesToKeep: Int = 5,
    /** Minimum stable span across the accepted frames. */
    val minSpanMs: Long = 1_200L,
    /** Rolling window: accepted frames older than this are dropped. */
    val windowMs: Long = 4_000L,
    /** Mean pairwise cosine an accepted frame must reach to stay in the set. */
    val consistencyThreshold: Float = 0.45f,
    /** Expected embedding dimension, or 0 to accept any positive dimension. */
    val expectedEmbeddingDimension: Int = 0,
    /** Whether embeddings must be (near) unit-norm, as MobileFaceNet's are. */
    val requireUnitNorm: Boolean = false,
    val unitNormTolerance: Float = 0.08f,
) {
    init {
        require(minFaceWidthRatio > 0f) { "minFaceWidthRatio must be > 0" }
        require(maxFaceWidthRatio >= minFaceWidthRatio) { "maxFaceWidthRatio must be >= min" }
        require(maxCenterOffsetRatio >= 0f) { "maxCenterOffsetRatio must be >= 0" }
        require(maxYawDegrees > 0f && maxPitchDegrees > 0f && maxRollDegrees > 0f) {
            "pose limits must be > 0"
        }
        require(minBrightness in 0f..1f && maxBrightness in 0f..1f) { "brightness in 0..1" }
        require(maxBrightness > minBrightness) { "maxBrightness must be > minBrightness" }
        require(minSharpness >= 0f) { "minSharpness must be >= 0" }
        require(minLandmarkVisibility in 0f..1f) { "minLandmarkVisibility in 0..1" }
        require(framesRequired >= 1) { "framesRequired must be >= 1" }
        require(framesToKeep in 1..framesRequired) { "framesToKeep must be in 1..framesRequired" }
        require(minSpanMs >= 0L) { "minSpanMs must be >= 0" }
        require(windowMs >= minSpanMs) { "windowMs must be >= minSpanMs" }
        require(consistencyThreshold in -1f..1f) { "consistencyThreshold in -1..1" }
        require(expectedEmbeddingDimension >= 0) { "expectedEmbeddingDimension must be >= 0" }
        require(unitNormTolerance > 0f) { "unitNormTolerance must be > 0" }
    }
}
