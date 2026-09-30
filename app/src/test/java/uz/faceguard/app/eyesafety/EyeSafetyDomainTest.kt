package uz.faceguard.app.eyesafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyEvaluator
import uz.faceguard.app.domain.eyesafety.EyeSafetyFrame
import uz.faceguard.app.domain.eyesafety.EyeSafetyWindow
import uz.faceguard.app.domain.eyesafety.TemporalEyeSafetyDetector
import uz.faceguard.app.domain.policy.EyeSafetyState

/**
 * Phase 6 Step 1 (pure JVM): the eye-safety value objects, configuration invariants, the temporal
 * window and the evaluator's lifecycle. No Android, no database, no UI.
 */
class EyeSafetyDomainTest {

    // ---- A. configuration validation ----------------------------------------

    @Test
    fun validConfigUsesTheDocumentedDefaults() {
        val config = EyeSafetyConfig.DEFAULT

        assertTrue(config.enabled)
        assertEquals(0.30f, config.warningEnterThreshold)
        assertEquals(0.27f, config.warningExitThreshold)
        assertEquals(0.40f, config.dangerEnterThreshold)
        assertEquals(0.35f, config.dangerExitThreshold)
        assertEquals(3, config.confirmFrames)
        assertEquals(2_500L, config.maxWindowAgeMs)
        assertEquals(24, config.maxWindowFrames)
        assertEquals(0.5f, config.minimumPresenceRatio)
    }

    @Test
    fun anExplicitlyBuiltConfigIsAccepted() {
        val config = EyeSafetyConfig(
            enabled = false,
            warningEnterThreshold = 0.2f,
            warningExitThreshold = 0.15f,
            dangerEnterThreshold = 0.5f,
            dangerExitThreshold = 0.45f,
            confirmFrames = 1,
            maxWindowAgeMs = 1_000L,
            maxWindowFrames = 8,
            minimumPresenceRatio = 1.0f,
        )

        assertFalse(config.enabled)
        assertEquals(0.5f, config.dangerEnterThreshold)
    }

    @Test
    fun warningMustBeStrictlyBelowDanger() {
        assertThrows(IllegalArgumentException::class.java) {
            config(warningEnter = 0.4f, dangerEnter = 0.4f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(warningEnter = 0.5f, dangerEnter = 0.4f)
        }
    }

    @Test
    fun enterThresholdsMustBeInsideTheUnitInterval() {
        // 0 and 1 are not usable enter thresholds: they are not ratios a face can fill exclusively.
        assertThrows(IllegalArgumentException::class.java) { config(warningEnter = 0f) }
        assertThrows(IllegalArgumentException::class.java) { config(warningEnter = 1f) }
        assertThrows(IllegalArgumentException::class.java) { config(warningEnter = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { config(warningEnter = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { config(warningEnter = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { config(dangerEnter = 1f) }
    }

    @Test
    fun exitThresholdsMustNotExceedTheirEnterThresholds() {
        assertThrows(IllegalArgumentException::class.java) {
            config(warningEnter = 0.30f, warningExit = 0.31f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(dangerEnter = 0.40f, dangerExit = 0.41f)
        }
    }

    @Test
    fun dangerExitMustNotFallBelowWarningExit() {
        assertThrows(IllegalArgumentException::class.java) {
            config(warningEnter = 0.30f, warningExit = 0.25f, dangerEnter = 0.40f, dangerExit = 0.20f)
        }
    }

    @Test
    fun equalityOfExitAndEnterIsAllowedAndMeansNoHysteresis() {
        // The contract permits this explicitly: an equal exit threshold simply means the boundary
        // has no dead band. It must not be silently corrected to something else.
        val config = config(warningEnter = 0.30f, warningExit = 0.30f, dangerEnter = 0.40f, dangerExit = 0.30f)

        assertEquals(0.30f, config.warningExitThreshold)
        assertEquals(0.30f, config.dangerExitThreshold)
    }

    @Test
    fun temporalParametersMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { config(confirmFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(confirmFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { config(maxWindowAgeMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { config(maxWindowFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(maxWindowFrames = -5) }
    }

    @Test
    fun presenceRatioMustBeInsideTheUnitInterval() {
        assertThrows(IllegalArgumentException::class.java) { config(minimumPresenceRatio = 0f) }
        assertThrows(IllegalArgumentException::class.java) { config(minimumPresenceRatio = -0.5f) }
        assertThrows(IllegalArgumentException::class.java) { config(minimumPresenceRatio = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { config(minimumPresenceRatio = Float.NaN) }
    }

    @Test
    fun anInvalidConfigIsNeverSilentlyClamped() {
        // 0.50 must not quietly become 0.40 to satisfy the invariant.
        val failure = runCatching { config(warningEnter = 0.50f, dangerEnter = 0.40f) }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    private fun config(
        warningEnter: Float = 0.30f,
        warningExit: Float = 0.27f,
        dangerEnter: Float = 0.40f,
        dangerExit: Float = 0.35f,
        confirmFrames: Int = 3,
        maxWindowAgeMs: Long = 2_500L,
        maxWindowFrames: Int = 24,
        minimumPresenceRatio: Float = 0.5f,
    ) = EyeSafetyConfig(
        enabled = true,
        warningEnterThreshold = warningEnter,
        warningExitThreshold = warningExit,
        dangerEnterThreshold = dangerEnter,
        dangerExitThreshold = dangerExit,
        confirmFrames = confirmFrames,
        maxWindowAgeMs = maxWindowAgeMs,
        maxWindowFrames = maxWindowFrames,
        minimumPresenceRatio = minimumPresenceRatio,
    )

    // ---- B. frame ------------------------------------------------------------

    @Test
    fun aMeasuredFrameCarriesItsRatio() {
        val frame = EyeSafetyFrame.measured(timestampMs = 100L, faceWidthRatio = 0.35f)

        assertTrue(frame.facePresent)
        assertEquals(0.35f, frame.usableRatio)
        assertEquals(100L, frame.timestampMs)
    }

    @Test
    fun aNoFaceFrameCarriesNoMeasurement() {
        val frame = EyeSafetyFrame.noFace(timestampMs = 100L)

        assertFalse(frame.facePresent)
        assertNull(frame.usableRatio)
        assertEquals(0f, frame.faceWidthRatio)
    }

    @Test
    fun zeroRatioIsNotAMeasurementWhetherOrNotAFaceWasDetected() {
        // A zero ratio must never be read as "very far away" — it is an absent measurement.
        assertNull(EyeSafetyFrame(200L, facePresent = false, faceWidthRatio = 0f).usableRatio)
        assertNull(EyeSafetyFrame(200L, facePresent = true, faceWidthRatio = 0f).usableRatio)
    }

    @Test
    fun nonFiniteAndOutOfRangeRatiosCarryNoMeasurement() {
        assertNull(EyeSafetyFrame(1L, true, Float.NaN).usableRatio)
        assertNull(EyeSafetyFrame(1L, true, Float.POSITIVE_INFINITY).usableRatio)
        assertNull(EyeSafetyFrame(1L, true, Float.NEGATIVE_INFINITY).usableRatio)
        assertNull(EyeSafetyFrame(1L, true, -0.10f).usableRatio)
        assertNull(EyeSafetyFrame(1L, true, 1.01f).usableRatio)
        assertNull(EyeSafetyFrame(1L, true, 12f).usableRatio)
    }

    @Test
    fun aRatioOfExactlyOneIsAcceptedAsTheUpperBoundary() {
        assertEquals(1f, EyeSafetyFrame(1L, true, EyeSafetyFrame.MAX_RATIO).usableRatio)
    }

    @Test
    fun anInvalidRawRatioIsNeverReinterpreted() {
        // The raw value is preserved rather than clamped; only usability is derived from it.
        val frame = EyeSafetyFrame(1L, true, 3f)

        assertEquals(3f, frame.faceWidthRatio)
        assertNull(frame.usableRatio)
    }

    // ---- C. window -----------------------------------------------------------

    @Test
    fun anAddedFrameIsRetained() {
        val window = window()

        assertTrue(window.add(EyeSafetyFrame.measured(100L, 0.30f)))

        assertEquals(1, window.size)
        assertFalse(window.isEmpty())
        assertEquals(listOf(100L), window.snapshot(100L).map { it.timestampMs })
    }

    @Test
    fun chronologicalFramesAreRetainedInOrder() {
        val window = window()

        (1..5).forEach { window.add(EyeSafetyFrame.measured(it * 100L, 0.30f)) }

        assertEquals(listOf(100L, 200L, 300L, 400L, 500L), window.snapshot(500L).map { it.timestampMs })
    }

    @Test
    fun aDuplicateTimestampIsRejected() {
        val window = window()
        window.add(EyeSafetyFrame.measured(100L, 0.30f))
        window.add(EyeSafetyFrame.measured(200L, 0.30f))

        val accepted = window.add(EyeSafetyFrame.measured(200L, 0.90f))

        assertFalse(accepted)
        assertEquals(2, window.size)
        assertEquals(listOf(100L, 200L), window.snapshot(200L).map { it.timestampMs })
    }

    @Test
    fun anOutOfOrderTimestampIsRejected() {
        val window = window()
        window.add(EyeSafetyFrame.measured(100L, 0.30f))
        window.add(EyeSafetyFrame.measured(300L, 0.30f))

        val accepted = window.add(EyeSafetyFrame.measured(200L, 0.90f))

        assertFalse(accepted)
        assertEquals(listOf(100L, 300L), window.snapshot(300L).map { it.timestampMs })
    }

    @Test
    fun theOldestFramesAreEvictedWhenMaxFramesIsReached() {
        val window = EyeSafetyWindow(maxAgeMs = 10_000L, maxFrames = 3)

        (1..5).forEach { window.add(EyeSafetyFrame.measured(it * 100L, 0.30f)) }

        assertEquals(3, window.size)
        assertEquals(listOf(300L, 400L, 500L), window.snapshot(500L).map { it.timestampMs })
    }

    @Test
    fun framesOlderThanMaxAgeAreEvicted() {
        val window = EyeSafetyWindow(maxAgeMs = 1_000L, maxFrames = 24)
        (1..5).forEach { window.add(EyeSafetyFrame.measured(it * 100L, 0.30f)) }

        // 1.5s after the newest frame every observation is stale.
        assertEquals(emptyList<EyeSafetyFrame>(), window.snapshot(2_000L))
        assertEquals(0, window.size)
    }

    @Test
    fun anEmptyWindowSnapshotsNothing() {
        val window = window()

        assertTrue(window.isEmpty())
        assertEquals(0, window.size)
        assertEquals(emptyList<EyeSafetyFrame>(), window.snapshot(0L))
    }

    @Test
    fun clearingEmptiesTheWindow() {
        val window = window()
        (1..3).forEach { window.add(EyeSafetyFrame.measured(it * 100L, 0.30f)) }

        window.clear()

        assertEquals(0, window.size)
        assertEquals(emptyList<EyeSafetyFrame>(), window.snapshot(300L))
    }

    private fun window() = EyeSafetyWindow(maxAgeMs = 10_000L, maxFrames = 24)

    // ---- I. evaluator reset --------------------------------------------------

    @Test
    fun resetDropsBothTheWindowAndTheConfirmedState() {
        val evaluator = evaluator()
        (1..3).forEach { index -> evaluator.observe(EyeSafetyFrame.measured(index * 100L, 0.45f)) }
        assertEquals(EyeSafetyState.DANGER, evaluator.result(300L).state)

        evaluator.reset()

        assertEquals(EyeSafetyState.UNKNOWN, evaluator.currentState)
        assertEquals(0, evaluator.frameCount)
        assertEquals(EyeSafetyState.UNKNOWN, evaluator.result(300L).state)
    }

    @Test
    fun noStaleStateSurvivesAReset() {
        val evaluator = evaluator()
        (1..3).forEach { index -> evaluator.observe(EyeSafetyFrame.measured(index * 100L, 0.45f)) }
        assertEquals(EyeSafetyState.DANGER, evaluator.result(300L).state)

        evaluator.reset()
        // One close frame is not enough evidence again; the previous child's verdict is gone.
        evaluator.observe(EyeSafetyFrame.measured(1_000L, 0.45f))

        assertEquals(EyeSafetyState.UNKNOWN, evaluator.result(1_000L).state)
    }

    @Test
    fun aFreshEvaluatorStartsUnknown() {
        val evaluator = evaluator()

        assertEquals(EyeSafetyState.UNKNOWN, evaluator.currentState)
        assertEquals(0, evaluator.frameCount)
        assertEquals(EyeSafetyState.UNKNOWN, evaluator.result(0L).state)
    }

    private fun evaluator() = EyeSafetyEvaluator(EyeSafetyConfig.DEFAULT)

    // ---- J. determinism ------------------------------------------------------

    @Test
    fun theSameObservationSequenceProducesTheSameStates() {
        val ratios = listOf(0.10f, 0.32f, 0.32f, 0.45f, 0.45f, 0.45f, 0.20f, 0.20f, 0.20f)

        fun run(): List<EyeSafetyState> {
            val evaluator = evaluator()
            return ratios.mapIndexed { index, ratio ->
                evaluator.observe(EyeSafetyFrame.measured(index * 100L + 100L, ratio))
                evaluator.result(index * 100L + 100L).state
            }
        }

        assertEquals(run(), run())
    }

    @Test
    fun theDetectorIsAValueFunctionOfItsInputs() {
        val detector = TemporalEyeSafetyDetector(EyeSafetyConfig.DEFAULT)
        val frames = (1..3).map { EyeSafetyFrame.measured(it * 100L, 0.45f) }

        val first = detector.detect(frames, EyeSafetyState.UNKNOWN)
        val second = detector.detect(frames, EyeSafetyState.UNKNOWN)

        assertEquals(first, second)
    }
}
