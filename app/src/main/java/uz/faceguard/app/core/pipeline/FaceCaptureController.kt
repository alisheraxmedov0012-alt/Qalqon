package uz.faceguard.app.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
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
 *
 * Every stage is guarded: a camera, ML Kit, bitmap or TFLite failure is
 * reported to the optional [setErrorListener] instead of crashing the app. The
 * analyzer runs on a background thread, so an uncaught exception there would
 * take the whole process down.
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

    private var errorListener: ((Throwable) -> Unit)? = null
    fun setErrorListener(listener: (Throwable) -> Unit) { errorListener = listener }

    @Volatile
    private var lastErrorAt = 0L

    interface Callback {
        fun onFaceFrame(frame: FrameEvent)
    }

    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    /** Null when ML Kit could not create a detector; the pipeline then stays idle. */
    private val detector: FaceDetector? = runCatching {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build(),
        )
    }.onFailure { reportError(it) }.getOrNull()

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun start(previewView: PreviewView, callback: Callback) {
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()

                // SurfaceView (PERFORMANCE) does not composite correctly inside
                // Compose, especially under an elevated Card, and shows a black
                // preview. TextureView (COMPATIBLE) renders reliably.
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

                // Release any existing use cases before re-binding.
                provider.unbindAll()

                val owner = lifecycleOwner
                if (owner == null) {
                    reportError(IllegalStateException("LifecycleOwner is not attached"))
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = buildAnalysis(callback)
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                reportError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Analysis-only start (no preview surface). Used by the protection screen,
     * which runs recognition headlessly while another app is in the foreground.
     */
    fun startAnalyzerOnly() {
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                provider.unbindAll()
                val owner = lifecycleOwner
                if (owner == null) {
                    reportError(IllegalStateException("LifecycleOwner is not attached"))
                    return@addListener
                }
                val analysis = buildAnalysis(null)
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (t: Throwable) {
                reportError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun buildAnalysis(callback: Callback?): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) @ExperimentalGetImage { proxy ->
                    try {
                        val mediaImage = proxy.image
                        val rotationDegrees = proxy.imageInfo.rotationDegrees
                        val bitmap = mediaImage?.let { mediaImageToBitmap(it) }
                        if (mediaImage == null || bitmap == null) return@setAnalyzer

                        val upright = FaceImageUtils.rotate(bitmap, rotationDegrees)
                        val input = InputImage.fromBitmap(upright, 0)
                        val faceDetector = detector ?: return@setAnalyzer

                        // Only frames with at least one detected face are passed on.
                        faceDetector.process(input)
                            .addOnSuccessListener { faces ->
                                try {
                                    if (faces.isNotEmpty()) {
                                        val features = extractEmbedding(upright, faces.first())
                                        val frame = FrameEvent(
                                            image = input,
                                            faceCount = faces.size,
                                            features = features,
                                        )
                                        recognizer?.publish(frame)
                                        callback?.onFaceFrame(frame)
                                    }
                                } catch (t: Throwable) {
                                    reportError(t)
                                }
                            }
                            .addOnFailureListener { reportError(it) }
                    } catch (t: Throwable) {
                        reportError(t)
                    } finally {
                        runCatching { proxy.close() }
                    }
                }
            }

    /** TFLite embedding when ready; geometry vector as the offline fallback. */
    private fun extractEmbedding(bitmap: Bitmap, face: Face): FloatArray? {
        try {
            val model = embeddingModel
            if (model != null && model.isReady()) {
                val crop = FaceImageUtils.cropFace(bitmap, face.boundingBox)
                if (crop != null) {
                    val embedding = model.embed(crop)
                    if (embedding != null && embedding.isNotEmpty()) return embedding
                }
            }
        } catch (t: Throwable) {
            reportError(t)
        }
        return runCatching { FaceFeatureExtractor.extract(face, bitmap.width, bitmap.height) }.getOrNull()
    }

    private fun mediaImageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        return try {
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride
            val rowPadding = (rowStride - pixelStride * image.width).coerceAtLeast(0)
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } catch (t: Throwable) {
            reportError(t)
            null
        }
    }

    fun stop() {
        runCatching {
            cameraProviderFuture.addListener(
                { cameraProviderFuture.get().unbindAll() },
                ContextCompat.getMainExecutor(context),
            )
        }.onFailure { reportError(it) }
    }

    /** Throttled so a repeatedly failing camera cannot spam the UI. */
    private fun reportError(error: Throwable) {
        Log.w(TAG, "face capture failed", error)
        val now = System.currentTimeMillis()
        if (now - lastErrorAt < ERROR_THROTTLE_MS) return
        lastErrorAt = now
        val listener = errorListener ?: return
        runCatching { listener(error) }
    }

    private companion object {
        const val TAG = "FaceCaptureController"
        const val ERROR_THROTTLE_MS = 3_000L
    }
}
