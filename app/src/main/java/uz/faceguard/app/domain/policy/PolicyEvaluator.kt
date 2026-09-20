package uz.faceguard.app.domain.policy

/**
 * Central policy decision point. Pure, synchronous and side-effect free so it
 * is trivially unit-testable and usable from the protection engine.
 *
 * Evaluation priority (deterministic — never order/random dependent):
 *   1. protection disabled            -> Allow
 *   2. parent recognised              -> Allow (mandatory override)
 *   3. child recognised               -> per-child app policy, else global protected app
 *   4. unknown user                   -> configured unknown-user action
 *   5. camera obstructed              -> configured obstruction action (fail-safe)
 *   6. no face                        -> configured no-face action
 *   7. schedule / screen-time / eye-safety hooks (foundation, default inactive)
 */
interface PolicyEvaluator {
    fun evaluate(context: PolicyContext): PolicyDecision
}
