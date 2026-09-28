package uz.faceguard.app.feature.eyesafety

import uz.faceguard.app.R
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 5: the presentation mappings for the eye-safety screen.
 *
 * All of these are pure functions returning string-resource ids, so the visible text always comes
 * from `strings.xml` and never from a hardcoded literal in a composable — and so the mapping itself
 * is JVM-testable without a device.
 */

/**
 * The actions the parent may choose for the WARNING and DANGER levels, in the approved order:
 * `ALLOW`, `WARNING`, `SOFT_BLOCK`, `HARD_BLOCK`, `MUTE`.
 *
 * Derived from the canonical [IMPLEMENTED_ACTIONS] set, exactly as the schedule editor does, so the
 * picker can never offer an action this build cannot enforce (`DIM`, `BLUR`, `BLACK_SCREEN`). The
 * stored representation stays the existing [ProtectionAction] — no eye-safety-specific action type.
 */
val EYE_SAFETY_ACTIONS: List<ProtectionAction> =
    ProtectionAction.entries.filter { it in IMPLEMENTED_ACTIONS }

/**
 * What choosing [action] does, whichever level it is chosen for.
 *
 * States the action's existing semantics only: `ALLOW` adds no restriction, `WARNING` surfaces the
 * existing warning, `MUTE` silences audio, and the two blocks show the existing blocking overlay.
 * Nothing here promises a behaviour the executor does not implement. The *level* a choice applies to
 * is conveyed by its section heading, so one description per action serves both levels.
 */
fun actionDescriptionRes(action: ProtectionAction): Int = when (action) {
    ProtectionAction.ALLOW -> R.string.eye_safety_action_allow_hint
    ProtectionAction.WARNING -> R.string.eye_safety_action_warning_hint
    ProtectionAction.SOFT_BLOCK -> R.string.eye_safety_action_soft_block_hint
    ProtectionAction.HARD_BLOCK -> R.string.eye_safety_action_hard_block_hint
    ProtectionAction.MUTE -> R.string.eye_safety_action_mute_hint
    ProtectionAction.DIM,
    ProtectionAction.BLUR,
    ProtectionAction.BLACK_SCREEN,
    -> R.string.eye_safety_action_unavailable_hint
}

/** The label shown on the chip for [action]. */
fun actionLabelRes(action: ProtectionAction): Int = when (action) {
    ProtectionAction.ALLOW -> R.string.eye_safety_action_allow
    ProtectionAction.WARNING -> R.string.eye_safety_action_warning
    ProtectionAction.SOFT_BLOCK -> R.string.eye_safety_action_soft_block
    ProtectionAction.HARD_BLOCK -> R.string.eye_safety_action_hard_block
    ProtectionAction.MUTE -> R.string.eye_safety_action_mute
    ProtectionAction.DIM,
    ProtectionAction.BLUR,
    ProtectionAction.BLACK_SCREEN,
    // Never offered; a total mapping keeps the function honest rather than falling through.
    -> R.string.eye_safety_action_unavailable_hint
}

/**
 * The child's eye-safety status as the parent sees it.
 *
 * Unconfigured (no stored row) is deliberately distinct from disabled (a stored row whose
 * `enabled` is false), because the two mean different things in this product: one has never been
 * set up, the other has been set up and switched off with its values intact.
 */
fun statusLabelRes(configured: Boolean, enabled: Boolean): Int = when {
    !configured -> R.string.eye_safety_status_not_configured
    enabled -> R.string.eye_safety_status_enabled
    else -> R.string.eye_safety_status_disabled
}
