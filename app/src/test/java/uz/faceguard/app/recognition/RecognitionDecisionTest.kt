package uz.faceguard.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.pipeline.EmbeddingSource
import uz.faceguard.app.core.recognition.ChildTemplate
import uz.faceguard.app.core.recognition.IdentityDecisionEngine
import uz.faceguard.app.core.recognition.RecognitionWinner
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Thresholds

/**
 * Stage 1 (Recognition Reliability): the pure, deterministic identity decision.
 *
 * These pin the decision *mathematics* — which identity wins, when an outcome is
 * ambiguous, and the two safety guards (dimension + embedding source). They do **not**
 * prove real-world accuracy or FAR/FRR; those require a labelled dataset and physical
 * devices and are explicitly out of scope here (see the Stage 1 report).
 */
class RecognitionDecisionTest {

    private val thresholds = Thresholds() // parent 0.68, child 0.62

    /** A live frame whose similarity to the stored templates is dialled in exactly. */
    private val live = floatArrayOf(1f, 0f, 0f, 0f)

    /** A unit vector whose cosine similarity to [live] is [cos] (for |cos| <= 1). */
    private fun template(cos: Double, size: Int = 4): FloatArray =
        FloatArray(size).also {
            it[0] = cos.toFloat()
            if (size > 1) it[1] = kotlin.math.sqrt((1.0 - cos * cos).coerceAtLeast(0.0)).toFloat()
        }

    private fun decide(
        parent: FloatArray?,
        child: FloatArray?,
        source: EmbeddingSource = EmbeddingSource.MODEL,
    ) = IdentityDecisionEngine.decide(
        live = live,
        source = source,
        parentTemplate = parent,
        children = child?.let { listOf(ChildTemplate(7L, "Vali", it)) } ?: emptyList(),
        thresholds = thresholds,
    )

    // --------------------------------------------------------------- Parent

    @Test
    fun aStrongParentMatchIsRecognisedAsParent() {
        val decision = decide(parent = template(0.90), child = template(0.40))

        assertEquals(RecognitionWinner.PARENT, decision.winner)
        assertTrue(decision.result is RecognitionResult.ParentRecognized)
        assertEquals(0.90, (decision.result as RecognitionResult.ParentRecognized).confidence, 1e-6)
    }

    @Test
    fun aParentBelowThresholdIsNotAccepted() {
        val decision = decide(parent = template(0.60), child = null)

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun theParentThresholdIsInclusiveAtItsExactValue() {
        // Assert the `>=` boundary against the engine's own computed score (float
        // construction cannot land on the threshold exactly).
        val decision = decide(parent = template(thresholds.parent), child = null)

        assertEquals(
            decision.parentScore!! >= thresholds.parent,
            decision.winner == RecognitionWinner.PARENT,
        )
    }

    // --------------------------------------------------------------- Child

    @Test
    fun aStrongChildMatchIsRecognisedAsChild() {
        val decision = decide(parent = template(0.40), child = template(0.85))

        assertEquals(RecognitionWinner.CHILD, decision.winner)
        val result = decision.result as RecognitionResult.ChildRecognized
        assertEquals(7L, result.childId)
        assertEquals("Vali", result.childName)
    }

    @Test
    fun aChildBelowThresholdIsNotAccepted() {
        val decision = decide(parent = null, child = template(0.50))

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun theChildThresholdIsInclusiveAtItsExactValue() {
        // Assert the `>=` boundary against the engine's own computed score.
        val decision = decide(parent = template(0.40), child = template(thresholds.child))

        assertEquals(
            decision.bestChildScore!! >= thresholds.child,
            decision.winner == RecognitionWinner.CHILD,
        )
    }

    // --------------------------------------------------------------- Unknown

    @Test
    fun lowSimilarityIsUnknownWithTheBestObservedScore() {
        val decision = decide(parent = template(0.30), child = template(0.35))

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertEquals(0.35, (decision.result as RecognitionResult.Unknown).confidence, 1e-6)
        assertEquals(0.35, decision.bestScore, 1e-6)
    }

    @Test
    fun anUnknownCloserToParentButBelowThresholdStaysUnknown() {
        val decision = decide(parent = template(0.66), child = template(0.20))

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun anUnknownCloserToChildButBelowThresholdStaysUnknown() {
        val decision = decide(parent = template(0.20), child = template(0.58))

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun anEmptyLiveVectorDecidesNothing() {
        val decision = IdentityDecisionEngine.decide(
            live = FloatArray(0),
            source = EmbeddingSource.MODEL,
            parentTemplate = template(0.99),
            children = listOf(ChildTemplate(7L, "Vali", template(0.99))),
            thresholds = thresholds,
        )

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    // ------------------------------------------------ Parent vs Child safety

    @Test
    fun aParentWhoseOwnScoreIsBelowThresholdIsNotForcedIntoTheChildClass() {
        // Stage 1 regression: the old parent-first logic returned CHILD here, because the
        // parent failed its (higher) 0.68 threshold and the child's lower 0.62 was cleared.
        // A genuine parent must never be reported as a child on a threshold artefact.
        val decision = decide(parent = template(0.66), child = template(0.63))

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(
            "a sub-threshold parent must stay UNKNOWN, never silently become CHILD",
            decision.result is RecognitionResult.Unknown,
        )
        assertNull(decision.childId)
    }

    @Test
    fun aClearChildWinsOverAParentBelowItsThreshold() {
        val decision = decide(parent = template(0.65), child = template(0.70))

        assertEquals(RecognitionWinner.CHILD, decision.winner)
    }

    @Test
    fun aClearParentWinsOverAChildBelowItsThreshold() {
        val decision = decide(parent = template(0.70), child = template(0.65))

        assertEquals(RecognitionWinner.PARENT, decision.winner)
    }

    @Test
    fun anExactTieBetweenParentAndChildIsAmbiguous() {
        val decision = decide(parent = template(0.70), child = template(0.70))

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun aDominantChildTemplateWinsAndNeverAllows() {
        // If the child template really is the closest match for the live face, the safe
        // outcome is CHILD (block), never PARENT (allow).
        val decision = decide(parent = template(0.66), child = template(0.72))

        assertEquals(RecognitionWinner.CHILD, decision.winner)
        assertTrue(decision.result is RecognitionResult.ChildRecognized)
    }

    @Test
    fun theDecisionReportsTheParentChildMarginForCalibration() {
        val decision = decide(parent = template(0.90), child = template(0.50))

        assertEquals(0.40, decision.margin, 1e-6)
    }

    // ------------------------------------------------------- safety guards

    @Test
    fun aDimensionMismatchNeverProducesAFalseIdentity() {
        // A 19-d geometry live vector vs a 192-d model template: cosine over the shorter
        // vector would otherwise be ~1.0 (all-equal vectors) and wrongly accept PARENT.
        val geometryLive = FloatArray(19) { 1f }
        val modelTemplate = FloatArray(192) { 1f }

        val decision = IdentityDecisionEngine.decide(
            live = geometryLive,
            source = EmbeddingSource.MODEL,
            parentTemplate = modelTemplate,
            children = emptyList(),
            thresholds = thresholds,
        )

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun theGeometryFallbackCanNeverAuthoriseAnIdentity() {
        // Even a perfect match must be refused: the geometry fallback is not identity-grade.
        val decision = decide(
            parent = template(1.0),
            child = template(1.0),
            source = EmbeddingSource.GEOMETRY,
        )

        assertEquals(RecognitionWinner.NONE, decision.winner)
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    @Test
    fun aChildIsStillRecognisedWhenNoParentTemplateExists() {
        val decision = decide(parent = null, child = template(0.80))

        assertEquals(RecognitionWinner.CHILD, decision.winner)
    }

    @Test
    fun aParentIsStillRecognisedWhenNoChildTemplateExists() {
        val decision = decide(parent = template(0.80), child = null)

        assertEquals(RecognitionWinner.PARENT, decision.winner)
    }
}
