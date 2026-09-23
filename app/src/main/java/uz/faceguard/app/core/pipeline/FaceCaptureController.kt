package uz.faceguard.app.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import uz.faceguard.app.core.liveness.AntiSpoofModel
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

    /**
     * Group 9: optional anti-spoofing model. Null (the default) leaves
     * [FrameEvent.liveProbability] null and liveness is decided by the passive
     * heuristic. A model is only queried when [AntiSpoofModel.isReady].
     */
    private var antiSpoofModel: AntiSpoofModel? = null
    fun setAntiSpoofModel(value: AntiSpoofModel?) { antiSpoofModel = value }

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

                        // Every analyzed frame is forwarded (even with no face)
                        // so the UI can show live guidance.
                        faceDetector.process(input)
                            .addOnSuccessListener { faces ->
                                try {
                                    val primary = faces.firstOrNull()
                                    val features = primary?.let { extractEmbedding(upright, it) }
                                    val liveProbability = primary?.let { runAntiSpoof(upright, it) }
                                    val frame = FrameEvent(
                                        image = input,
                                        faceCount = faces.size,
                                        features = features,
                                        quality = buildQuality(faces, primary, upright),
                                        liveProbability = liveProbability,
                                    )
                                    recognizer?.publish(frame)
                                    callback?.onFaceFrame(frame)
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

    /**
     * Group 9: runs the optional anti-spoofing model on the face crop. Returns
     * null when no model is configured/ready or inference fails, so the passive
     * heuristic decides liveness instead of a fabricated score.
     */
    private fun runAntiSpoof(bitmap: Bitmap, face: Face): Float? {
        val model = antiSpoofModel ?: return null
        if (!model.isReady()) return null
        return try {
            val crop = FaceImageUtils.cropFace(bitmap, face.boundingBox) ?: return null
            try {
                model.livenessScore(crop)
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } catch (t: Throwable) {
            reportError(t)
            null
        }
    }

    /** Live face metrics for enrollment guidance. */
    private fun buildQuality(faces: List<Face>, primary: Face?, bitmap: Bitmap): FaceQuality {
        if (primary == null) return FaceQuality(faceCount = 0)
        val box = primary.boundingBox
        val widthRatio = if (bitmap.width > 0) box.width().toFloat() / bitmap.width.toFloat() else 0f
        return FaceQuality(
            faceCount = faces.size,
            headEulerAngleX = primary.headEulerAngleX,
            headEulerAngleY = primary.headEulerAngleY,
            headEulerAngleZ = primary.headEulerAngleZ,
            faceWidthRatio = widthRatio,
            brightness = averageLuminance(bitmap, box),
        )
    }

    /** Average luminance (0..1) of the face region; sampled for speed. */
    private fun averageLuminance(bitmap: Bitmap, box: Rect): Float {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return 0f

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        var sum = 0.0
        var samples = 0
        var i = 0
        while (i < pixels.size) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            samples++
            i += LUMINANCE_STRIDE
        }
        return if (samples == 0) 0f else (sum / samples).toFloat()
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
        const val LUMINANCE_STRIDE = 8
    }
}
