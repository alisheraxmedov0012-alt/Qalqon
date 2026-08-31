package uz.faceguard.app.core.pipeline

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import uz.faceguard.app.core.recognition.Recognizer

/**
 * Runs the front camera + on-device face detection. Only frames with at least
 * one detected face are handed over to the callback. The embedding seam
 * (`FaceEmbeddable`) plugs in later without changing this controller.
 */
class FaceCaptureController(
    private val context: Context,
) {
    // lifecycleOwner stays via a private setter to avoid constructor-binding on AndroidView use
    private var lifecycleOwner: LifecycleOwner? = null
    fun setLifecycleOwner(value: LifecycleOwner) { lifecycleOwner = value }

    private var recognizer: Recognizer? = null
    fun setRecognizer(value: Recognizer) { recognizer = value }
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
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) @ExperimentalGetImage { proxy ->
                    val mediaImage = proxy.image ?: run { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    // Only frames with at least one detected face are passed on.
                    detector.process(input).addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val frame = FrameEvent(image = input, faceCount = faces.size)
                            recognizer?.publish(frame)
                            callback?.onFaceFrame(frame)
                        }
                        proxy.close()
                    }.addOnFailureListener { proxy.close() }
                }
            }

    fun stop() {
        cameraProviderFuture.addListener(
            { cameraProviderFuture.get().unbindAll() },
            ContextCompat.getMainExecutor(context),
        )
    }
}
