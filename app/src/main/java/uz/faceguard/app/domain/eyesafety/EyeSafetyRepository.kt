package uz.faceguard.app.domain.eyesafety

import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 3: one child's persisted eye-safety configuration.
 *
 * It **composes** the Step 1 detector configuration rather than duplicating it: [config] is the
 * existing [EyeSafetyConfig], so every threshold, `confirmFrames` and the enabled flag — and,
 * crucially, their invariants — stay exactly where Step 1 put them. What [EyeSafetyConfig] has no
 * place for is added here and only here: the owning `(accountId, childId)`, the two
 * parent-configured actions (which live in the policy layer, not in the detector's parameter
 * object) and the `updatedAt` bookkeeping.
 *
 * The three temporal parameters [EyeSafetyConfig] also carries (`maxWindowAgeMs`,
 * `maxWindowFrames`, `minimumPresenceRatio`) are **not** persisted in this step: they are engine
 * tuning, not parent configuration, so a loaded configuration takes the domain defaults for them.
 */
data class ChildEyeSafetyConfig(
    val accountId: Long,
    val childId: Long,
    val config: EyeSafetyConfig,
    /**
     * The restriction applied when the state is [uz.faceguard.app.domain.policy.EyeSafetyState.WARNING].
     * [ProtectionAction.ALLOW] means "do not restrict for a warning".
     */
    val warningAction: ProtectionAction,
    /** The restriction applied when the state is [uz.faceguard.app.domain.policy.EyeSafetyState.DANGER]. */
    val dangerAction: ProtectionAction,
    val updatedAt: Long,
) {

    init {
        require(accountId > 0L) { "accountId must be positive, was $accountId" }
        require(childId > 0L) { "childId must be positive, was $childId" }
    }
}

/**
 * Phase 6 Step 3: stores and retrieves a child's eye-safety configuration.
 *
 * Every method names both `accountId` and `childId`, which together are the persisted identity:
 * there is no lookup by child alone, so one account's child can never be read as another's, and no
 * method can act across children.
 *
 * **Absence is meaningful.** [config] returns `null` when the child has no configuration, and
 * nothing in this layer ever writes a default row: `null` means "eye safety has not been configured
 * for this child", which callers must treat as such rather than as a stored `enabled = false`.
 *
 * The contract exposes domain models only — no Room entity crosses this boundary. Validation stays
 * in the domain: loading a stored configuration reconstructs [EyeSafetyConfig], so a corrupt row
 * fails loudly instead of being silently corrected.
 */
interface EyeSafetyRepository {

    /** The child's configuration, or `null` when the child has none. */
    suspend fun config(accountId: Long, childId: Long): ChildEyeSafetyConfig?

    /**
     * [config] as an observable stream, so a saved change is seen without polling.
     *
     * Emits `null` while the child has no configuration, and again if it is deleted.
     */
    fun observeConfig(accountId: Long, childId: Long): Flow<ChildEyeSafetyConfig?>

    /**
     * Creates or replaces the child's configuration.
     *
     * The row's own `(accountId, childId)` come from [ChildEyeSafetyConfig], so a save can only
     * ever write the scope it was given. `updatedAt` is carried on the model and persisted as
     * given — no clock is read here.
     */
    suspend fun save(config: ChildEyeSafetyConfig)

    /** Removes the child's configuration. A child with none is left untouched. */
    suspend fun delete(accountId: Long, childId: Long)
}
