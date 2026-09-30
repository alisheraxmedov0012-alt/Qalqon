package uz.faceguard.app.core.embed

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Turns a detected ML Kit [Face] into a compact, deterministic geometry vector
 * that enrollment can persist and recognition can compare. This is a practical
 * on-device MVP feature — real landmarks/pose, not an identity-grade embedding.
 */
object FaceFeatureExtractor {

    /** 3 pose + 2 size + 7 landmarks x 2 coordinates. */
    const val DIM = 19

    private val LANDMARK_TYPES = intArrayOf(
        FaceLandmark.LEFT_EYE,
        FaceLandmark.RIGHT_EYE,
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
        FaceLandmark.LEFT_CHEEK,
        FaceLandmark.RIGHT_CHEEK,
    )

    /**
     * The landmarks used as the enrollment occlusion proxy.
     *
     * Deliberately the five *core* points (eyes, nose, mouth) rather than the
     * cheeks: ML Kit's fast detector returns cheeks least reliably, so counting
     * them would reject unobstructed faces. These five survive glasses and
     * natural hair, and drop when a hand or a mask covers a large part of the
     * face.
     */
    internal val VISIBILITY_LANDMARK_TYPES = intArrayOf(
        FaceLandmark.LEFT_EYE,
        FaceLandmark.RIGHT_EYE,
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
    )

    /**
     * Fraction (0..1) of the core landmarks present on [face].
     *
     * Requires landmark detection to be enabled on the detector; with ML Kit's
     * default `LANDMARK_MODE_NONE` every lookup returns null and this is 0.0.
     */
    fun landmarkVisibility(face: Face): Float =
        visibilityOf(VISIBILITY_LANDMARK_TYPES.count { face.getLandmark(it) != null })

    /** Fraction of [VISIBILITY_LANDMARK_TYPES] present: 5/5 -> 1.0, 0/5 -> 0.0. */
    internal fun visibilityOf(presentCount: Int): Float =
        presentCount.toFloat() / VISIBILITY_LANDMARK_TYPES.size

    fun extract(face: Face, imageWidth: Int, imageHeight: Int): FloatArray? {
        val box = face.boundingBox ?: return null
        val boxWidth = box.width()
        val boxHeight = box.height()
        if (boxWidth <= 0 || boxHeight <= 0) return null

        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY()

        val out = FloatArray(DIM)
        // Pose is normalized to roughly [-1, 1] so it stays comparable to the
        // other geometry dimensions when the recognizer computes similarity.
        out[0] = face.headEulerAngleX / 90f
        out[1] = face.headEulerAngleY / 90f
        out[2] = face.headEulerAngleZ / 90f
        out[3] = if (imageWidth > 0) boxWidth.toFloat() / imageWidth else 0f
        out[4] = if (imageHeight > 0) boxHeight.toFloat() / imageHeight else 0f

        var idx = 5
        for (type in LANDMARK_TYPES) {
            val point = face.getLandmark(type)?.position
            if (point != null) {
                out[idx] = (point.x - centerX) / boxWidth.toFloat()
                out[idx + 1] = (point.y - centerY) / boxHeight.toFloat()
            }
            idx += 2
        }
        return out
    }
}
