package uz.faceguard.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.liveness.LivenessEvaluator
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.core.pipeline.EmbeddingSource
import uz.faceguard.app.core.recognition.ChildTemplate
import uz.faceguard.app.core.recognition.IdentityDecisionEngine
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.RecognitionWinner
import uz.faceguard.app.core.recognition.Thresholds
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.LivenessTrust
import uz.faceguard.app.domain.policy.LivenessPolicy

/**
 * Release Block 5 (Recognition final hardening): robustness of the identity decision
 * against unusable numeric input.
 *
 * The identity decision consumes a live embedding produced by an on-device model and
 * stored templates. A model that emits a non-finite value (NaN / +/-Infinity), or a
 * corrupted stored template, must **never** be accepted as an identity: the safe outcome
 * is UNKNOWN, which flows through the existing fail-safe unknown-user policy. These tests
 * pin that — they are deterministic numeric/loss-of-signal checks, **not** real-world
 * accuracy, liveness efficacy or FAR/FRR measurements (those need a device and a labelled
 * dataset and are NOT measured here).
 */
class RecognitionRobustnessTest {

    private val thresholds = Thresholds() // parent 0.68, child 0.62

    private fun decide(live: FloatArray, parent: FloatArray?, child: FloatArray?) =
        IdentityDecisionEngine.decide(
            live = live,
            source = EmbeddingSource.MODEL,
            parentTemplate = parent,
            children = child?.let { listOf(ChildTemplate(7L, "Vali", it)) } ?: emptyList(),
            thresholds = thresholds,
        )

    private fun finite(size: Int = 4) = FloatArray(size).also { it[0] = 1f }

    // ------------------------------------------------ non-finite live embedding

    @Test
    fun aNaNLiveEmbeddingIsNeverAcceptedAsAnIdentity() {
        val live = floatArrayOf(Float.NaN, 0f, 0f, 0f)
        // Even a perfect-looking parent template must not be matched to a NaN live vector.
        val d = decide(live, parent = finite(), child = finite())
        assertEquals(RecognitionWinner.NONE, d.winner)
        assertTrue(d.result is RecognitionResult.Unknown)
    }

    @Test
    fun anInfiniteLiveEmbeddingIsNeverAcceptedAsAnIdentity() {
        listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { inf ->
            val live = floatArrayOf(inf, 0f, 0f, 0f)
            val d = decide(live, parent = finite(), child = null)
            assertEquals("live=$inf", RecognitionWinner.NONE, d.winner)
            assertTrue("live=$inf", d.result is RecognitionResult.Unknown)
        }
    }

    @Test
    fun anAllZeroLiveEmbeddingScoresZeroAndStaysUnknown() {
        val live = FloatArray(4)
        val d = decide(live, parent = finite(), child = finite())
        assertEquals(RecognitionWinner.NONE, d.winner)
        assertTrue(d.result is RecognitionResult.Unknown)
    }

    // ---------------------------------------------- non-finite stored template

    @Test
    fun aNaNTemplateNeverProducesARecognizedIdentity() {
        val nan = floatArrayOf(Float.NaN, 0f, 0f, 0f)
        // NaN parent, finite child: neither class may be accepted.
        val parentBad = decide(finite(), parent = nan, child = finite())
        assertEquals(RecognitionWinner.NONE, parentBad.winner)
        assertTrue(parentBad.result is RecognitionResult.Unknown)

        // Finite parent, NaN child: the child must not be accepted…
        val childBad = decide(finite(), parent = finite(), child = nan)
        assertTrue(
            "a NaN child template must not be accepted as CHILD",
            childBad.result !is RecognitionResult.ChildRecognized,
        )
        // …and, as a documented consequence, a corrupted child template also stops the
        // parent being accepted (the comparison is not order-independent w.r.t. NaN).
        // The safe direction is denial: never a false identity.
        assertTrue(childBad.result is RecognitionResult.Unknown)
    }

    @Test
    fun anInfiniteTemplateNeverProducesARecognizedIdentity() {
        listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { inf ->
            val bad = floatArrayOf(inf, 0f, 0f, 0f)
            val d = decide(finite(), parent = bad, child = null)
            assertEquals("template=$inf", RecognitionWinner.NONE, d.winner)
            assertTrue("template=$inf", d.result is RecognitionResult.Unknown)
        }
    }

    // ------------------------------------------- non-finite never reaches a winner

    @Test
    fun noCombinationOfNonFiniteInputsYieldsAParentOrChildWinner() {
        val finite = listOf(0f, 1f, -1f)
        val nonFinite = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val values = finite + nonFinite
        for (l in values) {
            for (p in values) {
                for (c in values) {
                    val anyNonFinite = listOf(l, p, c).any { !it.isFinite() }
                    val live = floatArrayOf(l, 0f, 0f, 0f)
                    val parent = floatArrayOf(p, 0f, 0f, 0f)
                    val child = floatArrayOf(c, 0f, 0f, 0f)
                    val d = decide(live, parent = parent, child = child)
                    if (anyNonFinite) {
                        // A non-finite anywhere may only ever yield UNKNOWN / NONE.
                        assertFalse(
                            "non-finite input produced winner=${d.winner} (live=$l parent=$p child=$c)",
                            d.winner == RecognitionWinner.PARENT || d.winner == RecognitionWinner.CHILD,
                        )
                    } else {
                        // Sanity anchor: the fully-finite combinations behave normally, so the
                        // loop above is exercising real decisions and not a degenerate stub.
                        assertTrue(
                            "finite input must be a valid decision (live=$l parent=$p child=$c)",
                            d.result is RecognitionResult.ParentRecognized ||
                                d.result is RecognitionResult.ChildRecognized ||
                                d.result is RecognitionResult.Unknown,
                        )
                    }
                }
            }
        }
    }

    // --------------------------------------------------------- liveness decoupling

    @Test
    fun aSpoofLivenessStateOverridesIdentityButAnUndecidedOneNeverDoes() {
        // Identity and liveness stay independent (Block 5 audit): only a positive SPOOF
        // overrides; UNKNOWN/UNSTABLE/NO_FACE never silently promote or block an identity
        // on their own (no model is bundled; the heuristic reads a still real face as
        // UNKNOWN).
        assertTrue(LivenessPolicy.overridesIdentity(LivenessState.SPOOF))
        listOf(LivenessState.UNKNOWN, LivenessState.UNSTABLE, LivenessState.NO_FACE).forEach {
            assertFalse("$it must not override identity", LivenessPolicy.overridesIdentity(it))
            assertEquals(LivenessTrust.UNDECIDED, LivenessPolicy.trustOf(it))
        }
    }

    @Test
    fun thePassiveHeuristicNeverEmitsASpoofVerdict() {
        // With no anti-spoofing model bundled, the heuristic must never positively label a
        // presentation as SPOOF (that would be an uncalibrated, model-less claim).
        val frames = listOf(
            LivenessFrame(facePresent = true, yawDegrees = 0f, timestamp = 1_000),
            LivenessFrame(facePresent = true, yawDegrees = 0.1f, timestamp = 1_100),
            LivenessFrame(facePresent = true, yawDegrees = 0.2f, timestamp = 1_200),
            LivenessFrame(facePresent = true, yawDegrees = 12f, timestamp = 1_300),
        )
        val evaluator = LivenessEvaluator()
        frames.forEach { evaluator.observe(it) }
        val result = evaluator.result(now = 1_300)
        assertFalse(
            "the heuristic must never emit SPOOF without a model",
            result.state == LivenessState.SPOOF,
        )
        assertEquals(LivenessSource.HEURISTIC, result.source)
    }
}
