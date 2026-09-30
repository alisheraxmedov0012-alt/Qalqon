package uz.faceguard.app.core.pipeline

import com.google.mlkit.vision.face.FaceDetectorOptions

/** How aggressively ML Kit should run. */
enum class FaceDetectorPerformance { FAST, ACCURATE }

/** Whether an optional ML Kit feature (landmarks / contours / classification) is requested. */
enum class FaceDetectorFeature { NONE, ALL }

/**
 * The ML Kit face-detector configuration QALQON asks for.
 *
 * Modelled as plain values rather than built inline, and exposed through the
 * ML Kit mode constants it will request, so the whole configuration is
 * verifiable in a JVM unit test. `FaceDetectorOptions` itself exposes no public
 * getters, so the requested modes cannot be read back from a built options
 * object — asserting them here is the only way to pin them without a device.
 *
 * Landmarks must be requested explicitly: ML Kit defaults to
 * `LANDMARK_MODE_NONE`, and under that default `Face.getLandmark(...)` returns
 * null for every point. Because the enrollment occlusion proxy counts landmark
 * presence, the NONE default made every frame read as "occluded" and enrollment
 * could never complete.
 */
data class FaceDetectorConfig(
    val performance: FaceDetectorPerformance = FaceDetectorPerformance.FAST,
    /** Required: the enrollment quality gate measures occlusion by landmark presence. */
    val landmarks: FaceDetectorFeature = FaceDetectorFeature.ALL,
    val classification: FaceDetectorFeature = FaceDetectorFeature.NONE,
    val contours: FaceDetectorFeature = FaceDetectorFeature.NONE,
    val trackingEnabled: Boolean = false,
) {
    companion object {
        /** The single configuration the camera pipeline uses. */
        val DEFAULT = FaceDetectorConfig()
    }
}

/** The ML Kit `landmarkMode` this feature requests. */
fun FaceDetectorFeature.toLandmarkMode(): Int = when (this) {
    FaceDetectorFeature.NONE -> FaceDetectorOptions.LANDMARK_MODE_NONE
    FaceDetectorFeature.ALL -> FaceDetectorOptions.LANDMARK_MODE_ALL
}

/** The ML Kit `contourMode` this feature requests. */
fun FaceDetectorFeature.toContourMode(): Int = when (this) {
    FaceDetectorFeature.NONE -> FaceDetectorOptions.CONTOUR_MODE_NONE
    FaceDetectorFeature.ALL -> FaceDetectorOptions.CONTOUR_MODE_ALL
}

/** The ML Kit `classificationMode` this feature requests. */
fun FaceDetectorFeature.toClassificationMode(): Int = when (this) {
    FaceDetectorFeature.NONE -> FaceDetectorOptions.CLASSIFICATION_MODE_NONE
    FaceDetectorFeature.ALL -> FaceDetectorOptions.CLASSIFICATION_MODE_ALL
}

/** The ML Kit `performanceMode` this setting requests. */
fun FaceDetectorPerformance.toPerformanceMode(): Int = when (this) {
    FaceDetectorPerformance.FAST -> FaceDetectorOptions.PERFORMANCE_MODE_FAST
    FaceDetectorPerformance.ACCURATE -> FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
}

/** Maps the requested configuration onto ML Kit's official builder options. */
fun FaceDetectorConfig.toMlKitOptions(): FaceDetectorOptions {
    val builder = FaceDetectorOptions.Builder()
        .setPerformanceMode(performance.toPerformanceMode())
        .setLandmarkMode(landmarks.toLandmarkMode())
        .setClassificationMode(classification.toClassificationMode())
        .setContourMode(contours.toContourMode())
    // Tracking is opt-in; ML Kit's builder only exposes an enable switch for it.
    if (trackingEnabled) builder.enableTracking()
    return builder.build()
}
