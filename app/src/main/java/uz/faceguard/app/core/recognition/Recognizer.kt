package uz.faceguard.app.core.recognition

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.sqrt
import uz.faceguard.app.core.embed.FaceEmbeddingCodec
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile

data class Thresholds(val parent: Double = 0.82, val child: Double = 0.78) {
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
 * Templates are the face vectors produced during enrollment (TFLite
 * MobileFaceNet embeddings when available, otherwise geometry fallback) and
 * stored in `ParentProfile.faceTemplateRef` / `ChildProfile.faceTemplateRef`.
 * The parent template is evaluated first so the parent identity can never be
 * misreported as a child.
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

        parent?.faceTemplateRef?.let { ref ->
            FaceEmbeddingCodec.decode(ref)?.let { template ->
                val score = cosine(frameFeatures, template)
                if (score >= thresholds.parent) return RecognitionResult.ParentRecognized(score)
            }
        }

        var bestChild: Pair<ChildProfile, Double>? = null
        for (child in children) {
            val ref = child.faceTemplateRef ?: continue
            val template = FaceEmbeddingCodec.decode(ref) ?: continue
            val score = cosine(frameFeatures, template)
            if (score >= thresholds.child && (bestChild == null || score > bestChild!!.second)) {
                bestChild = child to score
            }
        }
        if (bestChild != null) {
            return RecognitionResult.ChildRecognized(bestChild!!.first.id, bestChild!!.first.childName, bestChild!!.second)
        }

        return RecognitionResult.Unknown(bestScore(frameFeatures, parent, children))
    }

    private fun bestScore(frameFeatures: FloatArray, parent: ParentProfile?, children: List<ChildProfile>): Double {
        var best = 0.0
        parent?.faceTemplateRef?.let { ref ->
            FaceEmbeddingCodec.decode(ref)?.let { best = maxOf(best, cosine(frameFeatures, it)) }
        }
        children.forEach { child ->
            child.faceTemplateRef?.let { ref ->
                FaceEmbeddingCodec.decode(ref)?.let { best = maxOf(best, cosine(frameFeatures, it)) }
            }
        }
        return best
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-6) return 0.0
        return (dot / denom).coerceIn(-1.0, 1.0)
    }
}
