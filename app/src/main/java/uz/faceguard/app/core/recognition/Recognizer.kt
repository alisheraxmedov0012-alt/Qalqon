package uz.faceguard.app.core.recognition

import kotlinx.coroutines.flow.MutableSharedFlow
import uz.faceguard.app.core.embed.FaceEmbeddingCodec
import uz.faceguard.app.core.pipeline.FaceFeature
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.ProtectionAction

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

    /**
     * Stage 2: evaluates **every** detected face in the frame into a per-face
     * [IdentityDecision], then aggregates them with the deterministic [MultiFacePolicyEngine].
     * The result no longer depends on the detector's face order.
     *
     * [childActionOf] resolves the configured action for a recognised child and the current
     * foreground app, so multiple recognised children are resolved to the most restrictive
     * one; it defaults to "no policy" so a caller without app context still gets a
     * deterministic result.
     */
    fun evaluateAll(
        frame: FrameEvent,
        parent: ParentProfile?,
        children: List<ChildProfile>,
        childActionOf: (childId: Long) -> ProtectionAction? = { null },
    ): MultiFaceDecision {
        val faces = frame.faceFeatures()
        if (faces.isEmpty()) {
            return MultiFaceDecision(RecognitionResult.NoFace, emptyList(), null, frame.faceCount)
        }

        val parentTemplate = parent?.faceTemplateRef?.let { FaceEmbeddingCodec.decode(it) }
        val childTemplates = children.mapNotNull { child ->
            child.faceTemplateRef?.let { ref ->
                FaceEmbeddingCodec.decode(ref)?.let { template ->
                    ChildTemplate(childId = child.id, childName = child.childName, template = template)
                }
            }
        }

        val candidates = faces.map { face ->
            IdentityDecisionEngine.decide(
                live = face.values,
                source = face.source,
                parentTemplate = parentTemplate,
                children = childTemplates,
                thresholds = thresholds,
            )
        }
        return MultiFacePolicyEngine.decide(candidates, childActionOf)
    }

    /**
     * Single-result convenience over [evaluateAll], kept for the debug screen and any caller
     * that only needs the final identity. Behaviour is identical to Stage 1 for one face.
     */
    fun evaluate(
        frame: FrameEvent,
        parent: ParentProfile?,
        children: List<ChildProfile>,
        childActionOf: (childId: Long) -> ProtectionAction? = { null },
    ): RecognitionResult = evaluateAll(frame, parent, children, childActionOf).result

    /**
     * The per-face feature vectors for this frame.
     *
     * Prefers the multi-face [FrameEvent.faces] list. When it is empty — a single-face frame,
     * or a caller that only populated [FrameEvent.features] — it falls back to that single
     * vector, so pre-Stage-2 behaviour is preserved exactly.
     */
    private fun FrameEvent.faceFeatures(): List<FaceFeature> = when {
        faces.isNotEmpty() -> faces
        features != null && features.isNotEmpty() -> listOf(FaceFeature(features, embeddingSource))
        else -> emptyList()
    }
}
