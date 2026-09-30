package uz.faceguard.app.eyesafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyEvaluator
import uz.faceguard.app.domain.eyesafety.EyeSafetyFrame
import uz.faceguard.app.domain.eyesafety.EyeSafetyResult
import uz.faceguard.app.domain.eyesafety.TemporalEyeSafetyDetector
import uz.faceguard.app.domain.policy.EyeSafetyState

/**
 * Phase 6 Step 1 (pure JVM): classification, hysteresis, persistence, the no-face contract and the
 * presence requirement.
 *
 * These drive the *production* [TemporalEyeSafetyDetector] and [EyeSafetyEvaluator]; the algorithm
 * is never re-implemented here.
 */
class EyeSafetyDetectionTest {

    /** The documented thresholds, with persistence reduced so classification is isolated. */
    private val immediate = EyeSafetyConfig.DEFAULT.copy(confirmFrames = 1)

    /** The documented thresholds with the documented persistence. */
    private val confirming = EyeSafetyConfig.DEFAULT

    private fun detector(config: EyeSafetyConfig = immediate) = TemporalEyeSafetyDetector(config)

    /** [count] measured frames with strictly increasing timestamps. */
    private fun frames(ratio: Float, count: Int, startMs: Long = 100L, stepMs: Long = 100L): List<EyeSafetyFrame> =
        (0 until count).map { index -> EyeSafetyFrame.measured(startMs + index * stepMs, ratio) }

    private fun noFaceFrames(count: Int, startMs: Long = 100L, stepMs: Long = 100L): List<EyeSafetyFrame> =
        (0 until count).map { index -> EyeSafetyFrame.noFace(startMs + index * stepMs) }

    private fun state(ratios: List<Float>, previous: EyeSafetyState, config: EyeSafetyConfig = immediate) =
        detector(config).detect(
            ratios.mapIndexed { index, ratio -> EyeSafetyFrame.measured(100L + index * 100L, ratio) },
            previous,
        ).state

    // ---- D. classification ---------------------------------------------------

    @Test
    fun aSmallFaceIsSafe() {
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.10f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun aFaceAtTheWarningEnterThresholdIsAWarning() {
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.30f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun aFaceJustBelowTheWarningEnterThresholdIsSafe() {
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.29f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun aFaceInsideTheWarningBandIsAWarning() {
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.35f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun aFaceJustBelowTheDangerEnterThresholdIsAWarning() {
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.39f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun aFaceAtTheDangerEnterThresholdIsDanger() {
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.40f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun aVeryLargeFaceIsDanger() {
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.45f), EyeSafetyState.UNKNOWN))
        assertEquals(EyeSafetyState.DANGER, state(listOf(1f), EyeSafetyState.UNKNOWN))
    }

    @Test
    fun theResultReportsTheRatioAndTheObservedFrameCount() {
        val result = detector().detect(frames(0.45f, 3), EyeSafetyState.UNKNOWN)

        assertEquals(EyeSafetyState.DANGER, result.state)
        assertEquals(0.45f, result.ratio)
        assertEquals(3, result.observedFrames)
        assertEquals(300L, result.timestampMs)
    }

    @Test
    fun aDisabledConfigNeverClassifies() {
        val disabled = immediate.copy(enabled = false)

        assertEquals(EyeSafetyState.UNKNOWN, state(listOf(0.90f), EyeSafetyState.DANGER, disabled))
    }

    // ---- E. hysteresis -------------------------------------------------------

    @Test
    fun enteringWarningUsesTheWarningEnterThreshold() {
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.30f), EyeSafetyState.SAFE))
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.29f), EyeSafetyState.SAFE))
    }

    @Test
    fun anExistingWarningIsLeftOnlyBelowTheWarningExitThreshold() {
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.28f), EyeSafetyState.WARNING))
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.26f), EyeSafetyState.WARNING))
    }

    @Test
    fun enteringDangerUsesTheDangerEnterThreshold() {
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.40f), EyeSafetyState.WARNING))
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.39f), EyeSafetyState.WARNING))
    }

    @Test
    fun anExistingDangerIsHeldInsideItsExitBand() {
        // The contract's own example: at 0.36 a DANGER stays DANGER because 0.36 >= dangerExit 0.35.
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.36f), EyeSafetyState.DANGER))
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.35f), EyeSafetyState.DANGER))
    }

    @Test
    fun leavingDangerFallsToWarningBeforeSafe() {
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.34f), EyeSafetyState.DANGER))
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.29f), EyeSafetyState.DANGER))
    }

    @Test
    fun aGrowingWarningEscalatesToDangerDespiteTheExitBand() {
        // The warning "stay" must not swallow a ratio that has grown past the danger enter threshold.
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.50f), EyeSafetyState.WARNING))
    }

    @Test
    fun theDeadBandPreservesTheCurrentState() {
        // Between warningExit (0.27) and warningEnter (0.30): the state does not move either way.
        assertEquals(EyeSafetyState.WARNING, state(listOf(0.28f), EyeSafetyState.WARNING))
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.28f), EyeSafetyState.SAFE))
    }

    @Test
    fun aJitterInsideTheDeadBandDoesNotOscillate() {
        val jitter = listOf(0.28f, 0.31f, 0.29f, 0.33f, 0.28f, 0.32f)

        val states = jitter.map { state(listOf(it), EyeSafetyState.WARNING) }

        assertTrue("expected the state to stay WARNING, was $states", states.all { it == EyeSafetyState.WARNING })
    }

    @Test
    fun aJitterInsideTheDangerBandDoesNotOscillate() {
        val jitter = listOf(0.41f, 0.36f, 0.44f, 0.37f, 0.42f)

        val states = jitter.map { state(listOf(it), EyeSafetyState.DANGER) }

        assertTrue("expected the state to stay DANGER, was $states", states.all { it == EyeSafetyState.DANGER })
    }

    // ---- F. persistence ------------------------------------------------------

    @Test
    fun oneCloseFrameIsNotEnoughToConfirmDanger() {
        assertEquals(EyeSafetyState.UNKNOWN, state(listOf(0.45f), EyeSafetyState.UNKNOWN, confirming))
    }

    @Test
    fun twoCloseFramesAreNotEnoughToConfirmDanger() {
        assertEquals(EyeSafetyState.UNKNOWN, state(listOf(0.45f, 0.45f), EyeSafetyState.UNKNOWN, confirming))
    }

    @Test
    fun threeAgreeingCloseFramesConfirmDanger() {
        assertEquals(EyeSafetyState.DANGER, state(listOf(0.45f, 0.45f, 0.45f), EyeSafetyState.UNKNOWN, confirming))
    }

    @Test
    fun disagreeingEvidenceDoesNotChangeTheState() {
        // A single close frame between far ones is not a transition.
        assertEquals(EyeSafetyState.UNKNOWN, state(listOf(0.45f, 0.20f, 0.20f), EyeSafetyState.UNKNOWN, confirming))
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.45f, 0.20f, 0.20f), EyeSafetyState.SAFE, confirming))
    }

    @Test
    fun threeAgreeingFarFramesLeaveDanger() {
        assertEquals(EyeSafetyState.SAFE, state(listOf(0.20f, 0.20f, 0.20f), EyeSafetyState.DANGER, confirming))
    }

    @Test
    fun aConfirmedStateSurvivesFurtherAgreeingFrames() {
        val evaluator = EyeSafetyEvaluator(confirming)
        (1..3).forEach { evaluator.observe(EyeSafetyFrame.measured(it * 100L, 0.45f)) }
        assertEquals(EyeSafetyState.DANGER, evaluator.result(300L).state)

        // More of the same keeps it there, and more frames still cannot flip it.
        (4..8).forEach { evaluator.observe(EyeSafetyFrame.measured(it * 100L, 0.45f)) }
        assertEquals(EyeSafetyState.DANGER, evaluator.result(800L).state)
        assertEquals(EyeSafetyState.DANGER, evaluator.currentState)
    }

    @Test
    fun persistenceCountsObservedFramesNotElapsedTime() {
        // Identical frames are equally confirming however slowly they arrive: the contract is a
        // frame count, never a duration, because frames only arrive while a scan window is open.
        val slow = state(listOf(0.45f, 0.45f, 0.45f), EyeSafetyState.UNKNOWN, confirming)
        val fastFrames = (1..3).map { EyeSafetyFrame.measured(it.toLong(), 0.45f) }
        val fast = detector(confirming).detect(fastFrames, EyeSafetyState.UNKNOWN).state

        assertEquals(slow, fast)
        assertEquals(EyeSafetyState.DANGER, slow)
    }

    // ---- G. no face ----------------------------------------------------------

    @Test
    fun noFaceIsNeverDanger() {
        val result = detector(confirming).detect(noFaceFrames(10), EyeSafetyState.UNKNOWN)

        assertEquals(EyeSafetyState.UNKNOWN, result.state)
        assertNull(result.ratio)
    }

    @Test
    fun noFaceIsNeverReadAsADistance() {
        // A stream of no-face frames must not be interpreted as "the child is far away" — there is
        // simply no measurement at all.
        val result = detector(immediate).detect(noFaceFrames(5), EyeSafetyState.SAFE)

        assertEquals(EyeSafetyState.UNKNOWN, result.state)
        assertNull(result.ratio)
    }

    @Test
    fun noFaceDropsAnExistingDanger() {
        val result = detector(confirming).detect(noFaceFrames(10), EyeSafetyState.DANGER)

        assertEquals(EyeSafetyState.UNKNOWN, result.state)
        assertNull(result.ratio)
    }

    @Test
    fun framesWithoutAFaceAreNeverClassifiedAsClose() {
        // Ratio 0f stored on a no-face frame must not be read as a measurement of any kind.
        val result = detector(immediate).detect(noFaceFrames(3), EyeSafetyState.UNKNOWN)

        assertTrue(result.ratio == null)
        assertEquals(EyeSafetyState.UNKNOWN, result.state)
        assertEquals(EyeSafetyState.UNKNOWN, EyeSafetyResult.UNKNOWN.state)
    }

    @Test
    fun anInvalidRatioIsNeitherSafeNorDanger() {
        val frames = (1..3).map { EyeSafetyFrame(it * 100L, facePresent = true, faceWidthRatio = Float.NaN) }

        val result = detector(confirming).detect(frames, EyeSafetyState.UNKNOWN)

        assertEquals(EyeSafetyState.UNKNOWN, result.state)
        assertNull(result.ratio)
    }

    @Test
    fun aZeroWidthFaceIsNotAnObservation() {
        val frames = (1..3).map { EyeSafetyFrame(it * 100L, facePresent = true, faceWidthRatio = 0f) }

        assertEquals(EyeSafetyState.UNKNOWN, detector(confirming).detect(frames, EyeSafetyState.UNKNOWN).state)
    }

    @Test
    fun anOutOfRangeRatioCannotManufactureDanger() {
        val frames = (1..3).map { EyeSafetyFrame(it * 100L, facePresent = true, faceWidthRatio = 5f) }

        assertEquals(EyeSafetyState.UNKNOWN, detector(confirming).detect(frames, EyeSafetyState.UNKNOWN).state)
    }

    // ---- H. presence ratio ---------------------------------------------------

    @Test
    fun enoughFaceObservationsAllowClassification() {
        val frames = listOf(
            EyeSafetyFrame.measured(100L, 0.45f),
            EyeSafetyFrame.noFace(200L),
            EyeSafetyFrame.measured(300L, 0.45f),
            EyeSafetyFrame.measured(400L, 0.45f),
        )

        // 3 of 4 frames carry a face (0.75 >= 0.5) and 3 usable observations confirm DANGER.
        assertEquals(EyeSafetyState.DANGER, detector(confirming).detect(frames, EyeSafetyState.UNKNOWN).state)
    }

    @Test
    fun insufficientFaceObservationsReportUnknown() {
        val frames = listOf(
            EyeSafetyFrame.measured(100L, 0.45f),
            EyeSafetyFrame.noFace(200L),
            EyeSafetyFrame.noFace(300L),
            EyeSafetyFrame.noFace(400L),
        )

        // 1 of 4 (0.25) is below the required presence, so even a very close face proves nothing.
        assertEquals(EyeSafetyState.UNKNOWN, detector(confirming).detect(frames, EyeSafetyState.UNKNOWN).state)
    }

    @Test
    fun exactlyTheMinimumPresenceRatioIsAccepted() {
        val config = confirming.copy(confirmFrames = 2)
        val frames = listOf(
            EyeSafetyFrame.measured(100L, 0.45f),
            EyeSafetyFrame.measured(200L, 0.45f),
            EyeSafetyFrame.noFace(300L),
            EyeSafetyFrame.noFace(400L),
        )

        // 2 of 4 is exactly 0.5, which meets the requirement rather than falling short of it.
        assertEquals(EyeSafetyState.DANGER, detector(config).detect(frames, EyeSafetyState.UNKNOWN).state)
    }

    @Test
    fun justBelowTheMinimumPresenceRatioIsRejected() {
        val config = confirming.copy(confirmFrames = 2)
        val frames = listOf(
            EyeSafetyFrame.measured(100L, 0.45f),
            EyeSafetyFrame.noFace(200L),
            EyeSafetyFrame.noFace(300L),
        )

        // 1 of 3 (0.33) is below 0.5 even though the observed face is very close.
        assertEquals(EyeSafetyState.UNKNOWN, detector(config).detect(frames, EyeSafetyState.UNKNOWN).state)
    }

    @Test
    fun anEmptyWindowReportsUnknown() {
        val result = detector(confirming).detect(emptyList(), EyeSafetyState.DANGER)

        assertEquals(EyeSafetyState.UNKNOWN, result.state)
        assertEquals(0, result.observedFrames)
        assertNull(result.ratio)
    }

    @Test
    fun insufficientEvidenceDropsEvenAConfirmedDanger() {
        // Documented consequence of "enough evidence" step 3: with fewer usable observations than
        // confirmFrames the engine reports UNKNOWN rather than holding its previous verdict. It is
        // a "no longer known" answer, not a claim that the child moved away.
        val oneFrame = listOf(EyeSafetyFrame.measured(100L, 0.45f))

        assertEquals(EyeSafetyState.UNKNOWN, detector(confirming).detect(oneFrame, EyeSafetyState.DANGER).state)
        assertEquals(EyeSafetyState.UNKNOWN, detector(confirming).detect(oneFrame, EyeSafetyState.SAFE).state)
    }

    // ---- evaluator lifecycle -------------------------------------------------

    @Test
    fun staleFramesDecayToUnknown() {
        val evaluator = EyeSafetyEvaluator(confirming)
        (1..3).forEach { evaluator.observe(EyeSafetyFrame.measured(it * 100L, 0.45f)) }
        assertEquals(EyeSafetyState.DANGER, evaluator.result(300L).state)

        // The camera stopped: 2.5s later every observation has aged out of the window.
        assertEquals(EyeSafetyState.UNKNOWN, evaluator.result(10_000L).state)
        assertEquals(EyeSafetyState.UNKNOWN, evaluator.currentState)
    }

    @Test
    fun observingAndResolvingRepeatedlyIsStable() {
        val evaluator = EyeSafetyEvaluator(confirming)
        (1..3).forEach { evaluator.observe(EyeSafetyFrame.measured(it * 100L, 0.45f)) }

        val first = evaluator.result(300L)
        repeat(5) { assertEquals(first, evaluator.result(300L)) }
    }

    // ---- K. property-like invariants ----------------------------------------

    @Test
    fun aLargerRatioIsNeverLessRestrictive() {
        val samples = listOf(0.05f, 0.20f, 0.28f, 0.30f, 0.35f, 0.39f, 0.40f, 0.45f, 0.80f)

        EyeSafetyState.entries.forEach { previous ->
            samples.zipWithNext { lower, higher ->
                val lowerState = state(listOf(lower), previous)
                val higherState = state(listOf(higher), previous)
                assertTrue(
                    "ratio $higher gave $higherState but ratio $lower gave $lowerState (from $previous)",
                    rank(higherState) >= rank(lowerState),
                )
            }
        }
    }

    @Test
    fun repeatedIdenticalFramesDoNotChangeTheState() {
        val evaluator = EyeSafetyEvaluator(confirming)
        (1..20).forEach { evaluator.observe(EyeSafetyFrame.measured(it * 50L, 0.45f)) }

        val state = evaluator.result(1_000L).state
        repeat(10) { assertEquals(state, evaluator.result(1_000L).state) }
        assertEquals(EyeSafetyState.DANGER, state)
    }

    private fun rank(state: EyeSafetyState): Int = when (state) {
        EyeSafetyState.UNKNOWN -> -1
        EyeSafetyState.SAFE -> 0
        EyeSafetyState.WARNING -> 1
        EyeSafetyState.DANGER -> 2
    }
}
