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
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
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

    /**
     * Stage 5: reports the outcome of the most recent bind. Called with `true`
     * once the use cases are bound, and with `false` when the bind fails (camera
     * unavailable/in use, no owner, platform error) so a process-scoped owner can
     * attempt a bounded rebind instead of silently losing recognition.
     */
    private var bindStateListener: ((Boolean) -> Unit)? = null
    fun setBindStateListener(listener: (Boolean) -> Unit) { bindStateListener = listener }

    private fun reportBindState(bound: Boolean) {
        val listener = bindStateListener ?: return
        // The bind callback runs on the main executor; a throwing observer must not
        // abort the camera path.
        runCatching { listener(bound) }
    }

    @Volatile
    private var lastErrorAt = 0L

    interface Callback {
        fun onFaceFrame(frame: FrameEvent)
    }

    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    /** Null when ML Kit could not create a detector; the pipeline then stays idle. */
    private val detector: FaceDetector? = runCatching {
        FaceDetection.getClient(FaceDetectorConfig.DEFAULT.toMlKitOptions())
    }.onFailure { reportError(it) }.getOrNull()

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * Q-1 fix: the CameraX use cases *this* controller has bound, so [stop] releases
     * only what it owns instead of a process-wide `unbindAll()`. The shared
     * `ProcessCameraProvider` is used by more than one component (the process-scoped
     * protection session and the transient enrollment/debug screens); a blind
     * `unbindAll()` on teardown could otherwise destroy a binding this controller
     * never created. Guarded by [boundUseCasesLock] because it is written on the main
     * thread (the provider listener) and read from [stop]'s listener.
     */
    private val boundUseCasesLock = Any()
    private var boundUseCases: List<UseCase> = emptyList()

    private fun rememberBound(vararg useCases: UseCase) {
        synchronized(boundUseCasesLock) { boundUseCases = useCases.toList() }
    }

    private fun takeBound(): List<UseCase> =
        synchronized(boundUseCasesLock) {
            val owned = boundUseCases
            boundUseCases = emptyList()
            owned
        }

    /** True while this controller currently holds a CameraX binding. Diagnostics/tests. */
    fun isBound(): Boolean = synchronized(boundUseCasesLock) { boundUseCases.isNotEmpty() }

    fun start(previewView: PreviewView, callback: Callback) {
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()

                // SurfaceView (PERFORMANCE) does not composite correctly inside
                // Compose, especially under an elevated Card, and shows a black
                // preview. TextureView (COMPATIBLE) renders reliably.
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

                // Q-1: take over the shared process-wide camera. Only one ImageAnalysis
                // can be bound to the front camera, so whoever held it (the protection
                // session, or a previous transient screen) is evicted here. The evicted
                // protection session re-establishes its binding via
                // CameraBindingCoordinator.onTransientCameraReleased() once this screen
                // releases the camera — see [stop].
                provider.unbindAll()

                val owner = lifecycleOwner
                if (owner == null) {
                    reportError(IllegalStateException("LifecycleOwner is not attached"))
                    reportBindState(false)
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = buildAnalysis(callback)
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                // Q-1: record ownership so teardown releases only these use cases.
                rememberBound(preview, analysis)
                reportBindState(true)
            } catch (t: Throwable) {
                reportError(t)
                reportBindState(false)
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
                // Q-1: same takeover rule as [start] — the protection session claims the
                // shared camera; any previous holder is evicted.
                provider.unbindAll()
                val owner = lifecycleOwner
                if (owner == null) {
                    reportError(IllegalStateException("LifecycleOwner is not attached"))
                    reportBindState(false)
                    return@addListener
                }
                val analysis = buildAnalysis(null)
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                // Q-1: record ownership so teardown releases only this use case.
                rememberBound(analysis)
                reportBindState(true)
            } catch (t: Throwable) {
                reportError(t)
                reportBindState(false)
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
                                    // Stage 2: embed every detected face (bounded) so the
                                    // identity policy sees all of them, not just the first.
                                    val faceFeatures = faces.take(MAX_ANALYZED_FACES).mapNotNull { face ->
                                        extractEmbedding(upright, face)?.let {
                                            FaceFeature(it.values, it.source)
                                        }
                                    }
                                    val liveProbability = primary?.let { runAntiSpoof(upright, it) }
                                    val frame = FrameEvent(
                                        image = input,
                                        faceCount = faces.size,
                                        // features/embeddingSource stay the primary face for
                                        // enrollment guidance and liveness; `faces` carries all
                                        // faces for the identity policy.
                                        features = faceFeatures.firstOrNull()?.values,
                                        quality = buildQuality(faces, primary, upright),
                                        liveProbability = liveProbability,
                                        embeddingSource = faceFeatures.firstOrNull()?.source
                                            ?: EmbeddingSource.MODEL,
                                        faces = faceFeatures,
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

    /**
     * A frame's feature vector together with where it came from, so the recogniser can
     * refuse to treat a non-identity-grade geometry vector as an identity.
     */
    private data class FeatureVector(val values: FloatArray, val source: EmbeddingSource)

    /**
     * TFLite embedding when ready; geometry vector as the offline fallback.
     *
     * Stage 1: the returned vector is tagged with its [EmbeddingSource] — a real model
     * embedding is identity-grade, the geometry fallback is not.
     */
    private fun extractEmbedding(bitmap: Bitmap, face: Face): FeatureVector? {
        try {
            val model = embeddingModel
            if (model != null && model.isReady()) {
                val crop = FaceImageUtils.cropFace(bitmap, face.boundingBox)
                if (crop != null) {
                    val embedding = model.embed(crop)
                    if (embedding != null && embedding.isNotEmpty()) {
                        return FeatureVector(embedding, EmbeddingSource.MODEL)
                    }
                }
            }
        } catch (t: Throwable) {
            reportError(t)
        }
        return runCatching { FaceFeatureExtractor.extract(face, bitmap.width, bitmap.height) }
            .getOrNull()
            ?.let { FeatureVector(it, EmbeddingSource.GEOMETRY) }
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
        val centerX = if (bitmap.width > 0) box.exactCenterX() / bitmap.width.toFloat() else FaceQuality.CENTER
        val centerY = if (bitmap.height > 0) box.exactCenterY() / bitmap.height.toFloat() else FaceQuality.CENTER
        val (brightness, sharpness) = faceLuminanceAndSharpness(bitmap, box)
        return FaceQuality(
            faceCount = faces.size,
            headEulerAngleX = primary.headEulerAngleX,
            headEulerAngleY = primary.headEulerAngleY,
            headEulerAngleZ = primary.headEulerAngleZ,
            faceWidthRatio = widthRatio,
            brightness = brightness,
            faceCenterXRatio = centerX,
            faceCenterYRatio = centerY,
            sharpness = sharpness,
            landmarkVisibility = FaceFeatureExtractor.landmarkVisibility(primary),
        )
    }

    /**
     * Mean luminance (0..1) and mean absolute luminance gradient (0..1) of the
     * face region, both from a single strided pixel read.
     *
     * The gradient is a cheap focus proxy: a sharp face has strong neighbour-to-
     * neighbour luminance changes, a blurred or motion-smeared one is flat. It is
     * deterministic and needs no image-processing library.
     */
    private fun faceLuminanceAndSharpness(bitmap: Bitmap, box: Rect): Pair<Float, Float> {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return 0f to 0f

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        fun luminance(offset: Int): Float {
            val pixel = pixels[offset]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0).toFloat()
        }

        var sum = 0.0
        var gradientSum = 0.0
        var samples = 0
        var gradientSamples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val offset = y * width + x
                sum += luminance(offset)
                samples++
                if (x + SHARPNESS_STRIDE < width) {
                    gradientSum += kotlin.math.abs(luminance(offset) - luminance(offset + SHARPNESS_STRIDE))
                    gradientSamples++
                }
                x += SHARPNESS_STRIDE
            }
            y += SHARPNESS_STRIDE
        }
        val brightness = if (samples == 0) 0f else (sum / samples).toFloat()
        val sharpness = if (gradientSamples == 0) 0f else (gradientSum / gradientSamples).toFloat()
        return brightness to sharpness.coerceIn(0f, 1f)
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

    /**
     * Q-1 fix: releases only the use cases *this* controller bound, never a
     * process-wide `unbindAll()`.
     *
     * The shared `ProcessCameraProvider` is also used by the process-scoped protection
     * session; a blind `unbindAll()` here would destroy a binding this controller never
     * created (and, on the protection side, drop the analyzer without its owner knowing).
     * Unbinding by ownership keeps each consumer responsible for exactly its own
     * binding. A controller that bound nothing releases nothing.
     */
    fun stop() {
        runCatching {
            cameraProviderFuture.addListener(
                {
                    val owned = takeBound()
                    if (owned.isNotEmpty()) {
                        cameraProviderFuture.get().unbind(*owned.toTypedArray())
                    }
                },
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
        /** Pixel step used for both luminance and gradient sampling. */
        const val SHARPNESS_STRIDE = 8
        /**
         * Stage 2: the maximum number of faces embedded per frame. One MobileFaceNet
         * inference per face is not free, so a frame that somehow reports a crowd is
         * bounded; the policy still sees up to this many faces and never depends on order.
         */
        const val MAX_ANALYZED_FACES = 5
    }
}
