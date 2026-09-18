package uz.faceguard.app.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import uz.faceguard.app.core.embed.FaceEmbeddingModel
import uz.faceguard.app.core.embed.FaceFeatureExtractor
import uz.faceguard.app.core.embed.FaceImageUtils
import uz.faceguard.app.core.recognition.Recognizer

/**
 * Runs the front camera + on-device face detection. Detected faces are handed
 * to the configured embedding model (TFLite MobileFaceNet) when available;
 * otherwise the frame falls back to the geometry extractor.
 */
class FaceCaptureController(
    private val context: Context,
) {
    private var lifecycleOwner: LifecycleOwner? = null
    fun setLifecycleOwner(value: LifecycleOwner) { lifecycleOwner = value }

    private var recognizer: Recognizer? = null
    fun setRecognizer(value: Recognizer) { recognizer = value }

    private var embeddingModel: FaceEmbeddingModel? = null
    fun setEmbeddingModel(value: FaceEmbeddingModel) { embeddingModel = value }

    interface Callback {
        fun onFaceFrame(frame: FrameEvent)
    }

    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build(),
    )

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun start(previewView: PreviewView, callback: Callback) {
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = buildAnalysis(callback)
            try {
                provider.unbindAll()
                lifecycleOwner?.let {
                    provider.bindToLifecycle(
                        it,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis,
                    )
                }
            } catch (_: Exception) { /* camera errors surface downstream */ }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Analysis-only start (no preview surface). Used by the protection screen,
     * which runs recognition headlessly while another app is in the foreground.
     */
    fun startAnalyzerOnly() {
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            try {
                provider.unbindAll()
                lifecycleOwner?.let {
                    provider.bindToLifecycle(
                        it,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        buildAnalysis(null),
                    )
                }
            } catch (_: Exception) { /* camera errors surface downstream */ }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun buildAnalysis(callback: Callback?): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) @ExperimentalGetImage { proxy ->
                    val mediaImage = proxy.image ?: run { proxy.close(); return@setAnalyzer }
                    val rotationDegrees = proxy.imageInfo.rotationDegrees
                    val bitmap = mediaImageToBitmap(mediaImage) ?: run { proxy.close(); return@setAnalyzer }
                    proxy.close()

                    val upright = FaceImageUtils.rotate(bitmap, rotationDegrees)
                    val input = InputImage.fromBitmap(upright, 0)

                    // Only frames with at least one detected face are passed on.
                    detector.process(input).addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val features = extractEmbedding(upright, faces.first())
                            val frame = FrameEvent(image = input, faceCount = faces.size, features = features)
                            recognizer?.publish(frame)
                            callback?.onFaceFrame(frame)
                        }
                    }.addOnFailureListener { }
                }
            }

    /** TFLite embedding when ready; geometry vector as the offline fallback. */
    private fun extractEmbedding(bitmap: Bitmap, face: Face): FloatArray? {
        val model = embeddingModel
        if (model != null && model.isReady()) {
            val crop = FaceImageUtils.cropFace(bitmap, face.boundingBox) ?: return null
            val embedding = model.embed(crop)
            if (embedding != null && embedding.isNotEmpty()) return embedding
        }
        return FaceFeatureExtractor.extract(face, bitmap.width, bitmap.height)
    }

    private fun mediaImageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    fun stop() {
        cameraProviderFuture.addListener(
            { cameraProviderFuture.get().unbindAll() },
            ContextCompat.getMainExecutor(context),
        )
    }
}
