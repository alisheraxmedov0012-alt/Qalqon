package uz.faceguard.app.enrollment

import kotlin.math.sqrt
import uz.faceguard.app.domain.enrollment.EnrollmentFrame

/**
 * Shared deterministic fixtures for the enrollment quality tests. No camera, no
 * ML Kit, no bitmap: every "frame" is plain numbers, so each assertion is exact.
 */
object EnrollmentFixtures {

    /** A valid, gate-passing frame; override exactly the metric under test. */
    fun frame(
        timestampMs: Long = 0L,
        faceCount: Int = 1,
        faceWidthRatio: Float = 0.45f,
        centerXRatio: Float = 0.5f,
        centerYRatio: Float = 0.5f,
        pitchDegrees: Float = 3f,
        yawDegrees: Float = 4f,
        rollDegrees: Float = 2f,
        brightness: Float = 0.5f,
        sharpness: Float = 0.12f,
        landmarkVisibility: Float = 1f,
        embedding: FloatArray? = unit(1f, 0f),
    ): EnrollmentFrame = EnrollmentFrame(
        timestampMs = timestampMs,
        faceCount = faceCount,
        faceWidthRatio = faceWidthRatio,
        centerXRatio = centerXRatio,
        centerYRatio = centerYRatio,
        pitchDegrees = pitchDegrees,
        yawDegrees = yawDegrees,
        rollDegrees = rollDegrees,
        brightness = brightness,
        sharpness = sharpness,
        landmarkVisibility = landmarkVisibility,
        embedding = embedding,
    )

    /** L2-normalized vector, so unit-norm expectations hold exactly. */
    fun unit(vararg values: Float): FloatArray {
        val norm = sqrt(values.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    /** Deterministic n-dim vector from a seeded generator (no randomness). */
    fun vector(dimension: Int, seed: Int): FloatArray {
        var state = seed * 2654435761u.toInt()
        val raw = FloatArray(dimension)
        for (i in raw.indices) {
            state = state * 1103515245 + 12345
            raw[i] = ((state shr 16) and 0x7FFF).toFloat() - 16384f
        }
        return unit(*raw)
    }

    /** A near-copy of [base], nudged by [delta] and re-normalized. */
    fun similar(base: FloatArray, delta: Float): FloatArray =
        unit(*FloatArray(base.size) { base[it] + (if (it % 2 == 0) delta else -delta) })
}
