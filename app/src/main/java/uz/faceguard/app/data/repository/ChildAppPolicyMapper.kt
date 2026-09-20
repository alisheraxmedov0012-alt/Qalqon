package uz.faceguard.app.data.repository

import uz.faceguard.app.data.db.ChildAppPolicyEntity
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Room entity -> domain mapping, kept out of the repository class so the
 * mapping itself is unit-testable on the JVM (no database needed).
 *
 * Unknown enum names fall back to the safest legacy behaviour: BLOCK +
 * HARD_BLOCK, so a corrupted/older row never silently allows an app.
 */
internal fun ChildAppPolicyEntity.toDomainPolicy(): AppPolicy = AppPolicy(
    packageName = packageName,
    mode = runCatching { AppPolicyMode.valueOf(mode) }.getOrDefault(AppPolicyMode.BLOCK),
    action = runCatching { ProtectionAction.valueOf(action) }
        .getOrDefault(ProtectionAction.HARD_BLOCK),
    activationDelayMs = activationDelayMs,
    recoveryDelayMs = recoveryDelayMs,
    dailyLimitMinutes = dailyLimitMinutes,
    childId = childId,
)
