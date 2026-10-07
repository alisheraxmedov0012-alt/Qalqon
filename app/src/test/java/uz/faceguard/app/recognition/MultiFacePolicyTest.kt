package uz.faceguard.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.pipeline.EmbeddingSource
import uz.faceguard.app.core.recognition.ChildTemplate
import uz.faceguard.app.core.recognition.IdentityDecision
import uz.faceguard.app.core.recognition.IdentityDecisionEngine
import uz.faceguard.app.core.recognition.MultiFaceDecision
import uz.faceguard.app.core.recognition.MultiFacePolicyEngine
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.RecognitionWinner
import uz.faceguard.app.core.recognition.Thresholds
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Stage 2 (Multi-Face & Identity Policy): the deterministic, order-independent aggregation.
 *
 * Each face is decided independently by [IdentityDecisionEngine] (Stage 1), then aggregated
 * by [MultiFacePolicyEngine]. These tests drive the same composition the recogniser uses at
 * runtime (per-face decision -> aggregate) with synthetic frames, so the whole decision is
 * deterministic and JVM-testable. They prove decision *logic*, not real-world accuracy — that
 * needs physical devices and is out of Stage 2 scope.
 */
class MultiFacePolicyTest {

    private val thresholds = Thresholds() // parent 0.68, child 0.62

    // Orthonormal live faces and templates so each face matches exactly one identity.
    private val parentFace = floatArrayOf(1f, 0f, 0f, 0f)
    private val childFace = floatArrayOf(0f, 1f, 0f, 0f)
    private val unknownFace = floatArrayOf(0f, 0f, 1f, 0f)
    private val parentTemplate = floatArrayOf(1f, 0f, 0f, 0f)
    private val childATemplate = floatArrayOf(0f, 1f, 0f, 0f)
    private val childBTemplate = floatArrayOf(0f, 0f, 1f, 0f) // child B == the "unknown" axis

    private val childA = ChildTemplate(7L, "Vali", childATemplate)
    private val childB = ChildTemplate(8L, "Ali", childBTemplate)

    /** The per-face decision for one live face, exactly as the recogniser computes it. */
    private fun face(
        live: FloatArray,
        children: List<ChildTemplate> = listOf(childA),
        source: EmbeddingSource = EmbeddingSource.MODEL,
    ): IdentityDecision = IdentityDecisionEngine.decide(
        live = live,
        source = source,
        parentTemplate = parentTemplate,
        children = children,
        thresholds = thresholds,
    )

    /** Aggregates the per-face decisions of a frame, exactly as the recogniser does. */
    private fun decide(
        lives: List<FloatArray>,
        children: List<ChildTemplate> = listOf(childA),
        childActionOf: (Long) -> ProtectionAction? = { null },
        sources: List<EmbeddingSource> = lives.map { EmbeddingSource.MODEL },
    ): MultiFaceDecision =
        MultiFacePolicyEngine.decide(
            lives.mapIndexed { i, live -> face(live, children, sources[i]) },
            childActionOf,
        )

    // ------------------------------------------------------------- single face

    @Test
    fun aSingleParentFaceIsParent() {
        assertTrue(decide(listOf(parentFace)).result is RecognitionResult.ParentRecognized)
    }

    @Test
    fun aSingleChildFaceIsChildWithItsId() {
        val result = decide(listOf(childFace)).result as RecognitionResult.ChildRecognized
        assertEquals(7L, result.childId)
        assertEquals("Vali", result.childName)
    }

    @Test
    fun aSingleUnknownFaceIsUnknown() {
        val result = decide(listOf(floatArrayOf(0.1f, 0.1f, 0.1f, 0.9f))).result
        assertTrue(result is RecognitionResult.Unknown)
    }

    @Test
    fun anEmptyFrameIsNoFace() {
        val decision = MultiFacePolicyEngine.decide(emptyList())
        assertTrue(decision.result is RecognitionResult.NoFace)
        assertEquals(0, decision.faceCount)
        assertNull(decision.contributingChildId)
    }

    // ------------------------------------------------ Parent + Child (Case A)

    @Test
    fun parentAndChildResolveToTheChildInEitherOrder() {
        // The safety-critical rule: a present child must never be bypassed by a parent.
        val a = decide(listOf(parentFace, childFace)).result
        val b = decide(listOf(childFace, parentFace)).result

        assertTrue(a is RecognitionResult.ChildRecognized)
        assertTrue(b is RecognitionResult.ChildRecognized)
        assertEquals(7L, (b as RecognitionResult.ChildRecognized).childId)
    }

    // ------------------------------------------------ Two children (Case B)

    @Test
    fun twoChildrenResolveToTheMostRestrictiveChildsAction() {
        // Child A -> HARD_BLOCK, Child B -> SOFT_BLOCK: A must win.
        val result = decide(
            lives = listOf(childFace, childBTemplate),
            children = listOf(childA, childB),
            childActionOf = { id -> if (id == 7L) ProtectionAction.HARD_BLOCK else ProtectionAction.SOFT_BLOCK },
        ).result as RecognitionResult.ChildRecognized

        assertEquals(7L, result.childId)
    }

    @Test
    fun twoChildrenWithTheSameActionResolveDeterministicallyToTheLowerId() {
        val result = decide(
            lives = listOf(childBTemplate, childFace), // B first, A second
            children = listOf(childA, childB),
            childActionOf = { ProtectionAction.HARD_BLOCK },
        ).result as RecognitionResult.ChildRecognized

        // Regardless of the face order, the lower child id is chosen.
        assertEquals(7L, result.childId)
    }

    @Test
    fun twoChildrenReversedGiveTheSameResult() {
        val action: (Long) -> ProtectionAction = { ProtectionAction.SOFT_BLOCK }
        val a = decide(listOf(childFace, childBTemplate), listOf(childA, childB), action).result
        val b = decide(listOf(childBTemplate, childFace), listOf(childA, childB), action).result

        assertEquals(a::class, b::class)
        assertEquals(
            (a as RecognitionResult.ChildRecognized).childId,
            (b as RecognitionResult.ChildRecognized).childId,
        )
    }

    // ------------------------------------------------ Parent + Unknown (Case C)

    @Test
    fun aRecognisedParentWinsOverAnUnknown() {
        val result = decide(listOf(unknownFace, parentFace)).result
        assertTrue(result is RecognitionResult.ParentRecognized)
    }

    @Test
    fun parentAndUnknownAreOrderIndependent() {
        val a = decide(listOf(parentFace, unknownFace)).result
        val b = decide(listOf(unknownFace, parentFace)).result
        assertEquals(a::class, b::class)
    }

    // ------------------------------------------------ Child + Unknown (Case D)

    @Test
    fun aRecognisedChildWinsOverAnUnknown() {
        val result = decide(listOf(unknownFace, childFace)).result
        assertTrue(result is RecognitionResult.ChildRecognized)
    }

    // ------------------------------------------------ Multiple Unknown (Case E)

    @Test
    fun severalUnknownFacesStayUnknownAndNoFaceIsElected() {
        val unknown1 = floatArrayOf(0f, 0f, 1f, 0f)
        val unknown2 = floatArrayOf(0f, 0f, 0f, 1f)
        val decision = decide(listOf(unknown1, unknown2, unknownFace))

        assertTrue(decision.result is RecognitionResult.Unknown)
        assertNull(decision.contributingChildId)
        assertEquals(3, decision.faceCount)
    }

    @Test
    fun unknownsInAnyOrderGiveTheSameResult() {
        val u1 = floatArrayOf(0f, 0f, 1f, 0f)
        val u2 = floatArrayOf(0f, 0f, 0f, 1f)
        val a = decide(listOf(u1, u2)).result
        val b = decide(listOf(u2, u1)).result
        assertEquals(a::class, b::class)
    }

    // ------------------------------------ Parent + Child + Unknown (mixed)

    @Test
    fun parentChildAndUnknownResolveToTheChild() {
        val result = decide(listOf(unknownFace, parentFace, childFace)).result
        assertTrue(result is RecognitionResult.ChildRecognized)
    }

    @Test
    fun threeChildrenResolveDeterministically() {
        val childC = ChildTemplate(9L, "Hasan", floatArrayOf(0f, 0f, 0f, 1f))
        val childCFace = floatArrayOf(0f, 0f, 0f, 1f)
        val result = decide(
            lives = listOf(childCFace, childBTemplate, childFace),
            children = listOf(childA, childB, childC),
            childActionOf = { ProtectionAction.HARD_BLOCK },
        ).result as RecognitionResult.ChildRecognized

        // Same action for all three -> deterministic lowest id (A = 7).
        assertEquals(7L, result.childId)
    }

    // ------------------------------------------------ determinism / permutation

    @Test
    fun everyPermutationOfParentChildUnknownIsIdentical() {
        val faces = listOf(parentFace, childFace, unknownFace)
        val decisions = faces.indices.toList().permutations().map { order ->
            decide(order.map { faces[it] }).result::class
        }
        assertEquals(1, decisions.distinct().size)
        assertEquals(6, decisions.size)
        assertTrue(decisions.all { it == RecognitionResult.ChildRecognized::class })
    }

    @Test
    fun everyPermutationOfFourFacesIsIdentical() {
        val faces = listOf(childFace, childBTemplate, parentFace, unknownFace)
        val results = faces.indices.toList().permutations().map { order ->
            decide(
                lives = order.map { faces[it] },
                children = listOf(childA, childB),
                childActionOf = { ProtectionAction.HARD_BLOCK },
            ).result
        }
        val ids = results.map { (it as RecognitionResult.ChildRecognized).childId }
        assertEquals(1, ids.distinct().size) // all permutations pick the same child
    }

    @Test
    fun repeatedRandomShufflesNeverChangeTheResult() {
        val faces = listOf(parentFace, childFace, unknownFace, childBTemplate)
        val expected = decide(faces, listOf(childA, childB)).result::class
        repeat(100) {
            assertEquals(expected, decide(faces.shuffled(), listOf(childA, childB)).result::class)
        }
    }

    // ------------------------------------------------ child identity preserved

    @Test
    fun theAggregationKeepsTheContributingChildId() {
        val decision = decide(listOf(parentFace, childFace))
        assertEquals(7L, decision.contributingChildId)
        assertNull(decide(listOf(parentFace)).contributingChildId)
    }

    // ------------------------------------------------ safety guards still hold

    @Test
    fun aGeometryFaceCannotAuthorizeAnIdentityAndDoesNotChangeTheOutcome() {
        // The child face provided only as geometry (not identity-grade) must NOT make the
        // frame a child; the recognised parent still wins.
        val decision = decide(
            lives = listOf(parentFace, childFace),
            sources = listOf(EmbeddingSource.MODEL, EmbeddingSource.GEOMETRY),
        )
        assertTrue(decision.result is RecognitionResult.ParentRecognized)
    }

    @Test
    fun aDimensionMismatchFaceDoesNotChangeTheOutcome() {
        // A 19-d geometry live vector against 4-d templates must not contribute an identity.
        val decision = decide(
            lives = listOf(parentFace, FloatArray(19) { 1f }),
            sources = listOf(EmbeddingSource.MODEL, EmbeddingSource.MODEL),
        )
        assertTrue(decision.result is RecognitionResult.ParentRecognized)
    }

    @Test
    fun allGeometryFacesProduceUnknownNeverAnIdentity() {
        val decision = decide(
            lives = listOf(parentFace, childFace),
            sources = listOf(EmbeddingSource.GEOMETRY, EmbeddingSource.GEOMETRY),
        )
        assertTrue(decision.result is RecognitionResult.Unknown)
    }

    // ------------------------------------------------ candidate observability

    @Test
    fun theDecisionExposesEveryPerFaceCandidate() {
        val decision = decide(listOf(parentFace, childFace, unknownFace))
        assertEquals(3, decision.candidates.size)
        assertEquals(
            listOf(RecognitionWinner.PARENT, RecognitionWinner.CHILD, RecognitionWinner.NONE),
            decision.candidates.map { it.winner },
        )
    }

    private fun List<Int>.permutations(): List<List<Int>> =
        if (size <= 1) listOf(this)
        else flatMapIndexed { index, value ->
            (this - value).permutations().map { listOf(value) + it }
        }
}
