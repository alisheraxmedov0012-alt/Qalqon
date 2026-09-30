package uz.faceguard.app.domain.policy

import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

/**
 * Persistent policy settings, exposed as the Group 1 [PolicySettings] domain
 * model so the evaluator/runtime never sees DataStore or `AppSettings`.
 *
 * Storage stays single-source: this is a projection over the existing
 * settings store (see `SettingsRepository`), not a second copy.
 */
interface PolicySettingsRepository {

    fun observe(): Flow<PolicySettings>

    suspend fun setProtectionEnabled(enabled: Boolean)

    suspend fun setUnknownUserAction(action: ProtectionAction)

    suspend fun setNoFaceAction(action: ProtectionAction)

    /** Milliseconds, matching `AppSettings.recoveryDelayMs`. */
    suspend fun setRecoveryDelayMs(delayMs: Long)

    suspend fun setScanMode(mode: ScanMode)

    suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean)
}

/**
 * Maps persisted [AppSettings] onto the evaluator's [PolicySettings].
 *
 * Only the fields Group 2 persists are taken from storage. The remaining policy
 * fields keep documented defaults until a later group persists them:
 * - `activationDelayMs = 0` -> act immediately (today's behaviour)
 * - `childAction = HARD_BLOCK` -> preserves the legacy hard-block behaviour
 * - `obstructionAction` follows the unknown-user policy, matching the
 *   ProtectionEngine's existing fail-safe rule
 * - `spoofAction = SOFT_BLOCK` -> Group 9 safe default (not persisted yet):
 *   a spoofed presentation must not unlock, without the parent false-positive
 *   risk of a hard block
 * - `parentDeviceChildPolicyEnabled = false` -> device mode is not persisted yet
 */
fun AppSettings.toPolicySettings(): PolicySettings = PolicySettings(
    enabled = protectionEnabled,
    activationDelayMs = 0L,
    childAction = ProtectionAction.HARD_BLOCK,
    unknownUserAction = unknownUserPolicy.toProtectionAction(),
    noFaceAction = noFacePolicy.toProtectionAction(),
    obstructionAction = unknownUserPolicy.toProtectionAction(),
    recoveryDelayMs = recoveryDelayMs,
    parentDeviceChildPolicyEnabled = false,
)

/**
 * Only these three actions have a persisted counterpart. Anything else (WARNING,
 * MUTE, DIM, ...) is a programming error: fail fast instead of silently storing
 * a different policy than the caller asked for.
 */
internal fun ProtectionAction.asBlockPolicy(): BlockPolicy = when (this) {
    ProtectionAction.ALLOW -> BlockPolicy.ALLOW
    ProtectionAction.SOFT_BLOCK -> BlockPolicy.SOFT_BLOCK
    ProtectionAction.HARD_BLOCK -> BlockPolicy.HARD_BLOCK
    else -> throw IllegalArgumentException(
        "$this has no persisted policy counterpart; only ALLOW/SOFT_BLOCK/HARD_BLOCK are storable",
    )
}
