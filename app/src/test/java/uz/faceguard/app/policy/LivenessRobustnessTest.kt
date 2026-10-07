package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.liveness.LivenessEvaluator
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.core.liveness.LivenessWindow
import uz.faceguard.app.core.liveness.TemporalLivenessDetector
import uz.faceguard.app.domain.policy.LivenessState

/**
 * Stage 3 (Anti-Spoof / Liveness): model-output robustness and temporal determinism.
 *
 * A liveness model must never *authorise* liveness from a malformed output, and the
 * temporal decision must be deterministic over an ordered observation window. These tests
 * use the pure detector/evaluator, so they run on the JVM with no camera.
 */
class LivenessRobustnessTest {

    private val detector = TemporalLivenessDetector()

    private fun frame(ts: Long, yaw: Float = 0f, score: Float? = null) =
        LivenessFrame(facePresent = true, yawDegrees = yaw, modelScore = score, timestamp = ts)

    private fun scored(score: Float) = listOf(frame(1, score = score), frame(2, score = score), frame(3, score = score))

    // ------------------------------------------------- non-finite model output

    @Test
    fun positiveInfinityModelOutputIsNeverLive() {
        val result = detector.detect(scored(Float.POSITIVE_INFINITY))
        assertNotEquals(LivenessState.LIVE, result.state)
        assertNotEquals(LivenessState.SPOOF, result.state)
    }

    @Test
    fun negativeInfinityModelOutputIsNeverLiveOrSpoof() {
        val result = detector.detect(scored(Float.NEGATIVE_INFINITY))
        assertNotEquals(LivenessState.LIVE, result.state)
        assertNotEquals(LivenessState.SPOOF, result.state)
    }

    @Test
    fun nanModelOutputIsNeverLiveOrSpoof() {
        val result = detector.detect(scored(Float.NaN))
        assertNotEquals(LivenessState.LIVE, result.state)
        assertNotEquals(LivenessState.SPOOF, result.state)
        assertNull(result.confidence)
    }

    @Test
    fun oneBadScoreAmongGoodOnesNeverProducesLive() {
        // A single non-finite value poisons the mean; the window must be undecided rather
        // than clamped up to LIVE (0.9 + 0.9 + +Inf = +Inf).
        val result = detector.detect(listOf(frame(1, score = 0.9f), frame(2, score = 0.9f), frame(3, score = Float.POSITIVE_INFINITY)))
        assertNotEquals(LivenessState.LIVE, result.state)
    }

    @Test
    fun aNonFiniteModelResultCarriesNoConfidence() {
        val result = detector.detect(scored(Float.NaN))
        assertNull("a malformed score must not surface a confidence", result.confidence)
    }

    // ------------------------------------------------- finite out-of-range

    @Test
    fun finiteOutOfRangeScoresAreClampedDeterministically() {
        // Preserved Stage 5 behaviour: finite, out-of-range scores clamp into [0,1].
        assertEquals(LivenessState.LIVE, detector.detect(scored(5f)).state)
        assertEquals(LivenessState.SPOOF, detector.detect(scored(-3f)).state)
    }

    @Test
    fun aModelLiveConfidenceIsFiniteAndBounded() {
        val live = detector.detect(scored(0.9f))
        assertEquals(LivenessState.LIVE, live.state)
        val c = live.confidence
        assertTrue(c != null && c.isFinite() && c in 0f..1f)
    }

    @Test
    fun aModelSpoofConfidenceIsFiniteAndBounded() {
        val spoof = detector.detect(scored(0.1f))
        assertEquals(LivenessState.SPOOF, spoof.state)
        val c = spoof.confidence
        assertTrue(c != null && c.isFinite() && c in 0f..1f)
    }

    // ------------------------------------------------- heuristic path

    @Test
    fun theHeuristicNeverEmitsSpoofAndNeverInventaConfidence() {
        val live = detector.detect(listOf(frame(1, yaw = 0f), frame(2, yaw = 9f), frame(3, yaw = 0f)))
        assertEquals(LivenessState.LIVE, live.state)
        assertEquals(LivenessSource.HEURISTIC, live.source)
        assertNull(live.confidence)

        val still = detector.detect(listOf(frame(1, yaw = 1f), frame(2, yaw = 1f), frame(3, yaw = 1f)))
        assertNotEquals(LivenessState.SPOOF, still.state)
    }

    @Test
    fun aStaticPhotoLikeSequenceIsNotLive() {
        // A perfectly static presentation (photo on the desk / held still) must never be LIVE.
        val result = detector.detect(listOf(frame(1, yaw = 0f), frame(2, yaw = 0f), frame(3, yaw = 0f)))
        assertNotEquals(LivenessState.LIVE, result.state)
    }

    // ------------------------------------------------- temporal determinism

    @Test
    fun theDetectorIsAPureFunctionOfTheOrderedWindow() {
        val window = listOf(frame(1, yaw = 0f), frame(2, yaw = 6f), frame(3, yaw = -6f))
        // Same ordered observations -> identical result every time (no hidden state).
        repeat(5) { assertEquals(detector.detect(window), detector.detect(window)) }
    }

    @Test
    fun theWindowRejectsOutOfOrderAndDuplicateFrames() {
        val window = LivenessWindow()
        assertTrue(window.add(frame(100)))
        assertEquals("a duplicate timestamp is rejected", false, window.add(frame(100)))
        assertEquals("an out-of-order frame is rejected", false, window.add(frame(50)))
        assertTrue(window.add(frame(200)))
        assertEquals(2, window.size)
    }

    @Test
    fun anIdleWindowDecaysToEmptyRatherThanLatchingLive() {
        val evaluator = LivenessEvaluator()
        evaluator.observe(frame(1_000, yaw = 6f))
        evaluator.observe(frame(1_100, yaw = -6f))
        evaluator.observe(frame(1_200, yaw = 6f))
        assertEquals(LivenessState.LIVE, evaluator.result(1_200).state)

        // Far in the future with no new frames, the evidence has aged out.
        assertEquals(LivenessState.UNKNOWN, evaluator.result(1_200 + 10_000).state)
    }

    @Test
    fun aNoFaceWindowIsNoFaceRegardlessOfOrder() {
        val frames = listOf(
            LivenessFrame(facePresent = false, timestamp = 1),
            LivenessFrame(facePresent = false, timestamp = 2),
            LivenessFrame(facePresent = false, timestamp = 3),
        )
        assertEquals(LivenessState.NO_FACE, detector.detect(frames).state)
    }
}
