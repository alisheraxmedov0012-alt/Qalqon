package uz.faceguard.app.enrollment

import com.google.mlkit.vision.face.FaceLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.embed.FaceFeatureExtractor
import uz.faceguard.app.domain.enrollment.EnrollmentQualityConfig
import uz.faceguard.app.domain.enrollment.EnrollmentQualityGate
import uz.faceguard.app.domain.enrollment.EnrollmentRejection
import uz.faceguard.app.domain.enrollment.EnrollmentVerdict
import uz.faceguard.app.enrollment.EnrollmentFixtures.frame

/**
 * Regression coverage for the landmark-based occlusion proxy and how the
 * enrollment gate consumes it.
 *
 * The defect this guards against was a *wiring* bug (landmarks never requested),
 * so the pure fraction values are pinned here and the gate's boundary behaviour is
 * pinned there: 0.0 must still reject as `OCCLUDED` (a genuinely unmeasurable face
 * is not accepted), while the documented threshold 0.6 must not fail on occlusion.
 */
class EnrollmentLandmarkRegressionTest {

    private val gate = EnrollmentQualityGate(EnrollmentQualityConfig())

    private fun rejectionOf(frame: uz.faceguard.app.domain.enrollment.EnrollmentFrame) =
        (gate.evaluate(frame) as? EnrollmentVerdict.Rejected)?.reason

    // ------------------------------------------- landmark list + arithmetic ---

    @Test
    fun theProxyCountsExactlyTheFiveCoreLandmarks() {
        val types = FaceFeatureExtractor.VISIBILITY_LANDMARK_TYPES

        assertEquals("the occlusion proxy is defined over five landmarks", 5, types.size)
        assertEquals(
            listOf(
                FaceLandmark.LEFT_EYE,
                FaceLandmark.RIGHT_EYE,
                FaceLandmark.NOSE_BASE,
                FaceLandmark.MOUTH_LEFT,
                FaceLandmark.MOUTH_RIGHT,
            ),
            types.toList(),
        )
    }

    @Test
    fun theFractionIsPresenceOverFive() {
        assertEquals(1.0f, FaceFeatureExtractor.visibilityOf(5), 0f)
        assertEquals(0.8f, FaceFeatureExtractor.visibilityOf(4), 0f)
        assertEquals(0.6f, FaceFeatureExtractor.visibilityOf(3), 0f)
        assertEquals(0.4f, FaceFeatureExtractor.visibilityOf(2), 0f)
        assertEquals(0.0f, FaceFeatureExtractor.visibilityOf(0), 0f)
    }

    @Test
    fun noLandmarksDetectedIsZeroWhichIsWhyTheDetectorMustRequestThem() {
        // With ML Kit's default LANDMARK_MODE_NONE every landmark is absent, so the
        // proxy reads exactly this value — the state that previously broke enrollment.
        assertEquals(0.0f, FaceFeatureExtractor.visibilityOf(0), 0f)
    }

    // --------------------------------------------- enrollment gate boundary ---

    @Test
    fun zeroVisibilityIsRejectedAsOccluded() {
        assertEquals(
            EnrollmentRejection.OCCLUDED,
            rejectionOf(frame(landmarkVisibility = 0.0f)),
        )
    }

    @Test
    fun theDocumentedThresholdDoesNotFailOnOcclusion() {
        // 0.6 == minLandmarkVisibility: the boundary is inclusive.
        assertNotEquals(
            EnrollmentRejection.OCCLUDED,
            rejectionOf(frame(landmarkVisibility = 0.6f)),
        )
        assertTrue(gate.evaluate(frame(landmarkVisibility = 0.6f)) is EnrollmentVerdict.Accepted)
    }

    @Test
    fun theThresholdItselfWasNotWeakened() {
        assertEquals(0.6f, EnrollmentQualityConfig().minLandmarkVisibility, 0f)
    }

    @Test
    fun aFullyVisibleFacePassesEveryGate() {
        // Regression for the reported screenshot: a normal frontal face must be accepted.
        assertTrue(gate.evaluate(frame(landmarkVisibility = 1f)) is EnrollmentVerdict.Accepted)
    }
}
