package uz.faceguard.app.core.recognition

import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.restrictionRank

/**
 * The aggregated outcome of evaluating **every** detected face in a frame.
 *
 * [result] is the single identity the rest of the protection pipeline consumes (it feeds
 * the existing `PolicyEvaluator`, exactly as a per-frame `RecognitionResult` did before).
 * [candidates] keeps the per-face decisions for observability/tests, and
 * [contributingChildId] records which child's identity the aggregation selected — the
 * same child id the downstream per-child policy, schedule and screen-time are resolved
 * for, so a child is never lost in aggregation.
 */
data class MultiFaceDecision(
    val result: RecognitionResult,
    val candidates: List<IdentityDecision>,
    val contributingChildId: Long?,
    val faceCount: Int,
)

/**
 * Stage 2: the deterministic, order-independent multi-face identity policy.
 *
 * Replaces the previous "decide on `faces.firstOrNull()`" behaviour. It aggregates the
 * **per-face** [IdentityDecision]s (each produced independently by
 * [IdentityDecisionEngine]) into one identity using a fixed product-safety priority,
 * independent of the order the detector returned the faces in:
 *
 * ```
 * CHILD  >  PARENT  >  UNKNOWN  >  NO_FACE
 * ```
 *
 * Rationale, in product terms:
 * - **Any recognised child wins over a parent** (`Child > Parent`): if a child is looking
 *   at the phone, child protection must apply — a parent also being visible must not
 *   bypass it (Case A). This is the safety-critical rule.
 * - **A recognised parent wins over an unknown** (`Parent > Unknown`): the parent is the
 *   trusted device owner; an extra unrecognised face must not, on its own, restrict the
 *   parent's own phone (Case C). The unknown policy still applies when no parent/child is
 *   recognised.
 * - **Unknown, then no-face** otherwise.
 *
 * Two determinism rules keep the result independent of face order:
 * - Among several recognised children, the **most restrictive** one wins (the child whose
 *   configured action for the current app has the higher [restrictionRank]); ties are
 *   broken by **higher similarity**, then by **lower child id**. So Child A `HARD_BLOCK`
 *   + Child B `SOFT_BLOCK` resolves to Child A, and two children with identical policy
 *   resolve to a fixed child — never to whichever the detector happened to list first.
 * - Among several parents (resp. unknowns) the highest similarity wins; a tie is broken
 *   deterministically by the candidate's score/id, never by list position.
 *
 * The engine is pure, deterministic and Android-free: it takes the already-computed
 * per-face decisions plus a plain `childActionOf` resolver (like the runtime's existing
 * in-memory policy lookup), so every rule is unit-testable on the JVM.
 */
object MultiFacePolicyEngine {

    fun decide(
        candidates: List<IdentityDecision>,
        childActionOf: (childId: Long) -> ProtectionAction? = { null },
    ): MultiFaceDecision {
        if (candidates.isEmpty()) {
            return MultiFaceDecision(RecognitionResult.NoFace, emptyList(), null, 0)
        }

        // 1. Any recognised child -> CHILD wins. Pick the child that would restrict the
        //    most (so one child cannot weaken another's protection), then the closest
        //    match, then the lowest id — never the list order.
        val children = candidates.filter { it.winner == RecognitionWinner.CHILD && it.childId != null }
        if (children.isNotEmpty()) {
            val chosen = children.maxWith(
                compareBy<IdentityDecision> { it.configuredActionRank(childActionOf) }
                    .thenBy { it.similarityForRanking() }
                    .thenBy { -it.childId!! },
            )
            return MultiFaceDecision(
                result = RecognitionResult.ChildRecognized(
                    childId = chosen.childId!!,
                    childName = chosen.childName.orEmpty(),
                    confidence = chosen.bestChildScore ?: chosen.bestScore,
                ),
                candidates = candidates,
                contributingChildId = chosen.childId,
                faceCount = candidates.size,
            )
        }

        // 2. No child: a recognised parent wins over any unknown.
        val parents = candidates.filter { it.winner == RecognitionWinner.PARENT }
        if (parents.isNotEmpty()) {
            val best = parents.maxByOrNull { it.similarityForRanking() }!!
            return MultiFaceDecision(
                result = RecognitionResult.ParentRecognized(best.parentScore ?: best.bestScore),
                candidates = candidates,
                contributingChildId = null,
                faceCount = candidates.size,
            )
        }

        // 3. Otherwise the frame is unknown (one or several unknown faces are all UNKNOWN;
        //    no face is ever "elected" as a winner).
        val bestScore = candidates.maxOf { it.bestScore }
        return MultiFaceDecision(
            result = RecognitionResult.Unknown(bestScore),
            candidates = candidates,
            contributingChildId = null,
            faceCount = candidates.size,
        )
    }

    /** The configured action's restriction rank, or `-1` when no policy is configured. */
    private fun IdentityDecision.configuredActionRank(childActionOf: (Long) -> ProtectionAction?): Int =
        childId?.let { childActionOf(it)?.restrictionRank } ?: -1

    /** The similarity a candidate is ranked by within its own identity class. */
    private fun IdentityDecision.similarityForRanking(): Double = when (winner) {
        RecognitionWinner.PARENT -> parentScore ?: bestScore
        RecognitionWinner.CHILD -> bestChildScore ?: bestScore
        RecognitionWinner.NONE -> bestScore
    }
}
