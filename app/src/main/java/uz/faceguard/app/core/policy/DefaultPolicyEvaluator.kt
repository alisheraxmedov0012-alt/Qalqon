package uz.faceguard.app.core.policy

import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicyEvaluator
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.policy.isRestrictive

/**
 * Default implementation of the Parent Policy Engine.
 *
 * Pure Kotlin: no Android, no I/O, no logging. Every branch is deterministic
 * and covered by unit tests.
 */
class DefaultPolicyEvaluator : PolicyEvaluator {

    override fun evaluate(context: PolicyContext): PolicyDecision {
        val settings = context.settings

        // 1. Protection switched off -> nothing to enforce.
        if (!settings.enabled) return PolicyDecision.Allow

        return when (context.identity.identity) {
            // 2. Parent always wins, on both device modes.
            UserIdentity.PARENT -> PolicyDecision.Allow

            // 3. Child -> app-scoped policy, then global protected app.
            UserIdentity.CHILD -> evaluateChild(context, settings)

            // 4. Unknown / 5. obstructed / 6. no face follow configured actions.
            UserIdentity.UNKNOWN -> configured(
                settings.unknownUserAction,
                PolicyTrigger.UNKNOWN_USER,
                "unknown user",
                settings,
            )

            UserIdentity.CAMERA_OBSTRUCTED -> configured(
                settings.obstructionAction,
                PolicyTrigger.CAMERA_OBSTRUCTED,
                "camera obstructed",
                settings,
            )

            UserIdentity.NO_FACE -> configured(
                settings.noFaceAction,
                PolicyTrigger.NO_FACE,
                "no face detected",
                settings,
            )
        }
    }

    private fun evaluateChild(context: PolicyContext, settings: PolicySettings): PolicyDecision {
        val childId = context.identity.childId

        // The parent device may be configured to skip child enforcement.
        if (context.deviceOwnerMode == DeviceOwnerMode.PARENT_DEVICE &&
            !settings.parentDeviceChildPolicyEnabled
        ) {
            return PolicyDecision.Allow
        }

        val policy = context.appPolicy
        // Guard against a policy leaking across children.
        if (policy != null && policy.childId != null && policy.childId != childId) {
            return PolicyDecision.Allow
        }

        if (policy != null) {
            when (policy.mode) {
                AppPolicyMode.ALLOW -> return PolicyDecision.Allow

                AppPolicyMode.LIMIT -> {
                    val limit = policy.dailyLimitMinutes
                    val used = context.appTimeUsedMinutes
                    val exceeded = limit != null && used >= limit
                    if (!exceeded) return PolicyDecision.Allow
                    return protect(
                        action = policy.action,
                        trigger = PolicyTrigger.SCREEN_TIME_EXCEEDED,
                        reason = "daily limit reached ($used/$limit min)",
                        activationOverride = policy.activationDelayMs,
                        recoveryOverride = policy.recoveryDelayMs,
                        settings = settings,
                    )
                }

                AppPolicyMode.BLOCK -> return protect(
                    action = policy.action,
                    trigger = PolicyTrigger.PROTECTED_APP_OPENED,
                    reason = "app blocked by policy",
                    activationOverride = policy.activationDelayMs,
                    recoveryOverride = policy.recoveryDelayMs,
                    settings = settings,
                )
            }
        }

        // Fall back to the global protected-apps catalog (legacy behaviour).
        if (context.isProtectedApp) {
            return protect(
                action = settings.childAction,
                trigger = PolicyTrigger.PROTECTED_APP_OPENED,
                reason = "protected app",
                activationOverride = null,
                recoveryOverride = null,
                settings = settings,
            )
        }

        return PolicyDecision.Allow
    }

    /** Applies an identity-level action (unknown / no-face / obstruction). */
    private fun configured(
        action: ProtectionAction,
        trigger: PolicyTrigger,
        reason: String,
        settings: PolicySettings,
    ): PolicyDecision {
        if (action == ProtectionAction.ALLOW) return PolicyDecision.Allow
        if (action == ProtectionAction.WARNING) return PolicyDecision.Warn(reason)
        return PolicyDecision.Protect(
            action = action,
            activationDelayMs = settings.activationDelayMs,
            recoveryDelayMs = settings.recoveryDelayMs,
            trigger = trigger,
            reason = reason,
        )
    }

    private fun protect(
        action: ProtectionAction,
        trigger: PolicyTrigger,
        reason: String,
        activationOverride: Long?,
        recoveryOverride: Long?,
        settings: PolicySettings,
    ): PolicyDecision {
        if (action == ProtectionAction.ALLOW) return PolicyDecision.Allow
        if (action == ProtectionAction.WARNING) return PolicyDecision.Warn(reason)
        return PolicyDecision.Protect(
            action = action,
            activationDelayMs = activationOverride ?: settings.activationDelayMs,
            recoveryDelayMs = recoveryOverride ?: settings.recoveryDelayMs,
            trigger = trigger,
            reason = reason,
        )
    }
}

/** Convenience for callers that only care whether anything must be enforced. */
fun PolicyDecision.isEnforced(): Boolean =
    this is PolicyDecision.Protect && action.isRestrictive
