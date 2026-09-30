package uz.faceguard.app.domain.enrollment

import kotlin.math.abs
import kotlin.math.max

/** Verdict for one frame: usable (with a quality score) or rejected with a reason. */
sealed class EnrollmentVerdict {
    data class Accepted(val score: Float) : EnrollmentVerdict()
    data class Rejected(val reason: EnrollmentRejection) : EnrollmentVerdict()
}

/**
 * The per-frame enrollment quality gate.
 *
 * One frame at a time, in a fixed order, so the first failing condition is the
 * guidance the user sees:
 *
 * face count -> size -> position -> frontal pose -> brightness -> sharpness ->
 * occlusion -> embedding validity.
 *
 * It is pure and stateless: the same frame always yields the same verdict, which
 * is what makes every branch deterministically testable. Non-finite metrics are
 * treated as a failure of the check they belong to (an unusable measurement is
 * never silently accepted).
 */
class EnrollmentQualityGate(
    private val config: EnrollmentQualityConfig = EnrollmentQualityConfig(),
    private val validator: FaceEmbeddingValidator = FaceEmbeddingValidator(
        expectedDimension = config.expectedEmbeddingDimension,
        requireUnitNorm = config.requireUnitNorm,
        unitNormTolerance = config.unitNormTolerance,
    ),
) {

    fun evaluate(frame: EnrollmentFrame): EnrollmentVerdict {
        // STEP 1 — exactly one face; a second person must never leak into the template.
        if (frame.faceCount <= 0) return EnrollmentVerdict.Rejected(EnrollmentRejection.NO_FACE)
        if (frame.faceCount > 1) return EnrollmentVerdict.Rejected(EnrollmentRejection.MULTIPLE_FACES)

        // STEP 2 — size and position.
        if (!frame.faceWidthRatio.isFinite() || frame.faceWidthRatio < config.minFaceWidthRatio) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.TOO_SMALL)
        }
        if (frame.faceWidthRatio > config.maxFaceWidthRatio) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.TOO_CLOSE)
        }
        val offsetX = abs(frame.centerXRatio - CENTER)
        val offsetY = abs(frame.centerYRatio - CENTER)
        if (!offsetX.isFinite() || !offsetY.isFinite() ||
            max(offsetX, offsetY) > config.maxCenterOffsetRatio
        ) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.OFF_CENTER)
        }

        // STEP 3 — natural frontal pose (yaw/pitch/roll), not an exact 0 degrees.
        if (!frame.yawDegrees.isFinite() || abs(frame.yawDegrees) > config.maxYawDegrees ||
            !frame.pitchDegrees.isFinite() || abs(frame.pitchDegrees) > config.maxPitchDegrees ||
            !frame.rollDegrees.isFinite() || abs(frame.rollDegrees) > config.maxRollDegrees
        ) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.NOT_FRONTAL)
        }

        // STEP 4 — light, focus, occlusion, then embedding validity.
        if (!frame.brightness.isFinite() || frame.brightness < config.minBrightness) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.TOO_DARK)
        }
        if (frame.brightness > config.maxBrightness) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.TOO_BRIGHT)
        }
        if (!frame.sharpness.isFinite() || frame.sharpness < config.minSharpness) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.BLURRY)
        }
        if (!frame.landmarkVisibility.isFinite() ||
            frame.landmarkVisibility < config.minLandmarkVisibility
        ) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.OCCLUDED)
        }
        if (validator.validate(frame.embedding) != null) {
            return EnrollmentVerdict.Rejected(EnrollmentRejection.INVALID_EMBEDDING)
        }

        return EnrollmentVerdict.Accepted(score(frame))
    }

    /**
     * Best-frame ranking (0..1): pose first, then size, sharpness, lighting and
     * finally occlusion. Only ever called on a frame that already passed the gate,
     * so the weights order *good* frames rather than deciding acceptance.
     */
    fun score(frame: EnrollmentFrame): Float {
        val pose = 1f - max(
            max(abs(frame.yawDegrees) / config.maxYawDegrees, abs(frame.pitchDegrees) / config.maxPitchDegrees),
            abs(frame.rollDegrees) / config.maxRollDegrees,
        )
        val size = ((frame.faceWidthRatio - config.minFaceWidthRatio) /
            (config.maxFaceWidthRatio - config.minFaceWidthRatio))
        val sharpness = frame.sharpness / GOOD_SHARPNESS
        // Prefer mid lighting; both extremes are penalised.
        val light = 1f - abs(frame.brightness - CENTER) / CENTER
        val occlusion = frame.landmarkVisibility

        return (W_POSE * pose.coerceIn(0f, 1f) +
            W_SIZE * size.coerceIn(0f, 1f) +
            W_SHARPNESS * sharpness.coerceIn(0f, 1f) +
            W_LIGHT * light.coerceIn(0f, 1f) +
            W_OCCLUSION * occlusion.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
    }

    private companion object {
        const val CENTER = 0.5f

        /** Sharpness at which the focus component of the score saturates. */
        const val GOOD_SHARPNESS = 0.12f

        const val W_POSE = 0.30f
        const val W_SIZE = 0.20f
        const val W_SHARPNESS = 0.20f
        const val W_LIGHT = 0.15f
        const val W_OCCLUSION = 0.15f
    }
}
