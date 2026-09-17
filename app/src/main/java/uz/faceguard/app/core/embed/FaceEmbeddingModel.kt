package uz.faceguard.app.core.embed

import android.graphics.Bitmap

/**
 * Normalization applied to each RGB channel before inference:
 * `value = pixel * scale + offset`.
 *
 * Defaults match the bundled MobileFaceNet model (`(pixel - 127.5) / 128`).
 * Input dimensions are treated as fallbacks; the real values are read from the
 * model's input tensor at load time.
 */
data class FaceEmbeddingConfig(
    val inputWidth: Int = 112,
    val inputHeight: Int = 112,
    val inputChannels: Int = 3,
    val normalizeScale: Float = 1f / 128f,
    val normalizeOffset: Float = -127.5f / 128f,
)

/** On-device face embedding model seam; replaced without touching callers. */
interface FaceEmbeddingModel {
    val config: FaceEmbeddingConfig
    fun isReady(): Boolean
    fun embeddingSize(): Int

    /** Runs the model on a cropped face bitmap; null on failure. */
    fun embed(faceBitmap: Bitmap): FloatArray?
}
