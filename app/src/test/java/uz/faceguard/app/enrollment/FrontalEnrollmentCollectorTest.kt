package uz.faceguard.app.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.enrollment.EnrollmentFrame
import uz.faceguard.app.domain.enrollment.EnrollmentOutcome
import uz.faceguard.app.domain.enrollment.EnrollmentQualityConfig
import uz.faceguard.app.domain.enrollment.EnrollmentRejection
import uz.faceguard.app.domain.enrollment.EnrollmentStage
import uz.faceguard.app.domain.enrollment.FrontalEnrollmentCollector
import uz.faceguard.app.enrollment.EnrollmentFixtures.frame
import uz.faceguard.app.enrollment.EnrollmentFixtures.similar
import uz.faceguard.app.enrollment.EnrollmentFixtures.unit
import uz.faceguard.app.enrollment.EnrollmentFixtures.vector

/**
 * Phase 12 (enrollment): the temporal multi-frame capture.
 *
 * A 5-frame window over >= 1.2s of near-identical embeddings is the baseline for
 * "complete". Every test drives the collector with deterministic frames and
 * timestamps, so nothing depends on wall-clock time or a real camera.
 */
class FrontalEnrollmentCollectorTest {

    private val config = EnrollmentQualityConfig(
        framesRequired = 5,
        framesToKeep = 5,
        minSpanMs = 1_000L,
        windowMs = 4_000L,
        consistencyThreshold = 0.45f,
        expectedEmbeddingDimension = 0,
        requireUnitNorm = false,
    )

    private fun collector() = FrontalEnrollmentCollector(config)

    /** Feeds [count] quality frames 300 ms apart with mutually consistent embeddings. */
    private fun feedConsistent(
        collector: FrontalEnrollmentCollector,
        count: Int,
        startMs: Long = 0L,
        stepMs: Long = 300L,
        dimension: Int = 8,
        seed: Int = 42,
    ): List<EnrollmentFrame> {
        val base = vector(dimension, seed)
        return (0 until count).map { index ->
            val f = frame(
                timestampMs = startMs + index * stepMs,
                embedding = similar(base, delta = 0.02f),
            )
            collector.onFrame(f)
            f
        }
    }

    // 21. one valid frame alone -> enrollment incomplete
    @Test
    fun oneFrameAloneIsIncomplete() {
        val collector = collector()
        val progress = collector.onFrame(frame(timestampMs = 0L))

        assertFalse(progress.complete)
        assertNotEquals(EnrollmentStage.SUCCESS, progress.stage)
        assertEquals(EnrollmentOutcome.Incomplete, collector.outcome())
        assertTrue(collector.selected().isEmpty())
    }

    // 22. insufficient stable frames -> enrollment incomplete
    @Test
    fun fewFramesAreIncomplete() {
        val collector = collector()
        feedConsistent(collector, count = 4)

        assertFalse(collector.outcome() is EnrollmentOutcome.Complete)
        assertTrue(collector.selected().isEmpty())
    }

    @Test
    fun enoughFramesButTooShortASpanIsIncomplete() {
        val collector = collector()
        // Five frames, but all within 200 ms: not yet a *stable* hold.
        feedConsistent(collector, count = 5, stepMs = 50L)

        assertFalse(collector.outcome() is EnrollmentOutcome.Complete)
    }

    // 23. enough stable quality frames -> capture complete
    @Test
    fun enoughStableFramesComplete() {
        val collector = collector()
        var progress = collector.onFrame(frame(timestampMs = 0L))
        repeat(5) { index -> progress = collector.onFrame(frame(timestampMs = (index + 1) * 300L)) }

        assertTrue(progress.complete)
        assertEquals(EnrollmentStage.SUCCESS, progress.stage)
        assertEquals(1f, progress.progressFraction)
        assertTrue(collector.outcome() is EnrollmentOutcome.Complete)
    }

    // 24. inconsistent embeddings -> outlier rejected / re-capture
    @Test
    fun oneOutlierIsRejectedWithoutRestartingTheAttempt() {
        val collector = collector()
        // Explicit orthogonal basis: the outlier's cosine to the good frames is 0.
        val base = unit(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val outlier = unit(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)

        // Four consistent frames.
        (0 until 4).forEach { index ->
            collector.onFrame(frame(timestampMs = index * 300L, embedding = similar(base, 0.02f)))
        }
        val outlierProgress = collector.onFrame(frame(timestampMs = 1_200L, embedding = outlier))

        assertFalse("an outlier must not complete enrollment", outlierProgress.complete)
        assertEquals(EnrollmentRejection.INCONSISTENT_SAMPLES, outlierProgress.rejection)
        assertEquals(EnrollmentStage.VALIDATING, outlierProgress.stage)
        // The four consistent frames were kept, not thrown away.
        assertEquals(4, outlierProgress.acceptedFrames)

        // Adding one more consistent frame completes with the good samples only.
        val done = collector.onFrame(frame(timestampMs = 1_500L, embedding = similar(base, 0.02f)))
        assertTrue(done.complete)
        assertTrue(collector.selected().size == 5)
    }

    // 25. consistent embeddings -> enrollment complete
    @Test
    fun consistentEmbeddingsComplete() {
        val collector = collector()
        feedConsistent(collector, count = 5)

        assertTrue(collector.outcome() is EnrollmentOutcome.Complete)
        assertEquals(5, collector.selected().size)
    }

    @Test
    fun mutuallyInconsistentSamplesNeverComplete() {
        val collector = collector()
        // Five frames each pointing somewhere different: no consistent core exists.
        for (index in 0 until 5) {
            val embedding = FloatArray(5) { if (it == index) 1f else 0f }
            collector.onFrame(frame(timestampMs = index * 300L, embedding = embedding))
        }

        assertFalse(collector.outcome() is EnrollmentOutcome.Complete)
        assertTrue(collector.selected().isEmpty())
    }

    // 28. enrollment success only after validation
    @Test
    fun successIsOnlyReportedOnACompleteValidatedWindow() {
        val collector = collector()
        val seen = mutableListOf<Boolean>()
        repeat(9) { index ->
            seen += collector.onFrame(frame(timestampMs = index * 300L)).complete
        }
        // Complete only once, and never before the required frame count/span.
        assertEquals(listOf(false, false, false, false, true, true, true, true, true), seen)
    }

    // 29. failed quality never produces SUCCESS
    @Test
    fun badFramesNeverReachSuccess() {
        val collector = collector()
        var last = collector.onFrame(frame(faceCount = 0))
        repeat(20) { index ->
            last = when (index % 3) {
                0 -> collector.onFrame(frame(timestampMs = index * 300L, brightness = 0.02f))
                1 -> collector.onFrame(frame(timestampMs = index * 300L, yawDegrees = 40f))
                else -> collector.onFrame(frame(timestampMs = index * 300L, faceCount = 2))
            }
        }

        assertFalse(last.complete)
        assertNotEquals(EnrollmentStage.SUCCESS, last.stage)
        assertEquals(EnrollmentOutcome.Incomplete, collector.outcome())
    }

    // 30. repeated frames do not create duplicate uncontrolled templates
    @Test
    fun completionLatchesAndCannotBeExtendedByFurtherFrames() {
        val collector = collector()
        feedConsistent(collector, count = 5)
        val first = collector.selected()
        assertEquals(5, first.size)

        // The stream keeps running after success.
        repeat(20) { index ->
            collector.onFrame(frame(timestampMs = 2_000L + index * 300L, embedding = vector(8, seed = 99)))
        }

        assertEquals("selection must be latched", first, collector.selected())
        assertTrue(collector.outcome() is EnrollmentOutcome.Complete)
        assertEquals(5, collector.selected().size)
    }

    @Test
    fun selectionIsBoundedByFramesToKeep() {
        val bounded = FrontalEnrollmentCollector(config.copy(framesRequired = 3, framesToKeep = 3))
        feedConsistent(bounded, count = 30, stepMs = 100L)

        assertTrue(bounded.outcome() is EnrollmentOutcome.Complete)
        assertEquals(3, bounded.selected().size)
    }

    @Test
    fun staleFramesAreEvictedFromTheRollingWindow() {
        val collector = collector()
        feedConsistent(collector, count = 4, stepMs = 300L)

        // A long gap (beyond windowMs) with one fresh good frame drops the old ones.
        val progress = collector.onFrame(frame(timestampMs = 10_000L, embedding = vector(8, seed = 7)))

        assertFalse(progress.complete)
        assertEquals(1, progress.acceptedFrames)
    }

    @Test
    fun resetStartsAFreshAttempt() {
        val collector = collector()
        feedConsistent(collector, count = 5)
        assertTrue(collector.outcome() is EnrollmentOutcome.Complete)

        collector.reset()

        assertEquals(EnrollmentOutcome.Incomplete, collector.outcome())
        assertTrue(collector.selected().isEmpty())

        feedConsistent(collector, count = 5, startMs = 100_000L)
        assertTrue(collector.outcome() is EnrollmentOutcome.Complete)
    }

    @Test
    fun aRejectedFrameDoesNotClearTheAccumulatedWindow() {
        val collector = collector()
        val base = unit(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        (0 until 3).forEach { index ->
            collector.onFrame(frame(timestampMs = index * 300L, embedding = similar(base, 0.02f)))
        }

        // One blink/motion frame arrives.
        val blink = collector.onFrame(frame(timestampMs = 1_000L, faceCount = 0))

        assertEquals(EnrollmentRejection.NO_FACE, blink.rejection)
        assertEquals(EnrollmentStage.SEARCHING, blink.stage)
        assertEquals("the good window must survive", 3, blink.acceptedFrames)
    }

    @Test
    fun requiredCountAndProgressAreReported() {
        val collector = collector()
        val progress = collector.onFrame(frame(timestampMs = 0L))

        assertEquals(5, progress.requiredFrames)
        assertEquals(1, progress.acceptedFrames)
        assertEquals(0.2f, progress.progressFraction)
        assertEquals(EnrollmentStage.STABILIZING, progress.stage)
    }

    @Test
    fun configRejectsNonsensicalValues() {
        assertThrowsIllegalArgument { EnrollmentQualityConfig(framesRequired = 0) }
        assertThrowsIllegalArgument { EnrollmentQualityConfig(framesRequired = 3, framesToKeep = 4) }
        assertThrowsIllegalArgument { EnrollmentQualityConfig(windowMs = 100L, minSpanMs = 1_000L) }
        assertThrowsIllegalArgument { EnrollmentQualityConfig(maxBrightness = 0.1f, minBrightness = 0.5f) }
        assertThrowsIllegalArgument { EnrollmentQualityConfig(maxFaceWidthRatio = 0.1f) }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("expected IllegalArgumentException", thrown)
    }
}
