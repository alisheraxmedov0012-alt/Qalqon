package uz.faceguard.app.core.embed

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads a MobileFaceNet `.tflite` model from assets and runs on-device
 * embedding inference. If the model is missing or fails to load, [isReady]
 * returns false and callers fall back to the geometry extractor.
 *
 * Expected asset path: `assets/models/mobile_face_net.tflite`.
 */
class TfLiteMobileFaceNet(
    context: Context,
    private val modelAssetPath: String = DEFAULT_ASSET_PATH,
    override val config: FaceEmbeddingConfig = FaceEmbeddingConfig(),
) : FaceEmbeddingModel {

    private val interpreter: Interpreter? = loadModel(context, modelAssetPath)

    /** Model input is NHWC; batch may be larger than 1 (the bundled model uses 2). */
    private val inputShape: IntArray = interpreter
        ?.getInputTensor(0)
        ?.shape()
        ?.takeIf { it.size == 4 }
        ?: intArrayOf(DEFAULT_BATCH, config.inputHeight, config.inputWidth, config.inputChannels)

    private val batchSize: Int = inputShape[0].coerceAtLeast(1)
    private val inputHeight: Int = inputShape[1].coerceAtLeast(1)
    private val inputWidth: Int = inputShape[2].coerceAtLeast(1)

    private val outputSize: Int = interpreter
        ?.getOutputTensor(0)
        ?.shape()
        ?.lastOrNull()
        ?.takeIf { it > 0 }
        ?: DEFAULT_EMBEDDING_SIZE

    override fun isReady(): Boolean = interpreter != null

    override fun embeddingSize(): Int = outputSize

    override fun embed(faceBitmap: Bitmap): FloatArray? {
        val model = interpreter ?: return null
        val input = prepareInput(faceBitmap) ?: return null
        val output = Array(batchSize) { FloatArray(outputSize) }
        return try {
            model.run(input, output)
            l2Normalize(output.first())
            output.first()
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareInput(bitmap: Bitmap): ByteBuffer? {
        if (inputWidth <= 0 || inputHeight <= 0) return null
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val buffer = ByteBuffer
            .allocateDirect(batchSize * inputHeight * inputWidth * config.inputChannels * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        // The bundled model expects a fixed batch of 2. Its batch slots are
        // independent, so the same face fills both and slot 0 is read back.
        repeat(batchSize) {
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                buffer.putFloat(r * config.normalizeScale + config.normalizeOffset)
                buffer.putFloat(g * config.normalizeScale + config.normalizeOffset)
                buffer.putFloat(b * config.normalizeScale + config.normalizeOffset)
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        buffer.rewind()
        return buffer
    }

    private fun l2Normalize(values: FloatArray) {
        var sum = 0.0
        for (value in values) sum += value.toDouble() * value
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm <= 1e-10f) return
        for (i in values.indices) values[i] /= norm
    }

    private fun loadModel(context: Context, path: String): Interpreter? {
        return try {
            val descriptor = context.assets.openFd(path)
            descriptor.use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    val buffer: MappedByteBuffer = stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                    Interpreter(buffer)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val DEFAULT_ASSET_PATH = "models/mobile_face_net.tflite"
        const val DEFAULT_EMBEDDING_SIZE = 192
        const val DEFAULT_BATCH = 1
    }
}
