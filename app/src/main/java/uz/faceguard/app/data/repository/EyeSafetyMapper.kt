package uz.faceguard.app.data.repository

import uz.faceguard.app.data.db.ChildEyeSafetyEntity
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 3: Room entity <-> domain mapping for eye-safety configuration.
 *
 * Kept out of the repository class so the mapping is unit-testable on the JVM (no database needed),
 * the same way the schedule and app-policy mappers are.
 *
 * Room entities never leave this layer, and the domain never sees one. Two conversions are
 * deliberate:
 *
 *  - **thresholds** are stored as integer percentages and mapped to the domain's normalized ratio
 *    (`30` <-> `0.30f`). `Int / 100f` is correctly rounded in IEEE-754, so a whole percent
 *    round-trips exactly through the float the domain uses.
 *  - **actions** are stored as `ProtectionAction.name` and read back with
 *    [ProtectionAction.valueOf], which throws on an unknown name. That is intentional and mirrors
 *    the schedule mapper: silently substituting a different action would enforce something the
 *    parent never chose, so a corrupt row must fail loudly.
 *
 * Reading a row reconstructs [EyeSafetyConfig], so its `init` invariants run again on every load. A
 * stored configuration that violates them (an out-of-range percentage, a warning threshold at or
 * above the danger threshold) therefore throws instead of being clamped or swapped — the
 * persistence layer never "fixes" data.
 */

/** Room row -> domain configuration. Throws when the stored row is not a valid configuration. */
internal fun ChildEyeSafetyEntity.toDomainEyeSafety(): ChildEyeSafetyConfig = ChildEyeSafetyConfig(
    accountId = accountId,
    childId = childId,
    config = EyeSafetyConfig(
        enabled = enabled,
        warningEnterThreshold = warningEnterThresholdPercent.toRatio(),
        warningExitThreshold = warningExitThresholdPercent.toRatio(),
        dangerEnterThreshold = dangerEnterThresholdPercent.toRatio(),
        dangerExitThreshold = dangerExitThresholdPercent.toRatio(),
        confirmFrames = confirmFrames,
    ),
    warningAction = ProtectionAction.valueOf(warningAction),
    dangerAction = ProtectionAction.valueOf(dangerAction),
    updatedAt = updatedAt,
)

/** Domain configuration -> Room row. */
internal fun ChildEyeSafetyConfig.toEntity(): ChildEyeSafetyEntity = ChildEyeSafetyEntity(
    accountId = accountId,
    childId = childId,
    enabled = config.enabled,
    warningEnterThresholdPercent = config.warningEnterThreshold.toPercent(),
    warningExitThresholdPercent = config.warningExitThreshold.toPercent(),
    dangerEnterThresholdPercent = config.dangerEnterThreshold.toPercent(),
    dangerExitThresholdPercent = config.dangerExitThreshold.toPercent(),
    confirmFrames = config.confirmFrames,
    warningAction = warningAction.name,
    dangerAction = dangerAction.name,
    updatedAt = updatedAt,
)

/** `30` -> `0.30f`. The value has already been validated by [EyeSafetyConfig] on the way in. */
private fun Int.toRatio(): Float = this / 100f

/** `0.30f` -> `30`. Rounded so a caller passing `0.299f` cannot silently store a different percent. */
private fun Float.toPercent(): Int = Math.round(this * 100f)
