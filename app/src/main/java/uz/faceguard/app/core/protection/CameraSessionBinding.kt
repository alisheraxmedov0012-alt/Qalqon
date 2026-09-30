package uz.faceguard.app.core.protection

import android.content.Context
import uz.faceguard.app.core.embed.FaceEmbeddingModel
import uz.faceguard.app.core.pipeline.CameraSessionLifecycleOwner
import uz.faceguard.app.core.pipeline.FaceCaptureController
import uz.faceguard.app.core.recognition.Recognizer

/**
 * Phase 7.1: the camera work the process-scoped session performs, behind a seam.
 *
 * The session owns *whether* and *when* the camera runs; this owns *how* it is
 * bound, so the session's ownership and lifecycle rules stay unit-testable
 * without a real camera (or any Android camera stack).
 */
interface CameraSessionBinding {
    fun start()
    fun stop()
}

/**
 * Production binding: front-camera ImageAnalysis bound to a process-owned
 * [CameraSessionLifecycleOwner], feeding the shared [Recognizer].
 *
 * It reuses the existing [FaceCaptureController] pipeline unchanged (CameraX ->
 * ML Kit -> embedding -> [Recognizer]) and deliberately binds analysis only —
 * background recognition needs no preview surface, so no Preview pipeline is
 * created. There is exactly one camera pipeline in the app; this is that same
 * pipeline, moved off the screen's lifecycle.
 */
class CameraXSessionBinding(
    private val context: Context,
    private val recognizer: Recognizer,
    private val embeddingModel: FaceEmbeddingModel,
) : CameraSessionBinding {

    private var lifecycleOwner: CameraSessionLifecycleOwner? = null
    private var controller: FaceCaptureController? = null

    override fun start() {
        if (lifecycleOwner != null) return
        val owner = CameraSessionLifecycleOwner()
        val capture = FaceCaptureController(context).apply {
            setRecognizer(recognizer)
            setEmbeddingModel(embeddingModel)
            setLifecycleOwner(owner)
        }
        lifecycleOwner = owner
        controller = capture
        // RESUMED first so the owner is ready when the controller binds.
        owner.start()
        capture.startAnalyzerOnly()
    }

    override fun stop() {
        controller?.stop()
        controller = null
        lifecycleOwner?.stop()
        lifecycleOwner = null
    }
}
