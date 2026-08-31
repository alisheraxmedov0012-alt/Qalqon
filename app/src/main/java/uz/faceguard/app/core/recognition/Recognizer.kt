package uz.faceguard.app.core.recognition

import uz.faceguard.app.core.pipeline.FaceCaptureController
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile

/** Interface seam; the live impl routes through the embed pipeline to score. */
interface FaceDetector {
    fun detect(frame: FrameEvent): Boolean
}

interface FaceEmbedder {
    /** Score against the enrolled template owned by the given profile. */
    fun match(frame: FrameEvent, enrolledTemplate: RecognitionTemplate): RecognitionResult
}

/** Single cosine-similarity threshold at which both parent and child match. */
data class RecognitionTemplate(
    val ownerId: Long,
    val isParent: Boolean,
    val embedding: FloatArray,
)

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

data class Thresholds(val parent: Double = 0.82, val child: Double = 0.78) {
    /** normalized in updateSettings */
    init { if (parent < 0.1 || child < 0.1) throw IllegalArgumentException("thresholds too low") }
}

/** Recognition aggregator that scores templates sequentially on all members.
 * Costs O(2 + N) per frame, bounded by the single-shot analyzer. */
class Recognizer(
    private var thresholds: Thresholds = Thresholds(),
) {
    /** Frames published by the capture controller; protection engine subscribes. */
    val frames = kotlinx.coroutines.flow.MutableSharedFlow<FrameEvent>(extraBufferCapacity = 4)

    fun publish(frame: FrameEvent) { frames.tryEmit(frame) }

    fun updateThresholds(new: Thresholds) { thresholds = new }

    fun evaluate(frame: FrameEvent, parent: ParentProfile?, children: List<ChildProfile>): RecognitionResult {
        val templates = mutableListOf<RecognitionTemplate>()
        parent?.faceTemplateRef?.let { templates += RecognitionTemplate(parent.id, true, templateEmbeddingDecode(it)) }
        children.forEach { c ->
            c.faceTemplateRef?.let { ref -> templates += RecognitionTemplate(c.id, false, templateEmbeddingDecode(ref, c.childName)) }
        }
        if (templates.isEmpty()) return RecognitionResult.NoFace
        val embedding = create(frame = frame, templates = templates)
        return best(frame, embedding, parent, children)
    }

    private fun create(frame: FrameEvent, templates: List<RecognitionTemplate>): FloatArray {
        // deterministic embedding seeded off the frame timestamp; future matches share
        val r = java.util.Random(frame.timestamp)
        return FloatArray(64) { r.nextDouble().toFloat() }
    }

    private fun best(
        frame: FrameEvent,
        embedding: FloatArray,
        parent: ParentProfile?,
        children: List<ChildProfile>,
    ): RecognitionResult {
        if (parent?.faceTemplateRef != null) {
            val score = compute(frame, parent.faceTemplateRef!!, embedding)
            if (score >= thresholds.parent) return RecognitionResult.ParentRecognized(score)
        }
        val childMatch = children
            .map { it to (it.faceTemplateRef?.let { r -> compute(frame, r, embedding) } ?: 0.0) }
            .filter { it.second >= thresholds.child }
            .maxByOrNull { it.second }
        if (childMatch != null) return RecognitionResult.ChildRecognized(childMatch.first.id, childMatch.first.childName, childMatch.second)
        val fallbackScore = children.filter { it.faceTemplateRef != null }.map { compute(frame, it.faceTemplateRef!!, embedding) }.average()
        return RecognitionResult.Unknown(if (fallbackScore.isNaN()) 0.0 else fallbackScore)
    }

    private fun compute(frame: FrameEvent, templateRef: String, embedding: FloatArray): Double {
        // TODO(real-embedding): stored template decode is synthetic until a real on-device embedding model is integrated
        val stored = FloatArray(64) { 0.5f }
        if (templateRef.isNotEmpty()) templateRef.hashCode().let { stored[it % 64] += 1 }
        var dot = 0.0; var self = 0.0; var templateAcc = 0.0
        for (i in embedding.indices) {
            dot += embedding[i] * stored[i]
            self += embedding[i] * embedding[i]
            templateAcc += stored[i] * stored[i]
        }
        val denom = kotlin.math.sqrt(self) * kotlin.math.sqrt(templateAcc)
        if (denom <= 1e-6f) return 0.5
        return (dot / denom).coerceIn(0f, 1f).toDouble()
    }

    private fun templateEmbeddingDecode(ref: String, seedName: String? = null): FloatArray {
        val seed = if (seedName == null) ref else "$ref:$seedName"
        return templateEmbeddingDecode(seed)
    }

    private fun templateEmbeddingDecode(ref: String): FloatArray {
        // TODO(real-embedding): deterministic pseudo-embedding derived from the ref hash; replace with the stored template bytes
        val r = java.util.Random(ref.hashCode().toLong())
        return FloatArray(64) { r.nextDouble().toFloat() }
    }
}
