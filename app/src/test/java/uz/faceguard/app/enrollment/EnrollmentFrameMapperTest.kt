package uz.faceguard.app.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.core.enrollment.EnrollmentFrameMapper
import uz.faceguard.app.core.pipeline.FaceQuality
import uz.faceguard.app.domain.enrollment.EmbeddingRejection
import uz.faceguard.app.domain.enrollment.EnrollmentQualityConfig
import uz.faceguard.app.domain.enrollment.EnrollmentQualityGate
import uz.faceguard.app.domain.enrollment.EnrollmentRejection
import uz.faceguard.app.domain.enrollment.EnrollmentVerdict
import uz.faceguard.app.domain.enrollment.FaceEmbeddingValidator

/**
 * Phase 12 (enrollment): the Android -> domain boundary.
 *
 * The mapper is the only place a raw ML Kit measurement can enter the gate, so it
 * is the only place a NaN/Infinity can be neutralised. These tests pin that an
 * unusable measurement can never be turned into a passing one.
 */
class EnrollmentFrameMapperTest {

    private val gate = EnrollmentQualityGate(EnrollmentQualityConfig())

    @Test
    fun qualityFieldsAreCopiedVerbatim() {
        val quality = FaceQuality(
            faceCount = 1,
            headEulerAngleX = 1f,
            headEulerAngleY = 2f,
            headEulerAngleZ = 3f,
            faceWidthRatio = 0.4f,
            brightness = 0.6f,
            faceCenterXRatio = 0.45f,
            faceCenterYRatio = 0.55f,
            sharpness = 0.1f,
            landmarkVisibility = 0.8f,
        )

        val mapped = EnrollmentFrameMapper.map(quality, faceCount = 1, embedding = floatArrayOf(1f, 0f), timestampMs = 9L)

        assertEquals(9L, mapped.timestampMs)
        assertEquals(1, mapped.faceCount)
        assertEquals(0.4f, mapped.faceWidthRatio)
        assertEquals(0.45f, mapped.centerXRatio)
        assertEquals(0.55f, mapped.centerYRatio)
        assertEquals(1f, mapped.pitchDegrees)
        assertEquals(2f, mapped.yawDegrees)
        assertEquals(3f, mapped.rollDegrees)
        assertEquals(0.6f, mapped.brightness)
        assertEquals(0.1f, mapped.sharpness)
        assertEquals(0.8f, mapped.landmarkVisibility)
    }

    @Test
    fun aMissingQualityReadsAsNoFace() {
        val mapped = EnrollmentFrameMapper.map(null, faceCount = 0, embedding = null, timestampMs = 0L)

        assertEquals(0, mapped.faceCount)
        assertNull(mapped.embedding)
        assertEquals(EnrollmentRejection.NO_FACE, (gate.evaluate(mapped) as EnrollmentVerdict.Rejected).reason)
    }

    @Test
    fun nonFiniteMeasurementsAreSanitisedToFailingValues() {
        val poisoned = FaceQuality(
            faceCount = 1,
            headEulerAngleY = Float.NaN,
            faceWidthRatio = Float.NaN,
            brightness = Float.NaN,
            sharpness = Float.POSITIVE_INFINITY,
            landmarkVisibility = Float.NaN,
            faceCenterXRatio = Float.NaN,
            faceCenterYRatio = Float.NaN,
        )

        val mapped = EnrollmentFrameMapper.map(poisoned, faceCount = 1, embedding = floatArrayOf(1f, 0f), timestampMs = 0L)

        // Width is checked before anything else, so an unusable size is what wins.
        assertEquals(
            EnrollmentRejection.TOO_SMALL,
            (gate.evaluate(mapped) as EnrollmentVerdict.Rejected).reason,
        )
    }

    @Test
    fun individuallyPoisonedMeasurementsStillFailTheirOwnCheck() {
        val base = FaceQuality(
            faceCount = 1,
            faceWidthRatio = 0.45f,
            brightness = 0.5f,
            sharpness = 0.12f,
            landmarkVisibility = 1f,
        )

        assertEquals(
            EnrollmentRejection.NOT_FRONTAL,
            rejectionOf(base.copy(headEulerAngleY = Float.NaN)),
        )
        assertEquals(
            EnrollmentRejection.TOO_DARK,
            rejectionOf(base.copy(brightness = Float.NaN)),
        )
        assertEquals(
            EnrollmentRejection.BLURRY,
            rejectionOf(base.copy(sharpness = Float.NaN)),
        )
        assertEquals(
            EnrollmentRejection.OCCLUDED,
            rejectionOf(base.copy(landmarkVisibility = Float.NaN)),
        )
        assertEquals(
            EnrollmentRejection.OFF_CENTER,
            rejectionOf(base.copy(faceCenterXRatio = Float.NaN)),
        )
    }

    @Test
    fun aNonFiniteEmbeddingIsLeftForTheValidatorToReject() {
        val poisoned = floatArrayOf(1f, Float.NaN)
        val mapped = EnrollmentFrameMapper.map(
            FaceQuality(faceCount = 1, faceWidthRatio = 0.45f, brightness = 0.5f, sharpness = 0.12f),
            faceCount = 1,
            embedding = poisoned,
            timestampMs = 0L,
        )

        assertEquals(EmbeddingRejection.NON_FINITE, FaceEmbeddingValidator().validate(mapped.embedding))
        assertEquals(
            EnrollmentRejection.INVALID_EMBEDDING,
            (gate.evaluate(mapped) as EnrollmentVerdict.Rejected).reason,
        )
    }

    private fun rejectionOf(quality: FaceQuality): EnrollmentRejection =
        (gate.evaluate(EnrollmentFrameMapper.map(quality, 1, floatArrayOf(1f, 0f), 0L)) as EnrollmentVerdict.Rejected).reason
}
