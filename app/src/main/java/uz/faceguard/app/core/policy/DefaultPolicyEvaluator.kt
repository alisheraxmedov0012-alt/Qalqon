package uz.faceguard.app.core.policy

import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.EyeSafetyState
import uz.faceguard.app.domain.policy.LivenessPolicy
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicyEvaluator
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.policy.isRestrictive
import uz.faceguard.app.domain.policy.restrictionRank
import uz.faceguard.app.domain.schedule.ScheduleResolution

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

        // 2. A spoofed presentation is never trusted. A printed photo, screen or
        //    replay that is *recognised* as the parent (or child) must not inherit
        //    that identity's policy, so this gate runs before the identity switch.
        //    The combination rule is the explicit, unit-tested
        //    [uz.faceguard.app.domain.policy.LivenessPolicy]: only a positive SPOOF
        //    determination overrides a recognised identity. Undecided liveness
        //    (LIVE/UNKNOWN/UNSTABLE/NO_FACE) falls through unchanged, preserving all
        //    pre-Stage-3 behaviour (see that object's documentation for why an
        //    undecided observation is not turned into a hard block).
        if (LivenessPolicy.overridesIdentity(context.liveness)) {
            return configured(
                settings.spoofAction,
                PolicyTrigger.LIVENESS_SPOOF,
                "spoofed presentation",
                settings,
            )
        }

        return when (context.identity.identity) {
            // 3. Parent always wins, on both device modes.
            UserIdentity.PARENT -> PolicyDecision.Allow

            // 4. Child -> app-scoped policy, then global protected app, then the restriction
            //    layers: eye safety (Phase 6) and schedules (Phase 5), in that order. Both run only
            //    for a recognised child, so a recognised parent (branch 3) is never restricted by
            //    either.
            //
            //    The parent-device bypass is resolved *here*, around the whole child evaluation,
            //    so a parent device configured to skip child enforcement cannot have it
            //    reintroduced by a schedule or by eye safety.
            UserIdentity.CHILD -> if (childPolicyBypassed(context, settings)) {
                PolicyDecision.Allow
            } else {
                applySchedule(
                    applyEyeSafety(evaluateChild(context, settings), context, settings),
                    context,
                    settings,
                )
            }

            // 5. Unknown / 6. obstructed / 7. no face follow configured actions.
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
                    // Phase 4 Step 4: the comparison is unchanged (`used >= limit`, equality is
                    // reached), but it only runs on a real measurement. A null measurement means
                    // usage is unknown — never zero — so no screen-time restriction is derived
                    // from it. The app's own BLOCK/ALLOW policy is unaffected either way.
                    val used = context.appTimeUsedMinutes
                    val exceeded = limit != null && used != null && used >= limit
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

    /**
     * Phase 6 Step 2: the eye-safety restriction layer, applied on top of the ordinary child
     * decision (and below the schedule layer).
     *
     * Eye safety is a *restriction* layer like a schedule: the result is the more restrictive of
     * the ordinary decision's action and the parent-configured eye-safety action
     * ([restrictionRank]), so a warning or danger can tighten a decision but can never unlock an app
     * the child's own policy restricts. Because the combination is a maximum over an ordered rank,
     * the resulting **action** does not depend on whether eye safety or a schedule was applied
     * first.
     *
     * It participates only when every one of these holds:
     *  - the recognised user is a child. This runs inside the `CHILD` branch, so a recognised parent
     *    is never restricted by eye safety (parent precedence is unchanged), and
     *    unknown/no-face/obstructed keep following their own configured actions. Eye safety is never
     *    consulted for a parent, so it cannot restrict a parent's own device use;
     *  - the foreground package is one the existing protected-app gate already covers
     *    ([PolicyContext.isProtectedApp]). Eye safety never makes an unprotected app protected and
     *    never restricts outside that gate;
     *  - the state is [EyeSafetyState.WARNING] or [EyeSafetyState.DANGER]. [EyeSafetyState.UNKNOWN]
     *    (no measurement — which is also what "no face" reports, never "far away") and
     *    [EyeSafetyState.SAFE] are explicit no-ops, so an unmeasured or comfortable child keeps
     *    exactly the decision they had before.
     *
     * The [PolicyTrigger] names eye safety only when it is the strictly stronger source, mirroring
     * [applySchedule]: a state whose action is not more restrictive than the decision it was given
     * leaves that decision — and its trigger and reason — untouched.
     */
    private fun applyEyeSafety(
        decision: PolicyDecision,
        context: PolicyContext,
        settings: PolicySettings,
    ): PolicyDecision {
        // Never broaden the protected-app gate: an unprotected app is untouched by eye safety.
        if (!context.isProtectedApp) return decision

        val (stateAction, stateTrigger, stateReason) = when (context.eyeSafetyState) {
            EyeSafetyState.UNKNOWN,
            EyeSafetyState.SAFE,
            -> return decision

            EyeSafetyState.WARNING -> Triple(
                settings.eyeSafetyWarningAction,
                PolicyTrigger.EYE_SAFETY_WARNING,
                "eye safety warning",
            )

            EyeSafetyState.DANGER -> Triple(
                settings.eyeSafetyDangerAction,
                PolicyTrigger.EYE_SAFETY_DANGER,
                "eye safety danger",
            )
        }

        if (stateAction.restrictionRank <= decision.action().restrictionRank) return decision

        return protect(
            action = stateAction,
            trigger = stateTrigger,
            reason = stateReason,
            activationOverride = null,
            recoveryOverride = null,
            settings = settings,
        )
    }

    /**
     * True when this device is a parent device that is configured to not apply child policy at
     * all. Resolved before [evaluateChild] and before the restriction layers, so neither the child's
     * app policies, their schedules nor eye safety can restrict the device when the parent has opted
     * out.
     */
    private fun childPolicyBypassed(context: PolicyContext, settings: PolicySettings): Boolean =
        context.deviceOwnerMode == DeviceOwnerMode.PARENT_DEVICE &&
            !settings.parentDeviceChildPolicyEnabled

    /**
     * Phase 5 Step 4: the schedule restriction layer, applied on top of the ordinary child
     * decision.
     *
     * A schedule may only ever *add* restriction. The result is the more restrictive of the
     * ordinary decision's action and the schedule's action ([restrictionRank]), so a schedule
     * `ALLOW` can never unlock an app the ordinary child/app policy restricts — a schedule is a
     * restriction layer, never a permission grant.
     *
     * It participates only when every one of these holds:
     *  - the recognised user is a child. This runs inside the `CHILD` branch, so a recognised
     *    parent is never restricted by a schedule (parent precedence is unchanged), and
     *    unknown/no-face/obstructed keep following their own configured actions;
     *  - the foreground package is one the existing protected-app gate already covers
     *    ([PolicyContext.isProtectedApp]). A schedule never makes an unprotected app protected
     *    and never broadens that gate;
     *  - the package is one of the schedule's affected apps, which the caller encodes as
     *    [ScheduleResolution.NoActiveSchedule] for every other package.
     *
     * A [ScheduleResolution.ScheduleConflict] is preserved and never silently resolved to one
     * schedule: all tied schedules are equally authoritative, so enforcement takes the strongest
     * action among them. That is a maximum over the tied set, so it is independent of id, name,
     * mode and collection order — no tie-break is introduced.
     *
     * A schedule's `mode` is descriptive metadata and is deliberately never consulted here: only
     * the schedule's explicit `action` restricts anything.
     */
    private fun applySchedule(
        decision: PolicyDecision,
        context: PolicyContext,
        settings: PolicySettings,
    ): PolicyDecision {
        // Never broaden the protected-app gate: an untargeted or unprotected app is untouched.
        if (!context.isProtectedApp) return decision

        val scheduleAction = when (val resolution = context.scheduleResolution) {
            ScheduleResolution.NoActiveSchedule -> return decision

            is ScheduleResolution.ActiveSchedule -> resolution.schedule.action

            is ScheduleResolution.ScheduleConflict ->
                resolution.schedules.maxByOrNull { it.action.restrictionRank }?.action
                    ?: return decision
        }

        val ordinaryAction = decision.action()
        if (scheduleAction.restrictionRank <= ordinaryAction.restrictionRank) return decision

        return protect(
            action = scheduleAction,
            trigger = PolicyTrigger.SCHEDULE_ACTIVE,
            reason = scheduleReason(context.scheduleResolution),
            activationOverride = null,
            recoveryOverride = null,
            settings = settings,
        )
    }

    /** The action a decision currently asks for, for comparison against a schedule's action. */
    private fun PolicyDecision.action(): ProtectionAction = when (this) {
        is PolicyDecision.Allow -> ProtectionAction.ALLOW
        is PolicyDecision.Warn -> ProtectionAction.WARNING
        is PolicyDecision.Protect -> action
    }

    /** Human-readable schedule provenance for the decision reason (logs/UI), not a rule input. */
    private fun scheduleReason(resolution: ScheduleResolution): String = when (resolution) {
        ScheduleResolution.NoActiveSchedule -> "schedule"
        is ScheduleResolution.ActiveSchedule -> "schedule: ${resolution.schedule.name}"
        is ScheduleResolution.ScheduleConflict ->
            "schedule conflict (${resolution.schedules.joinToString(", ") { it.name }})"
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
