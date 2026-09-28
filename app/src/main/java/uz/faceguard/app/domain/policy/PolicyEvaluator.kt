package uz.faceguard.app.domain.policy

/**
 * Central policy decision point. Pure, synchronous and side-effect free so it
 * is trivially unit-testable and usable from the protection engine.
 *
 * Evaluation priority (deterministic — never order/random dependent):
 *   1. protection disabled            -> Allow
 *   2. spoofed presentation           -> configured spoof action (identity not trusted)
 *   3. parent recognised              -> Allow (mandatory override)
 *   4. child recognised               -> per-child app policy, else global protected app
 *                                        (screen-time LIMIT inside), then the restriction
 *                                        layers: eye safety, then schedule
 *   5. unknown user                   -> configured unknown-user action
 *   6. camera obstructed              -> configured obstruction action (fail-safe)
 *   7. no face                        -> configured no-face action
 *
 * Steps 4's restriction layers only ever *add* restriction ([restrictionRank] maximum), and only
 * for a protected package: eye safety (Phase 6) and schedules (Phase 5) can tighten a child's
 * decision but never loosen it.
 */
interface PolicyEvaluator {
    fun evaluate(context: PolicyContext): PolicyDecision
}
