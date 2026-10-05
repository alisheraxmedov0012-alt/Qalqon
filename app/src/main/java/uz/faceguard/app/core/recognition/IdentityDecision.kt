package uz.faceguard.app.core.recognition

import uz.faceguard.app.core.pipeline.EmbeddingSource
import uz.faceguard.app.domain.similarity.CosineSimilarity

/** One enrolled child template paired with the identity it belongs to. */
data class ChildTemplate(
    val childId: Long,
    val childName: String,
    val template: FloatArray,
)

/** Which identity class the decision engine selected (or none). */
enum class RecognitionWinner { PARENT, CHILD, NONE }

/**
 * Explainable, deterministic outcome of one identity decision.
 *
 * It carries the scores behind the decision so the choice can be asserted in tests
 * and observed for future threshold calibration — **never** the embeddings
 * themselves (those are never persisted or logged).
 *
 * [margin] is the absolute gap between the best parent score and the best child
 * score (0 when only one class is present). It is exposed **purely for observability
 * and future calibration**; Stage 1 deliberately does not gate on it, because no
 * labelled dataset exists to justify a margin value.
 */
data class IdentityDecision(
    val result: RecognitionResult,
    val winner: RecognitionWinner,
    val parentScore: Double?,
    val childId: Long?,
    val childName: String?,
    val bestChildScore: Double?,
    val bestScore: Double,
    val margin: Double,
)

/**
 * The single, pure Parent/Child/Unknown identity decision for QALQON.
 *
 * Stage 1 hardening. The previous logic short-circuited on the parent first and
 * otherwise accepted any child above its threshold, evaluating identities in a fixed
 * order against **asymmetric** thresholds (parent 0.68 > child 0.62). That is
 * order-dependent and admits a provable false positive: a genuine parent whose own
 * template scores below 0.68 but whose face coincidentally clears the child's lower
 * 0.62 threshold was reported as a CHILD.
 *
 * The rule here is order-independent and needs **no new threshold or margin value**:
 *
 * 1. The **single best-matching identity across both classes** is selected (the
 *    identity whose stored template is closest to the live frame).
 * 2. That best identity is accepted **only if it clears its own threshold**.
 * 3. A tie (both classes equally closest) is ambiguous -> UNKNOWN.
 *
 * Consequences:
 * - Parent -> Child false positive is removed: a genuine parent is only ever reported
 *   as a child if the child template is *strictly* the closest match, which is genuine
 *   ambiguity rather than a threshold artefact.
 * - Child -> Parent false positive is reduced: the parent is accepted only when its own
 *   template is the strictly closest match and clears 0.68.
 * - Anything ambiguous or below threshold is UNKNOWN, which flows through the existing
 *   (fail-safe) unknown-user policy; it is never silently promoted to a trusted identity.
 *
 * Two further safety guards:
 * - **Dimension guard:** a live vector is only compared against a template of the same
 *   length. [CosineSimilarity] compares over the shorter vector, so a 19-d geometry
 *   vector against a 192-d model template would otherwise yield a meaningless but
 *   non-zero score. Mismatched dimensions simply do not participate.
 * - **Source guard:** identity is accepted only from a real model embedding
 *   ([EmbeddingSource.MODEL]). The geometry fallback is not identity-grade and can
 *   never authorise Parent/Child.
 */
object IdentityDecisionEngine {

    fun decide(
        live: FloatArray,
        source: EmbeddingSource,
        parentTemplate: FloatArray?,
        children: List<ChildTemplate>,
        thresholds: Thresholds,
    ): IdentityDecision {
        // No usable live evidence at all.
        if (live.isEmpty()) return none(0.0)

        // Geometry fallback is not identity-grade: face presence is signalled elsewhere,
        // but it must never be matched for identity.
        if (source != EmbeddingSource.MODEL) return none(0.0)

        val parentScore = parentTemplate
            ?.takeIf { it.size == live.size }
            ?.let { CosineSimilarity.of(live, it) }

        val bestChild = children
            .filter { it.template.size == live.size }
            .map { it to CosineSimilarity.of(live, it.template) }
            .maxByOrNull { it.second }

        val childScore = bestChild?.second
        val bestScore = maxOf(parentScore ?: 0.0, childScore ?: 0.0)
        val margin = if (parentScore != null && childScore != null) {
            kotlin.math.abs(parentScore - childScore)
        } else {
            0.0
        }

        // The strictly closest identity (order-independent). A tie is ambiguous.
        val parentIsBest = parentScore != null && (childScore == null || parentScore > childScore)
        val childIsBest = childScore != null && (parentScore == null || childScore > parentScore)

        return when {
            parentIsBest && parentScore!! >= thresholds.parent -> IdentityDecision(
                result = RecognitionResult.ParentRecognized(parentScore),
                winner = RecognitionWinner.PARENT,
                parentScore = parentScore,
                childId = null,
                childName = null,
                bestChildScore = childScore,
                bestScore = bestScore,
                margin = margin,
            )

            childIsBest && childScore!! >= thresholds.child -> IdentityDecision(
                result = RecognitionResult.ChildRecognized(
                    childId = bestChild!!.first.childId,
                    childName = bestChild.first.childName,
                    confidence = childScore,
                ),
                winner = RecognitionWinner.CHILD,
                parentScore = parentScore,
                childId = bestChild.first.childId,
                childName = bestChild.first.childName,
                bestChildScore = childScore,
                bestScore = bestScore,
                margin = margin,
            )

            // Best identity did not clear its own threshold, or parent/child are equally
            // close -> no safe identity. UNKNOWN keeps the existing fail-safe behaviour.
            else -> none(bestScore).copy(
                parentScore = parentScore,
                childId = null,
                childName = null,
                bestChildScore = childScore,
                margin = margin,
            )
        }
    }

    private fun none(bestScore: Double) = IdentityDecision(
        result = RecognitionResult.Unknown(bestScore),
        winner = RecognitionWinner.NONE,
        parentScore = null,
        childId = null,
        childName = null,
        bestChildScore = null,
        bestScore = bestScore,
        margin = 0.0,
    )
}
