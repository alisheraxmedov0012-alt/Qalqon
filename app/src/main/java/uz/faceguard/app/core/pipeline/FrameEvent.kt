package uz.faceguard.app.core.pipeline

import com.google.mlkit.vision.common.InputImage

/**
 * Live per-frame face metrics used to guide enrollment (and to gate it: a
 * template is only stored once the face is present, well-lit, close enough and
 * held straight).
 */
data class FaceQuality(
    val faceCount: Int = 0,
    val headEulerAngleX: Float = 0f,
    val headEulerAngleY: Float = 0f,
    val headEulerAngleZ: Float = 0f,
    /** Face bounding-box width relative to the frame width (0..1). */
    val faceWidthRatio: Float = 0f,
    /** Average luminance of the face region (0..1). */
    val brightness: Float = 0f,
)

/** Frame approved by the detector; representation here is the InputImage seam. */
data class FrameEvent(
    val image: InputImage,
    val faceCount: Int = 1,
    /** On-device geometry vector for the primary detected face, if extractable. */
    val features: FloatArray? = null,
    /** Live face metrics for UI guidance; null when no analysis ran. */
    val quality: FaceQuality? = null,
    /**
     * Group 9: the anti-spoofing model's live probability for this frame, in
     * `[0, 1]`, or null when no model is available (the default in this build).
     * This is the only channel through which a model feeds liveness; the passive
     * heuristic derives its evidence from [quality] instead.
     */
    val liveProbability: Float? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
