package uz.faceguard.app.core.liveness

import android.graphics.Bitmap

/**
 * Group 9: on-device anti-spoofing model seam.
 *
 * No anti-spoofing model is bundled in this build. Supplying a real one requires
 * a vetted, licensed, fully offline model whose input/output contract, size and
 * SHA-256 are documented (see the README). Until then callers must check
 * [isReady] and fall back to the passive heuristic.
 *
 * The seam exists so a model can be added without touching the engine, runtime or
 * policy layers: the model produces a per-frame live probability, which flows
 * into [LivenessFrame.modelScore] and is decided on by [TemporalLivenessDetector].
 */
interface AntiSpoofModel {

    /** False when no usable model is loaded; callers must then not call [livenessScore]. */
    fun isReady(): Boolean

    /**
     * Probability that the face is live, in `[0, 1]`, or null when unavailable or
     * when inference fails. No fabricated value is ever returned.
     */
    fun livenessScore(faceBitmap: Bitmap): Float?
}
