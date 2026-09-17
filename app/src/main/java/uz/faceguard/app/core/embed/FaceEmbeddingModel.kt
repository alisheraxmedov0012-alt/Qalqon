package uz.faceguard.app.core.embed

import android.graphics.Bitmap

/**
 * Normalization applied to each RGB channel before inference:
 * `value = pixel * scale + offset`. MobileFaceNet builds are usually
 * normalized to `[-1, 1]` (`2/255` scale, `-1` offset).
 */
data class FaceEmbeddingConfig(
    val inputWidth: Int = 112,
    val inputHeight: Int = 112,
    val inputChannels: Int = 3,
    val normalizeScale: Float = 2f / 255f,
    val normalizeOffset: Float = -1f,
)

/** On-device face embedding model seam; replaced without touching callers. */
interface FaceEmbeddingModel {
    val config: FaceEmbeddingConfig
    fun isReady(): Boolean
    fun embeddingSize(): Int

    /** Runs the model on a cropped face bitmap; null on failure. */
    fun embed(faceBitmap: Bitmap): FloatArray?
}
