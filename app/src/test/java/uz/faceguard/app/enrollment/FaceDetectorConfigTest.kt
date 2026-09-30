package uz.faceguard.app.enrollment

import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.pipeline.FaceDetectorConfig
import uz.faceguard.app.core.pipeline.FaceDetectorFeature
import uz.faceguard.app.core.pipeline.FaceDetectorPerformance
import uz.faceguard.app.core.pipeline.toClassificationMode
import uz.faceguard.app.core.pipeline.toContourMode
import uz.faceguard.app.core.pipeline.toLandmarkMode
import uz.faceguard.app.core.pipeline.toPerformanceMode

/**
 * Regression guard for the detector configuration the enrollment quality gate
 * depends on.
 *
 * The gate measures occlusion by counting ML Kit landmarks. ML Kit defaults to
 * `LANDMARK_MODE_NONE`, under which every `Face.getLandmark(...)` returns null, so
 * a detector created without an explicit landmark mode makes `landmarkVisibility`
 * permanently 0.0 and rejects every frame as `OCCLUDED` — enrollment could never
 * complete. These tests fail if that mode is ever silently dropped.
 *
 * `FaceDetectorOptions` has no public getters, so the requested ML Kit mode
 * constants are asserted through the configuration that feeds the builder.
 */
class FaceDetectorConfigTest {

    private val config = FaceDetectorConfig.DEFAULT

    // --------------------------------------------- requested configuration ----

    @Test
    fun landmarksAreRequested() {
        assertEquals(
            "landmarks must be requested or the occlusion gate can never pass",
            FaceDetectorFeature.ALL,
            config.landmarks,
        )
        assertEquals(
            "the landmark mode handed to ML Kit must be LANDMARK_MODE_ALL",
            FaceDetectorOptions.LANDMARK_MODE_ALL,
            config.landmarks.toLandmarkMode(),
        )
        assertTrue(
            "a revert to the ML Kit default would break enrollment",
            config.landmarks.toLandmarkMode() != FaceDetectorOptions.LANDMARK_MODE_NONE,
        )
    }

    @Test
    fun onlyTheRequiredFeaturesAreEnabled() {
        assertEquals(FaceDetectorPerformance.FAST, config.performance)
        assertEquals(FaceDetectorOptions.PERFORMANCE_MODE_FAST, config.performance.toPerformanceMode())
        assertEquals(FaceDetectorFeature.NONE, config.classification)
        assertEquals(FaceDetectorOptions.CLASSIFICATION_MODE_NONE, config.classification.toClassificationMode())
        assertEquals(FaceDetectorFeature.NONE, config.contours)
        assertEquals(FaceDetectorOptions.CONTOUR_MODE_NONE, config.contours.toContourMode())
        assertFalse("tracking is not used by the pipeline", config.trackingEnabled)
    }

    @Test
    fun theFeatureMappingIsExhaustiveAndCorrect() {
        assertEquals(
            FaceDetectorOptions.LANDMARK_MODE_NONE,
            FaceDetectorFeature.NONE.toLandmarkMode(),
        )
        assertEquals(
            FaceDetectorOptions.LANDMARK_MODE_ALL,
            FaceDetectorFeature.ALL.toLandmarkMode(),
        )
        assertEquals(
            FaceDetectorOptions.CONTOUR_MODE_ALL,
            FaceDetectorFeature.ALL.toContourMode(),
        )
        assertEquals(
            FaceDetectorOptions.CLASSIFICATION_MODE_ALL,
            FaceDetectorFeature.ALL.toClassificationMode(),
        )
        assertEquals(
            FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE,
            FaceDetectorPerformance.ACCURATE.toPerformanceMode(),
        )
    }

    // ------------------------------------------------------- wiring guard -----

    @Test
    fun theCameraPipelineBuildsItsDetectorFromThisConfiguration() {
        val source = detectorWiringSource()

        assertTrue(
            "the detector must be created from the shared configuration",
            source.contains("FaceDetectorConfig.DEFAULT.toMlKitOptions()"),
        )
        assertFalse(
            "the detector must not build FaceDetectorOptions inline again",
            source.contains("FaceDetectorOptions.Builder()"),
        )
    }

    @Test
    fun theBuilderActuallyAppliesTheLandmarkMode() {
        val source = File(
            repoRoot(),
            "app/src/main/java/uz/faceguard/app/core/pipeline/FaceDetectorConfig.kt",
        ).readText()

        assertTrue(
            "the ML Kit builder must receive the landmark mode",
            source.contains("setLandmarkMode(landmarks.toLandmarkMode())"),
        )
    }

    private fun detectorWiringSource(): String = File(
        repoRoot(),
        "app/src/main/java/uz/faceguard/app/core/pipeline/FaceCaptureController.kt",
    ).readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
