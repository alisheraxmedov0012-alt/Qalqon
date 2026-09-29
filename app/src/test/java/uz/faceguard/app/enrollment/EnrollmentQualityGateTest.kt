package uz.faceguard.app.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.enrollment.EmbeddingRejection
import uz.faceguard.app.domain.enrollment.EnrollmentFrame
import uz.faceguard.app.domain.enrollment.EnrollmentQualityConfig
import uz.faceguard.app.domain.enrollment.EnrollmentQualityGate
import uz.faceguard.app.domain.enrollment.EnrollmentRejection
import uz.faceguard.app.domain.enrollment.EnrollmentVerdict
import uz.faceguard.app.domain.enrollment.FaceEmbeddingValidator
import uz.faceguard.app.enrollment.EnrollmentFixtures.frame

/**
 * Phase 12 (enrollment): the per-frame quality gate, one test per rejection rule.
 *
 * A frame that passes every rule is the baseline; each test then changes exactly
 * one metric so the resulting reason (and the fact that it is a rejection) is
 * pinned. Deterministic inputs only — no camera.
 */
class EnrollmentQualityGateTest {

    private val gate = EnrollmentQualityGate(EnrollmentQualityConfig())

    private fun rejectionOf(frame: EnrollmentFrame): EnrollmentRejection? =
        (gate.evaluate(frame) as? EnrollmentVerdict.Rejected)?.reason

    private fun assertAccepted(frame: EnrollmentFrame) {
        assertTrue("expected accepted, got ${gate.evaluate(frame)}", gate.evaluate(frame) is EnrollmentVerdict.Accepted)
    }

    private fun assertRejected(reason: EnrollmentRejection, frame: EnrollmentFrame) {
        assertEquals(reason, rejectionOf(frame))
    }

    // 1. no face -> reject
    @Test
    fun noFace_isRejected() = assertRejected(EnrollmentRejection.NO_FACE, frame(faceCount = 0))

    // 2. multiple faces -> reject
    @Test
    fun multipleFaces_isRejected() = assertRejected(EnrollmentRejection.MULTIPLE_FACES, frame(faceCount = 2))

    // 3. face too small -> reject
    @Test
    fun tooSmall_isRejected() = assertRejected(EnrollmentRejection.TOO_SMALL, frame(faceWidthRatio = 0.15f))

    // 4. face too far / off-centre -> reject
    @Test
    fun offCenterHorizontally_isRejected() = assertRejected(EnrollmentRejection.OFF_CENTER, frame(centerXRatio = 0.9f))

    @Test
    fun offCenterVertically_isRejected() = assertRejected(EnrollmentRejection.OFF_CENTER, frame(centerYRatio = 0.05f))

    @Test
    fun tooClose_isRejected() = assertRejected(EnrollmentRejection.TOO_CLOSE, frame(faceWidthRatio = 0.95f))

    // 5. acceptable face size -> pass
    @Test
    fun acceptableSize_isAccepted() = assertAccepted(frame(faceWidthRatio = 0.40f))

    @Test
    fun acceptableSlightOffset_isAccepted() = assertAccepted(frame(centerXRatio = 0.6f))

    // 6. excessive yaw -> reject
    @Test
    fun excessiveYaw_isRejected() = assertRejected(EnrollmentRejection.NOT_FRONTAL, frame(yawDegrees = 25f))

    @Test
    fun excessiveNegativeYaw_isRejected() = assertRejected(EnrollmentRejection.NOT_FRONTAL, frame(yawDegrees = -25f))

    // 7. excessive pitch -> reject
    @Test
    fun excessivePitch_isRejected() = assertRejected(EnrollmentRejection.NOT_FRONTAL, frame(pitchDegrees = 25f))

    // 8. excessive roll -> reject
    @Test
    fun excessiveRoll_isRejected() = assertRejected(EnrollmentRejection.NOT_FRONTAL, frame(rollDegrees = 22f))

    // 9. acceptable frontal pose -> pass
    @Test
    fun naturalFrontalPose_isAccepted() = assertAccepted(
        frame(yawDegrees = 10f, pitchDegrees = -9f, rollDegrees = 7f),
    )

    @Test
    fun poseThresholdBoundaryIsInclusiveOnTheLimit() {
        // At the limit the pose is still acceptable; just past it is not.
        assertAccepted(frame(yawDegrees = 18f))
        assertRejected(EnrollmentRejection.NOT_FRONTAL, frame(yawDegrees = 18.1f))
    }

    // 10. insufficient brightness -> reject
    @Test
    fun tooDark_isRejected() = assertRejected(EnrollmentRejection.TOO_DARK, frame(brightness = 0.05f))

    // 11. excessive brightness -> reject
    @Test
    fun tooBright_isRejected() = assertRejected(EnrollmentRejection.TOO_BRIGHT, frame(brightness = 0.98f))

    // 12. acceptable brightness -> pass
    @Test
    fun acceptableBrightness_isAccepted() = assertAccepted(frame(brightness = 0.5f))

    // 13. blurry frame -> reject
    @Test
    fun blurry_isRejected() = assertRejected(EnrollmentRejection.BLURRY, frame(sharpness = 0.0f))

    @Test
    fun zeroSharpnessFromAMissingMeasurement_isRejected() =
        assertRejected(EnrollmentRejection.BLURRY, frame(sharpness = 0f))

    // 14. sharp frame -> pass
    @Test
    fun sharp_isAccepted() = assertAccepted(frame(sharpness = 0.2f))

    // 15. heavy occlusion -> reject
    @Test
    fun heavyOcclusion_isRejected() =
        assertRejected(EnrollmentRejection.OCCLUDED, frame(landmarkVisibility = 0.2f))

    // 16. acceptable occlusion -> pass (glasses / natural hair must not be rejected)
    @Test
    fun acceptableOcclusion_isAccepted() = assertAccepted(frame(landmarkVisibility = 0.8f))

    // 17-20 via the gate's embedding validation
    @Test
    fun invalidEmbeddingDimension_isRejected() {
        val strict = EnrollmentQualityGate(
            config = EnrollmentQualityConfig(expectedEmbeddingDimension = 192, requireUnitNorm = true),
            validator = FaceEmbeddingValidator(expectedDimension = 192, requireUnitNorm = true),
        )
        val verdict = strict.evaluate(frame(embedding = EnrollmentFixtures.vector(19, seed = 1)))
        assertEquals(EnrollmentVerdict.Rejected(EnrollmentRejection.INVALID_EMBEDDING), verdict)
    }

    @Test
    fun missingEmbedding_isRejected() =
        assertRejected(EnrollmentRejection.INVALID_EMBEDDING, frame(embedding = null))

    @Test
    fun nonFiniteEmbedding_isRejected() {
        val nan = EnrollmentFixtures.unit(1f, 0f).also { it[0] = Float.NaN }
        assertRejected(EnrollmentRejection.INVALID_EMBEDDING, frame(embedding = nan))

        val infinite = EnrollmentFixtures.unit(1f, 0f).also { it[1] = Float.POSITIVE_INFINITY }
        assertRejected(EnrollmentRejection.INVALID_EMBEDDING, frame(embedding = infinite))
    }

    @Test
    fun nonNormalizedEmbedding_isRejectedWhenUnitNormIsRequired() {
        val strict = EnrollmentQualityGate(
            config = EnrollmentQualityConfig(requireUnitNorm = true),
            validator = FaceEmbeddingValidator(requireUnitNorm = true),
        )
        val verdict = strict.evaluate(frame(embedding = floatArrayOf(3f, 4f)))
        assertEquals(EnrollmentVerdict.Rejected(EnrollmentRejection.INVALID_EMBEDDING), verdict)
    }

    // Non-finite metrics are treated as a failure of their own check.
    @Test
    fun nonFiniteMetricsAreRejectedNotAccepted() {
        assertRejected(EnrollmentRejection.TOO_DARK, frame(brightness = Float.NaN))
        assertRejected(EnrollmentRejection.BLURRY, frame(sharpness = Float.NaN))
        assertRejected(EnrollmentRejection.OFF_CENTER, frame(centerXRatio = Float.NaN))
        assertRejected(EnrollmentRejection.NOT_FRONTAL, frame(yawDegrees = Float.NaN))
    }

    // 5/6/7. Best-frame ranking orders good frames; it never decides acceptance.
    @Test
    fun scoreIsHigherForMoresFrontalAndSharperFrames() {
        val good = gate.score(frame(yawDegrees = 0f, sharpness = 0.2f, brightness = 0.5f))
        val lessGood = gate.score(frame(yawDegrees = 17f, sharpness = 0.04f, brightness = 0.9f))
        assertTrue("good=$good lessGood=$lessGood", good > lessGood)
        assertTrue(good in 0f..1f)
        assertTrue(lessGood in 0f..1f)
    }

    // 29. A frame that fails quality can never come back as accepted.
    @Test
    fun failedQualityNeverProducesAnAcceptedVerdict() {
        val bad = listOf(
            frame(faceCount = 0),
            frame(faceCount = 3),
            frame(faceWidthRatio = 0.05f),
            frame(centerXRatio = 0.99f),
            frame(yawDegrees = 40f),
            frame(brightness = 0.01f),
            frame(brightness = 0.99f),
            frame(sharpness = 0f),
            frame(landmarkVisibility = 0f),
            frame(embedding = null),
        )
        bad.forEach { f ->
            assertTrue("$f must not be accepted", gate.evaluate(f) is EnrollmentVerdict.Rejected)
        }
    }

    @Test
    fun validatorReportsTheSpecificReason() {
        val validator = FaceEmbeddingValidator(expectedDimension = 4, requireUnitNorm = true)
        assertEquals(EmbeddingRejection.EMPTY, validator.validate(FloatArray(0)))
        assertEquals(EmbeddingRejection.WRONG_DIMENSION, validator.validate(floatArrayOf(1f, 0f)))
        assertEquals(EmbeddingRejection.ZERO_NORM, validator.validate(floatArrayOf(0f, 0f, 0f, 0f)))
        assertEquals(EmbeddingRejection.NON_FINITE, validator.validate(floatArrayOf(1f, 0f, 0f, Float.NaN)))
        assertEquals(EmbeddingRejection.NOT_NORMALIZED, validator.validate(floatArrayOf(2f, 0f, 0f, 0f)))
        assertEquals(null, validator.validate(floatArrayOf(1f, 0f, 0f, 0f)))
    }
}
