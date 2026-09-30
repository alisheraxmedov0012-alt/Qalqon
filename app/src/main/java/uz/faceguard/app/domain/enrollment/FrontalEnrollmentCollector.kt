package uz.faceguard.app.domain.enrollment

import uz.faceguard.app.domain.similarity.CosineSimilarity

/**
 * Phase 12 (enrollment): frontal temporal multi-frame capture.
 *
 * The user is asked to do one thing — face the camera naturally — while this
 * collector accumulates quality-checked frames over a short window. A template
 * is only declared complete once enough frames have been accepted, they span a
 * minimum time (so a single lucky frame cannot finish enrollment) and their
 * embeddings are mutually consistent.
 *
 * Robustness rules:
 *  - A rejected frame never clears the window: a blink or a moment of motion
 *    must not restart the whole attempt.
 *  - Once enough frames exist, mutually inconsistent (outlier) frames are
 *    dropped and only the consistent remainder is kept, so one bad sample does
 *    not poison an otherwise good capture.
 *  - On completion the window is latched: later frames cannot change or extend
 *    the result, so a repeated stream never yields a second, uncontrolled set.
 *
 * It is pure and holds no Android state; [reset] starts a fresh attempt.
 */
class FrontalEnrollmentCollector(
    private val config: EnrollmentQualityConfig = EnrollmentQualityConfig(),
    private val gate: EnrollmentQualityGate = EnrollmentQualityGate(config),
) {

    private data class Sample(val frame: EnrollmentFrame, val score: Float)

    private val window = ArrayDeque<Sample>()
    private var lastRejection: EnrollmentRejection? = null
    private var completed: List<EnrollmentFrame>? = null

    /** Offers one frame and returns the resulting progress snapshot. */
    fun onFrame(frame: EnrollmentFrame): EnrollmentProgress {
        completed?.let { return successProgress(it) }

        when (val verdict = gate.evaluate(frame)) {
            is EnrollmentVerdict.Rejected -> {
                lastRejection = verdict.reason
                evictOlderThan(frame.timestampMs - config.windowMs)
                return progress()
            }

            is EnrollmentVerdict.Accepted -> {
                lastRejection = null
                window.addLast(Sample(frame, verdict.score))
                evictOlderThan(frame.timestampMs - config.windowMs)
                if (pruneOutliers()) lastRejection = EnrollmentRejection.INCONSISTENT_SAMPLES
            }
        }

        val span = span()
        if (window.size >= config.framesRequired && span >= config.minSpanMs) {
            val selected = selectBest()
            return successProgress(selected).also { completed = selected }
        }
        return progress()
    }

    /** The samples selected so far, best-first by timestamp; empty until complete. */
    fun selected(): List<EnrollmentFrame> = completed ?: emptyList()

    fun outcome(): EnrollmentOutcome =
        completed?.let { EnrollmentOutcome.Complete(it) } ?: EnrollmentOutcome.Incomplete

    fun reset() {
        window.clear()
        lastRejection = null
        completed = null
    }

    // ------------------------------------------------------------------ window

    private fun evictOlderThan(thresholdMs: Long) {
        while (window.isNotEmpty() && window.first().frame.timestampMs < thresholdMs) {
            window.removeFirst()
        }
    }

    /**
     * Drops frames whose embedding disagrees with the rest, but only once there
     * are enough frames to judge. Dropping is preferred over clearing so a single
     * outlier costs one sample, not the whole attempt.
     */
    private fun pruneOutliers(): Boolean {
        if (window.size < config.framesRequired) return false
        val consistent = consistentSubset(window.toList())
        if (consistent.size == window.size) return false
        window.clear()
        window.addAll(consistent)
        return true
    }

    /**
     * The largest mutually-consistent remainder: a frame stays only if its mean
     * cosine similarity to the others reaches [EnrollmentQualityConfig.consistencyThreshold].
     * Reuses the Recognizer's shared cosine, so enrollment and recognition agree
     * on what "similar" means.
     */
    private fun consistentSubset(samples: List<Sample>): List<Sample> {
        if (samples.size < 2) return samples
        return samples.filter { candidate ->
            var sum = 0.0
            var count = 0
            for (other in samples) {
                if (other === candidate) continue
                sum += similarity(candidate, other)
                count++
            }
            count == 0 || (sum / count) >= config.consistencyThreshold
        }
    }

    private fun similarity(a: Sample, b: Sample): Double {
        val va = a.frame.embedding ?: return 0.0
        val vb = b.frame.embedding ?: return 0.0
        return CosineSimilarity.of(va, vb)
    }

    private fun span(): Long =
        if (window.size < 2) 0L
        else window.last().frame.timestampMs - window.first().frame.timestampMs

    /** Highest-scoring [EnrollmentQualityConfig.framesToKeep] frames, chronologically. */
    private fun selectBest(): List<EnrollmentFrame> = window
        .sortedByDescending { it.score }
        .take(config.framesToKeep)
        .sortedBy { it.frame.timestampMs }
        .map { it.frame }

    // ----------------------------------------------------------------- progress

    private fun progress(): EnrollmentProgress {
        val span = span()
        val stage = when {
            lastRejection != null -> stageFor(lastRejection!!)
            window.size >= config.framesRequired && span >= config.minSpanMs ->
                EnrollmentStage.VALIDATING
            else -> EnrollmentStage.STABILIZING
        }
        return EnrollmentProgress(
            stage = stage,
            rejection = lastRejection,
            acceptedFrames = window.size,
            requiredFrames = config.framesRequired,
            stableMs = span,
            complete = false,
        )
    }

    private fun successProgress(frames: List<EnrollmentFrame>) = EnrollmentProgress(
        stage = EnrollmentStage.SUCCESS,
        rejection = null,
        acceptedFrames = config.framesRequired,
        requiredFrames = config.framesRequired,
        stableMs = frames.maxOfOrNull { it.timestampMs }?.minus(
            frames.minOfOrNull { it.timestampMs } ?: 0L,
        ) ?: 0L,
        complete = true,
    )

    private fun stageFor(rejection: EnrollmentRejection): EnrollmentStage = when (rejection) {
        EnrollmentRejection.NO_FACE,
        EnrollmentRejection.MULTIPLE_FACES,
        -> EnrollmentStage.SEARCHING

        EnrollmentRejection.TOO_SMALL,
        EnrollmentRejection.TOO_CLOSE,
        EnrollmentRejection.OFF_CENTER,
        -> EnrollmentStage.POSITIONING

        EnrollmentRejection.NOT_FRONTAL -> EnrollmentStage.FRONTAL_REQUIRED

        EnrollmentRejection.TOO_DARK,
        EnrollmentRejection.TOO_BRIGHT,
        EnrollmentRejection.BLURRY,
        EnrollmentRejection.OCCLUDED,
        EnrollmentRejection.INVALID_EMBEDDING,
        -> EnrollmentStage.QUALITY_CHECK

        EnrollmentRejection.INCONSISTENT_SAMPLES -> EnrollmentStage.VALIDATING
    }
}
