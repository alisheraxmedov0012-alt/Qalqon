package uz.faceguard.app.domain.similarity

import kotlin.math.sqrt

/**
 * The one cosine-similarity implementation the app uses.
 *
 * Enrollment's temporal-consistency check and the recognition engine both compare
 * embeddings with this exact maths, so "similar" means the same thing on both
 * sides and a second, subtly different copy cannot appear. It lives in the domain
 * (the layer `core` already depends on) rather than being duplicated.
 *
 * Behaviour is unchanged from the original recognition implementation: compared
 * over the shorter of the two vectors, `0.0` for a degenerate (near-zero) pair,
 * clamped to `[-1, 1]`.
 */
object CosineSimilarity {

    fun of(a: FloatArray, b: FloatArray): Double {
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
