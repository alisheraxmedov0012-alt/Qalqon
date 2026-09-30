package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.EyeSafetyState
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Phase 6 Step 2 (pure JVM): the eye-safety policy integration.
 *
 * These drive the *production* [DefaultPolicyEvaluator] through its public `evaluate`, so they
 * prove the shipped composition — ordinary child policy → eye safety → schedule — rather than a
 * re-implementation of it. Phase 5's own suite already covers the schedule layer; this file
 * covers eye safety on its own and its interaction with that layer.
 */
class EyeSafetyPolicyTest {

    private val evaluator = DefaultPolicyEvaluator()
    private val pkg = "com.example.youtube"

    /** Settings whose ordinary protected-app action is a mere warning, so tightening is visible. */
    private val base = PolicySettings(
        enabled = true,
        activationDelayMs = 3_000L,
        childAction = ProtectionAction.WARNING,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    private fun context(
        identity: UserIdentity = UserIdentity.CHILD,
        settings: PolicySettings = base,
        childId: Long? = 1L,
        appPolicy: AppPolicy? = null,
        isProtectedApp: Boolean = true,
        eyeSafetyState: EyeSafetyState = EyeSafetyState.UNKNOWN,
        scheduleResolution: ScheduleResolution = ScheduleResolution.NoActiveSchedule,
        deviceOwner: DeviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
    ) = PolicyContext(
        identity = IdentityContext(
            identity = identity,
            childId = if (identity == UserIdentity.CHILD) childId else null,
            confidence = 0.9f,
        ),
        settings = settings,
        foregroundPackage = pkg,
        deviceOwnerMode = deviceOwner,
        appPolicy = appPolicy,
        isProtectedApp = isProtectedApp,
        scheduleResolution = scheduleResolution,
        eyeSafetyState = eyeSafetyState,
    )

    /** An explicit BLOCK policy whose own action is [action]. */
    private fun blocked(action: ProtectionAction) =
        AppPolicy(packageName = pkg, mode = AppPolicyMode.BLOCK, action = action, childId = 1L)

    private fun allowed() = AppPolicy(packageName = pkg, mode = AppPolicyMode.ALLOW, childId = 1L)

    private fun schedule(action: ProtectionAction, id: Long = 1L) = ScheduleResolution.ActiveSchedule(
        ScheduleRule(
            id = id,
            name = "Schedule $id",
            mode = ScheduleMode.CUSTOM,
            window = ScheduleWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)),
            days = ScheduleDays.of(DayOfWeek.MONDAY),
            action = action,
        ),
    )

    /** The action the decision asks for (ALLOW when nothing is enforced). */
    private fun actionOf(decision: PolicyDecision): ProtectionAction = when (decision) {
        is PolicyDecision.Allow -> ProtectionAction.ALLOW
        is PolicyDecision.Warn -> ProtectionAction.WARNING
        is PolicyDecision.Protect -> decision.action
    }

    private fun assertProtect(action: ProtectionAction, decision: PolicyDecision) {
        assertTrue("expected Protect($action) but was $decision", decision is PolicyDecision.Protect)
        assertEquals(action, (decision as PolicyDecision.Protect).action)
    }

    // ============================================================
    // A. UNKNOWN is a no-op
    // ============================================================

    @Test
    fun a_unknownLeavesAnAllowedAppAllowed() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed(), eyeSafetyState = EyeSafetyState.UNKNOWN)),
        )
    }

    @Test
    fun a_unknownLeavesEveryExistingRestrictionExactlyAsItWas() {
        listOf(
            ProtectionAction.WARNING,
            ProtectionAction.MUTE,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
        ).forEach { existing ->
            val settings = base.copy(
                eyeSafetyWarningAction = ProtectionAction.HARD_BLOCK,
                eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK,
            )
            val withUnknown = evaluator.evaluate(
                context(settings = settings, appPolicy = blocked(existing), eyeSafetyState = EyeSafetyState.UNKNOWN),
            )
            val without = evaluator.evaluate(context(settings = settings, appPolicy = blocked(existing)))

            assertEquals("$existing must be unchanged by an UNKNOWN eye-safety state", without, withUnknown)
        }
    }

    @Test
    fun a_unknownDoesNotRestrictAProtectedAppByItself() {
        // UNKNOWN contributes nothing, so the decision is bit-for-bit the no-eye-safety baseline.
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK)
        val with = evaluator.evaluate(context(settings = settings, eyeSafetyState = EyeSafetyState.UNKNOWN))
        val without = evaluator.evaluate(context(settings = settings))

        assertEquals(without, with)
        assertTrue("the ordinary protected-app path only warns here", with is PolicyDecision.Warn)
        assertEquals(ProtectionAction.WARNING, actionOf(with))
    }

    // ============================================================
    // B. SAFE is a no-op
    // ============================================================

    @Test
    fun b_safeLeavesEveryExistingActionUntouched() {
        listOf(
            ProtectionAction.ALLOW,
            ProtectionAction.WARNING,
            ProtectionAction.MUTE,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
        ).forEach { existing ->
            val settings = base.copy(
                eyeSafetyWarningAction = ProtectionAction.HARD_BLOCK,
                eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK,
            )
            val policy = if (existing == ProtectionAction.ALLOW) allowed() else blocked(existing)
            val withSafe = evaluator.evaluate(context(settings = settings, appPolicy = policy, eyeSafetyState = EyeSafetyState.SAFE))
            val without = evaluator.evaluate(context(settings = settings, appPolicy = policy))

            assertEquals("$existing must be unchanged by a SAFE eye-safety state", without, withSafe)
        }
    }

    @Test
    fun b_safeDoesNotRestrictAProtectedAppByItself() {
        val settings = base.copy(eyeSafetyWarningAction = ProtectionAction.HARD_BLOCK)
        val with = evaluator.evaluate(context(settings = settings, eyeSafetyState = EyeSafetyState.SAFE))
        val without = evaluator.evaluate(context(settings = settings))

        assertEquals(without, with)
        assertEquals(ProtectionAction.WARNING, actionOf(with))
    }

    // ============================================================
    // C. WARNING applies the parent-configured warning action
    // ============================================================

    @Test
    fun c_warningTightensAnAllowedAppToTheWarningAction() {
        ProtectionAction.entries.filter { it in IMPLEMENTED }.forEach { warningAction ->
            val decision = evaluator.evaluate(
                context(
                    settings = base.copy(eyeSafetyWarningAction = warningAction),
                    appPolicy = allowed(),
                    eyeSafetyState = EyeSafetyState.WARNING,
                ),
            )

            assertEquals(warningAction, actionOf(decision))
        }
    }

    @Test
    fun c_warningNeverWeakensAnExistingStrongerRestriction() {
        // Existing SOFT_BLOCK (3) vs warning action WARNING (1): SOFT_BLOCK must stand.
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.WARNING),
                appPolicy = blocked(ProtectionAction.SOFT_BLOCK),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.PROTECTED_APP_OPENED, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun c_warningNeverWeakensAnExistingHardBlock() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.WARNING),
                appPolicy = blocked(ProtectionAction.HARD_BLOCK),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun c_warningUsesItsOwnTriggerWhenItIsTheStrongerSource() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.HARD_BLOCK),
                appPolicy = allowed(),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        assertEquals(PolicyTrigger.EYE_SAFETY_WARNING, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun c_warningUsesTheSettingsDelaysBecauseEyeSafetyHasNoneOfItsOwn() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.HARD_BLOCK),
                appPolicy = blocked(ProtectionAction.SOFT_BLOCK).copy(activationDelayMs = 9_999L, recoveryDelayMs = 8_888L),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        val protect = decision as PolicyDecision.Protect
        assertEquals(base.activationDelayMs, protect.activationDelayMs)
        assertEquals(base.recoveryDelayMs, protect.recoveryDelayMs)
    }

    // ============================================================
    // D. DANGER applies the parent-configured danger action
    // ============================================================

    @Test
    fun d_dangerTightensAnAllowedAppToTheDangerAction() {
        ProtectionAction.entries.filter { it in IMPLEMENTED }.forEach { dangerAction ->
            val decision = evaluator.evaluate(
                context(
                    settings = base.copy(eyeSafetyDangerAction = dangerAction),
                    appPolicy = allowed(),
                    eyeSafetyState = EyeSafetyState.DANGER,
                ),
            )

            assertEquals(dangerAction, actionOf(decision))
        }
    }

    @Test
    fun d_dangerNeverWeakensAnExistingStrongerRestriction() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK),
                appPolicy = blocked(ProtectionAction.HARD_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun d_dangerWithAnAllowActionIsANoOp() {
        // ALLOW is a legitimate configuration meaning "do not restrict", and it cannot unlock the
        // ordinary protected-app decision either — the baseline is unchanged.
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.ALLOW)
        val with = evaluator.evaluate(context(settings = settings, eyeSafetyState = EyeSafetyState.DANGER))
        val without = evaluator.evaluate(context(settings = settings))

        assertEquals(without, with)
        assertEquals(ProtectionAction.WARNING, actionOf(with))
    }

    @Test
    fun d_dangerWithAnExplicitAllowAppPolicyRemainsAllowed() {
        // An explicit child ALLOW is not a restriction to tighten, and eye safety cannot invent one
        // beyond its own configured action — with ALLOW configured there is nothing to apply.
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(
                context(
                    settings = base.copy(eyeSafetyDangerAction = ProtectionAction.ALLOW),
                    appPolicy = allowed(),
                    eyeSafetyState = EyeSafetyState.DANGER,
                ),
            ),
        )
    }

    @Test
    fun d_dangerUsesItsOwnTriggerWhenItIsTheStrongerSource() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK),
                appPolicy = allowed(),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, (decision as PolicyDecision.Protect).trigger)
    }

    // ============================================================
    // E. MUTE keeps its existing semantics
    // ============================================================

    @Test
    fun e_eyeSafetyMuteDoesNotWeakenAnExistingSoftBlock() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.MUTE),
                appPolicy = blocked(ProtectionAction.SOFT_BLOCK),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
    }

    @Test
    fun e_anExistingMuteIsNotWeakenedByAWeakerEyeSafetyAction() {
        // MUTE (2) vs warning action WARNING (1): MUTE stands.
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.WARNING),
                appPolicy = blocked(ProtectionAction.MUTE),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        assertProtect(ProtectionAction.MUTE, decision)
    }

    @Test
    fun e_anEyeSafetyBlockTightensAnExistingMute() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK),
                appPolicy = blocked(ProtectionAction.MUTE),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
    }

    @Test
    fun e_eyeSafetyMuteOnAnAllowedAppIsMuteAndNothingMore() {
        // There is no composite "MUTE + block" action: MUTE is enforced as MUTE.
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyWarningAction = ProtectionAction.MUTE),
                appPolicy = allowed(),
                eyeSafetyState = EyeSafetyState.WARNING,
            ),
        )

        assertProtect(ProtectionAction.MUTE, decision)
    }

    // ============================================================
    // F/G. parent, unknown user, no face — eye safety is never consulted
    // ============================================================

    @Test
    fun f_parentIsNeverRestrictedByEyeSafety() {
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK)

        EyeSafetyState.entries.forEach { state ->
            assertEquals(
                "a parent must not be restricted by eye safety ($state)",
                PolicyDecision.Allow,
                evaluator.evaluate(
                    context(
                        identity = UserIdentity.PARENT,
                        settings = settings,
                        appPolicy = blocked(ProtectionAction.HARD_BLOCK),
                        eyeSafetyState = state,
                    ),
                ),
            )
        }
    }

    @Test
    fun f_parentIsUnaffectedEvenWithoutAConsultedChildPolicy() {
        val without = evaluator.evaluate(context(identity = UserIdentity.PARENT))
        val with = evaluator.evaluate(
            context(
                identity = UserIdentity.PARENT,
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertEquals(without, with)
    }

    @Test
    fun g_unknownUserKeepsItsOwnPolicyRatherThanEyeSafety() {
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK)

        val withDanger = evaluator.evaluate(
            context(identity = UserIdentity.UNKNOWN, settings = settings, eyeSafetyState = EyeSafetyState.DANGER),
        )
        val without = evaluator.evaluate(context(identity = UserIdentity.UNKNOWN, settings = settings))

        assertEquals(without, withDanger)
        assertEquals(PolicyTrigger.UNKNOWN_USER, (withDanger as PolicyDecision.Protect).trigger)
    }

    @Test
    fun g_noFaceKeepsItsOwnPolicyRatherThanEyeSafety() {
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK)

        val withDanger = evaluator.evaluate(
            context(identity = UserIdentity.NO_FACE, settings = settings, eyeSafetyState = EyeSafetyState.DANGER),
        )

        assertEquals(PolicyDecision.Allow, withDanger)
        assertEquals(withDanger, evaluator.evaluate(context(identity = UserIdentity.NO_FACE, settings = settings)))
    }

    @Test
    fun g_anUnknownStateIsNeverTreatedAsSafeByTheEvaluator() {
        // The evaluator consumes the state verbatim: it neither upgrades UNKNOWN to SAFE nor
        // downgrades it, so the domain's "no measurement" semantics survive the policy layer.
        val settings = base.copy(
            eyeSafetyWarningAction = ProtectionAction.HARD_BLOCK,
            eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK,
        )
        val unknown = evaluator.evaluate(context(settings = settings, appPolicy = allowed(), eyeSafetyState = EyeSafetyState.UNKNOWN))

        assertEquals(PolicyDecision.Allow, unknown)
    }

    // ============================================================
    // H. protected-app boundary
    // ============================================================

    @Test
    fun h_eyeSafetyAppliesInsideAProtectedApp() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                appPolicy = allowed(),
                isProtectedApp = true,
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun h_eyeSafetyIsANoOpOutsideTheProtectedAppGate() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                appPolicy = null,
                isProtectedApp = false,
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertEquals("eye safety must not restrict an unprotected app", PolicyDecision.Allow, decision)
    }

    @Test
    fun h_anUnprotectedAppIsUnaffectedEvenWithAnExistingAppPolicy() {
        // The app policy itself is what restricts here; eye safety adds nothing outside the gate.
        val with = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK),
                appPolicy = blocked(ProtectionAction.HARD_BLOCK),
                isProtectedApp = false,
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )
        val without = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.HARD_BLOCK), isProtectedApp = false),
        )

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(with))
        assertEquals(without, with)
    }

    // ============================================================
    // I. schedule interaction
    // ============================================================

    @Test
    fun i_scheduleAllowWithEyeSafetyHardBlockIsHardBlock() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                appPolicy = allowed(),
                scheduleResolution = schedule(ProtectionAction.ALLOW),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun i_scheduleSoftBlockWithEyeSafetyHardBlockIsHardBlock() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                appPolicy = allowed(),
                scheduleResolution = schedule(ProtectionAction.SOFT_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun i_scheduleHardBlockWithEyeSafetyAllowIsHardBlock() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.ALLOW),
                appPolicy = allowed(),
                scheduleResolution = schedule(ProtectionAction.HARD_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun i_scheduleWarningWithEyeSafetySoftBlockIsSoftBlock() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK),
                appPolicy = allowed(),
                scheduleResolution = schedule(ProtectionAction.WARNING),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
    }

    @Test
    fun i_theStrongerOfEyeSafetyAndScheduleWins() {
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK)

        // Eye safety weaker than the schedule: the schedule's action stands.
        assertProtect(
            ProtectionAction.HARD_BLOCK,
            evaluator.evaluate(
                context(
                    settings = settings,
                    appPolicy = allowed(),
                    scheduleResolution = schedule(ProtectionAction.HARD_BLOCK),
                    eyeSafetyState = EyeSafetyState.DANGER,
                ),
            ),
        )
    }

    @Test
    fun i_eyeSafetyAndScheduleRankTiesKeepEyeSafetyProvenance() {
        // Deterministic composition rule: the outer schedule layer only overrides on a *strictly*
        // greater rank, so an equal eye-safety rank keeps the eye-safety trigger. No new tie-break
        // is introduced — the inner layer's provenance survives, exactly as it does for the
        // ordinary decision under a schedule.
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK),
                appPolicy = allowed(),
                scheduleResolution = schedule(ProtectionAction.SOFT_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun i_bothLayersAreNoOpsForAnUntargetedUnprotectedApp() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                isProtectedApp = false,
                scheduleResolution = schedule(ProtectionAction.HARD_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun i_eyeSafetyWithNoActiveScheduleStillApplies() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                appPolicy = allowed(),
                scheduleResolution = ScheduleResolution.NoActiveSchedule,
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, (decision as PolicyDecision.Protect).trigger)
    }

    // ============================================================
    // J. screen time is not weakened
    // ============================================================

    @Test
    fun j_screenTimeExceededIsNotWeakenedByAWakerEyeSafetyState() {
        val limited = AppPolicy(
            packageName = pkg,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.HARD_BLOCK,
            dailyLimitMinutes = 30,
            childId = 1L,
        )

        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.ALLOW),
                appPolicy = limited,
                eyeSafetyState = EyeSafetyState.DANGER,
            ).let { it.copy(appTimeUsedMinutes = 30) },
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
        assertEquals(PolicyTrigger.SCREEN_TIME_EXCEEDED, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun j_screenTimeWithinLimitIsStillAllowedWhenEyeSafetyIsAlsoANoOp() {
        val limited = AppPolicy(
            packageName = pkg,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = 1L,
        )

        val decision = evaluator.evaluate(
            context(appPolicy = limited, eyeSafetyState = EyeSafetyState.UNKNOWN)
                .let { it.copy(appTimeUsedMinutes = 10) },
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun j_eyeSafetyTightensAWithinLimitAppWithoutTouchingTheLimitItself() {
        val limited = AppPolicy(
            packageName = pkg,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = 1L,
        )

        val decision = evaluator.evaluate(
            context(
                settings = base.copy(eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                appPolicy = limited,
                eyeSafetyState = EyeSafetyState.DANGER,
            ).let { it.copy(appTimeUsedMinutes = 10) },
        )

        // The limit was not reached, so the app was allowed — and eye safety then tightened it.
        assertProtect(ProtectionAction.HARD_BLOCK, decision)
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun j_anUnknownUsageMeasurementIsNotTreatedAsZero() {
        val limited = AppPolicy(
            packageName = pkg,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 0,
            childId = 1L,
        )

        // null usage = "not measured", so even a 0-minute limit must not fire.
        val decision = evaluator.evaluate(context(appPolicy = limited, eyeSafetyState = EyeSafetyState.UNKNOWN))

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ============================================================
    // K. determinism and restriction-only behaviour
    // ============================================================

    @Test
    fun k_theSameInputAlwaysProducesTheSameDecision() {
        val settings = base.copy(
            eyeSafetyWarningAction = ProtectionAction.MUTE,
            eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK,
        )
        val context = context(
            settings = settings,
            appPolicy = blocked(ProtectionAction.SOFT_BLOCK),
            scheduleResolution = schedule(ProtectionAction.WARNING),
            eyeSafetyState = EyeSafetyState.DANGER,
        )

        val first = evaluator.evaluate(context)
        repeat(10) { assertEquals(first, evaluator.evaluate(context)) }
    }

    @Test
    fun k_eyeSafetyOnlyEverTightens() {
        // For every combination, the final action is at least as restrictive as the ordinary
        // decision's action — eye safety can never loosen it.
        val ordinaryActions = listOf(
            ProtectionAction.WARNING,
            ProtectionAction.MUTE,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
        )
        val eyeActions = listOf(ProtectionAction.ALLOW, ProtectionAction.WARNING, ProtectionAction.MUTE, ProtectionAction.SOFT_BLOCK, ProtectionAction.HARD_BLOCK)

        ordinaryActions.forEach { ordinary ->
            eyeActions.forEach { eyeAction ->
                val decision = evaluator.evaluate(
                    context(
                        settings = base.copy(eyeSafetyDangerAction = eyeAction),
                        appPolicy = blocked(ordinary),
                        eyeSafetyState = EyeSafetyState.DANGER,
                    ),
                )
                val finalAction = actionOf(decision)
                assertTrue(
                    "ordinary $ordinary + eye $eyeAction produced $finalAction, which is less restrictive",
                    rank(finalAction) >= rank(ordinary),
                )
            }
        }
    }

    @Test
    fun k_aStrongerEyeSafetyActionIsNeverDowngradedByAddingASchedule() {
        val settings = base.copy(eyeSafetyDangerAction = ProtectionAction.SOFT_BLOCK)
        val withSchedule = evaluator.evaluate(
            context(
                settings = settings,
                appPolicy = allowed(),
                scheduleResolution = schedule(ProtectionAction.WARNING),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )
        val withoutSchedule = evaluator.evaluate(
            context(settings = settings, appPolicy = allowed(), eyeSafetyState = EyeSafetyState.DANGER),
        )

        assertEquals(actionOf(withoutSchedule), actionOf(withSchedule))
    }

    @Test
    fun k_theParentDeviceBypassIsNotReintroducedByEyeSafety() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(
                    parentDeviceChildPolicyEnabled = false,
                    eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK,
                ),
                appPolicy = blocked(ProtectionAction.HARD_BLOCK),
                deviceOwner = DeviceOwnerMode.PARENT_DEVICE,
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun k_protectionDisabledLeavesEyeSafetyInert() {
        val decision = evaluator.evaluate(
            context(
                settings = base.copy(enabled = false, eyeSafetyDangerAction = ProtectionAction.HARD_BLOCK),
                eyeSafetyState = EyeSafetyState.DANGER,
            ),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    private fun rank(action: ProtectionAction): Int = when (action) {
        ProtectionAction.ALLOW -> 0
        ProtectionAction.WARNING -> 1
        ProtectionAction.MUTE -> 2
        ProtectionAction.SOFT_BLOCK -> 3
        ProtectionAction.HARD_BLOCK -> 4
        else -> 0
    }

    private companion object {
        /** The actions a parent may actually choose; DIM/BLUR/BLACK_SCREEN are not enforceable. */
        val IMPLEMENTED = setOf(
            ProtectionAction.ALLOW,
            ProtectionAction.WARNING,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
            ProtectionAction.MUTE,
        )
    }
}
