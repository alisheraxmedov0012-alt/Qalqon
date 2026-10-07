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
    /** Face bounding-box centre X relative to the frame width (0..1); 0.5 = centred. */
    val faceCenterXRatio: Float = CENTER,
    /** Face bounding-box centre Y relative to the frame height (0..1); 0.5 = centred. */
    val faceCenterYRatio: Float = CENTER,
    /**
     * Mean absolute luminance gradient over the face region (0..1). Higher is
     * sharper; it is a cheap focus proxy, not a full blur metric. Defaults to
     * [SHARPNESS_UNKNOWN] so a caller that does not compute it is never rejected
     * on a signal it did not measure.
     */
    val sharpness: Float = SHARPNESS_UNKNOWN,
    /**
     * Fraction of the core face landmarks (eyes, nose, mouth) ML Kit actually
     * returned for this frame (0..1). Used as an occlusion proxy: a hand or mask
     * that hides a large part of the face drops landmarks. Glasses and natural
     * hair do not. Defaults to [VISIBILITY_UNKNOWN] (fully visible).
     */
    val landmarkVisibility: Float = VISIBILITY_UNKNOWN,
) {
    companion object {
        const val CENTER = 0.5f
        const val SHARPNESS_UNKNOWN = 1f
        const val VISIBILITY_UNKNOWN = 1f
    }
}

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
    /**
     * Where [features] came from.
     *
     * Stage 1: only a real model embedding may authorise a Parent/Child identity.
     * The 19-d geometry fallback is a detection/quality signal, not identity-grade,
     * so [EmbeddingSource.GEOMETRY] frames are never matched for identity. Defaults to
     * [EmbeddingSource.MODEL] so a frame built without an explicit source keeps the
     * pre-Stage-1 recognition behaviour (the tests that construct frames directly).
     */
    val embeddingSource: EmbeddingSource = EmbeddingSource.MODEL,
    /**
     * Stage 2: the feature vector of every detected face in the frame, each tagged with
     * its [EmbeddingSource]. The recogniser evaluates all of them so the identity policy
     * never depends on the order the detector returned faces in.
     *
     * Empty means "no per-face evidence was supplied", in which case the recogniser falls
     * back to the single [features]/[embeddingSource] pair — this keeps single-face frames
     * (and callers that only populate [features]) behaving exactly as before.
     */
    val faces: List<FaceFeature> = emptyList(),
)

/**
 * One detected face's feature vector and its provenance, so the recogniser can decide on
 * every face independently while still refusing to treat a non-identity-grade geometry
 * vector as an identity.
 */
data class FaceFeature(
    val values: FloatArray,
    val source: EmbeddingSource = EmbeddingSource.MODEL,
)

/**
 * Provenance of a frame's [FrameEvent.features].
 *
 * - [MODEL] — a real on-device face embedding (TFLite MobileFaceNet). Identity-grade.
 * - [GEOMETRY] — the 19-d geometry fallback (pose/size/landmarks). NOT identity-grade;
 *   it must never be used to accept a Parent or Child identity.
 */
enum class EmbeddingSource { MODEL, GEOMETRY }
