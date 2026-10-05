package uz.faceguard.app.core.recognition

import kotlinx.coroutines.flow.MutableSharedFlow
import uz.faceguard.app.core.embed.FaceEmbeddingCodec
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile

/**
 * Cosine-similarity acceptance thresholds on L2-normalized embeddings.
 * Anchored to the bundled MobileFaceNet reference implementation (its L2
 * distance of 0.8 maps to cosine ~0.68). Heuristic — tune on real devices.
 */
data class Thresholds(val parent: Double = 0.68, val child: Double = 0.62) {
    init { if (parent < 0.1 || child < 0.1) throw IllegalArgumentException("thresholds too low") }
}

sealed class RecognitionResult {
    data class ParentRecognized(val confidence: Double) : RecognitionResult()
    data class ChildRecognized(val childId: Long, val childName: String, val confidence: Double) : RecognitionResult()
    data class Unknown(val confidence: Double) : RecognitionResult()
    object NoFace : RecognitionResult()
    /** Heuristic: frames arrive but no face is ever detected while protected app is active. */
    object CameraPossiblyObstructed : RecognitionResult()
    /** Rolling confidence variance exceeded the stability band; hold the current state. */
    object UnstableRecognition : RecognitionResult()
}

/**
 * Matches a live frame against persisted on-device templates.
 *
 * Templates are the face vectors produced during enrollment (TFLite MobileFaceNet
 * embeddings when available, otherwise geometry fallback) and stored in
 * `ParentProfile.faceTemplateRef` / `ChildProfile.faceTemplateRef`.
 *
 * Stage 1: the decision itself lives in the pure, order-independent
 * [IdentityDecisionEngine] (documented there), so the recogniser is a thin adapter
 * that only decodes stored templates and delegates. Identity is accepted only from a
 * real model embedding whose dimension matches the stored template.
 */
class Recognizer(
    private var thresholds: Thresholds = Thresholds(),
) {
    /** Frames published by the capture controller; protection engine subscribes. */
    val frames = MutableSharedFlow<FrameEvent>(extraBufferCapacity = 4)

    fun publish(frame: FrameEvent) { frames.tryEmit(frame) }

    fun updateThresholds(new: Thresholds) { thresholds = new }

    fun evaluate(frame: FrameEvent, parent: ParentProfile?, children: List<ChildProfile>): RecognitionResult {
        val frameFeatures = frame.features ?: return RecognitionResult.NoFace
        if (frameFeatures.isEmpty()) return RecognitionResult.NoFace

        return IdentityDecisionEngine.decide(
            live = frameFeatures,
            source = frame.embeddingSource,
            parentTemplate = parent?.faceTemplateRef?.let { FaceEmbeddingCodec.decode(it) },
            children = children.mapNotNull { child ->
                child.faceTemplateRef?.let { ref ->
                    FaceEmbeddingCodec.decode(ref)?.let { template ->
                        ChildTemplate(childId = child.id, childName = child.childName, template = template)
                    }
                }
            },
            thresholds = thresholds,
        ).result
    }
}
