package uz.faceguard.app.domain.enrollment

import kotlin.math.sqrt

/** Why an embedding cannot be trusted as an enrollment sample. */
enum class EmbeddingRejection {
    EMPTY,
    WRONG_DIMENSION,
    NON_FINITE,
    ZERO_NORM,
    NOT_NORMALIZED,
}

/**
 * Validates a single per-frame embedding before it may enter the enrollment set.
 *
 * "ML Kit detected a face" is not enough: a frame is only usable when its vector
 * is non-empty, the expected size, entirely finite, non-degenerate and — for
 * MobileFaceNet, which L2-normalizes its output — actually unit-norm. A failing
 * vector rejects its frame; it never becomes part of the template.
 *
 * The geometry fallback extractor is *not* unit-norm, so [requireUnitNorm] is a
 * per-call setting the caller derives from which extractor produced the vector.
 */
class FaceEmbeddingValidator(
    private val expectedDimension: Int = UNSPECIFIED_DIMENSION,
    private val requireUnitNorm: Boolean = false,
    private val unitNormTolerance: Float = 0.08f,
) {
    init {
        require(expectedDimension >= 0) { "expectedDimension must be >= 0" }
        require(unitNormTolerance > 0f) { "unitNormTolerance must be > 0" }
    }

    /** Returns null when [values] is usable, otherwise the reason it is not. */
    fun validate(values: FloatArray?): EmbeddingRejection? {
        if (values == null || values.isEmpty()) return EmbeddingRejection.EMPTY
        if (expectedDimension != UNSPECIFIED_DIMENSION && values.size != expectedDimension) {
            return EmbeddingRejection.WRONG_DIMENSION
        }

        var sumSquares = 0.0
        for (value in values) {
            if (!value.isFinite()) return EmbeddingRejection.NON_FINITE
            sumSquares += value.toDouble() * value
        }

        val norm = sqrt(sumSquares)
        if (norm <= MIN_NORM) return EmbeddingRejection.ZERO_NORM

        if (requireUnitNorm && kotlin.math.abs(norm - 1.0) > unitNormTolerance) {
            return EmbeddingRejection.NOT_NORMALIZED
        }
        return null
    }

    companion object {
        /** Accept any positive dimension (used for the variable-size geometry fallback). */
        const val UNSPECIFIED_DIMENSION = 0

        /** Below this the vector carries no usable direction. */
        const val MIN_NORM = 1e-6
    }
}
