package uz.faceguard.app.core.embed

import uz.faceguard.app.core.pipeline.FrameEvent

/**
 * Enrollment collects accepted face frames and turns them into a compact,
 * on-device template reference. The reference is what later gets stored in
 * `ParentProfile.faceTemplateRef` / `ChildProfile.faceTemplateRef`.
 */
interface FaceEmbeddable {
    /** Returns an encoded template, or an empty string when not enough data. */
    suspend fun collect(faces: List<FrameEvent>): String
}

/**
 * Averages the per-frame face vectors (AI embeddings when the model is
 * present, otherwise geometry fallback vectors) into one stable template.
 */
class MeanFaceEmbeddingCollector : FaceEmbeddable {
    override suspend fun collect(faces: List<FrameEvent>): String {
        val vectors = faces.mapNotNull { it.features }.filter { it.isNotEmpty() }
        if (vectors.isEmpty()) return ""

        val dim = vectors.first().size
        val mean = FloatArray(dim)
        var count = 0
        for (vector in vectors) {
            if (vector.size != dim) continue
            for (i in mean.indices) mean[i] += vector[i]
            count++
        }
        if (count == 0) return ""

        for (i in mean.indices) mean[i] /= count
        return FaceEmbeddingCodec.encode(mean)
    }
}
