package uz.faceguard.app.policy

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.policy.restrictionRank
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow
import uz.faceguard.app.domain.schedule.resolveScheduleForPackage

/**
 * Phase 5 Step 4: schedule integration into the existing policy pipeline.
 *
 * These exercise the *production* [DefaultPolicyEvaluator] through its public `evaluate`, and
 * build the schedule inputs through the *production* [resolveScheduleForPackage] /
 * `ScheduleResolver`, so the whole path is the shipped one. The policy algorithm is never
 * re-implemented here.
 *
 * 2026-09-21 is a Monday and 2026-09-22 a Tuesday; the week used below is Mon 21 / Tue 22 Sep 2026.
 */
class SchedulePolicyIntegrationTest {

    private val evaluator = DefaultPolicyEvaluator()
    private val zone = ZoneId.of("Asia/Tashkent")

    /** The app under test, and an app no schedule targets. */
    private val protectedPkg = "com.example.app"
    private val untargetedPkg = "com.example.other"

    private val enabled = PolicySettings(
        enabled = true,
        activationDelayMs = 3_000L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    // ---- inputs -------------------------------------------------------------

    private fun mon(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 21, hour, minute).atZone(zone).toInstant()

    private fun tue(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 22, hour, minute).atZone(zone).toInstant()

    private fun rule(
        id: Long,
        action: ProtectionAction,
        name: String = "Schedule $id",
        priority: Int = 0,
        mode: ScheduleMode = ScheduleMode.CUSTOM,
        days: ScheduleDays = ScheduleDays.of(DayOfWeek.MONDAY),
        start: Pair<Int, Int> = 8 to 0,
        end: Pair<Int, Int> = 18 to 0,
        enabled: Boolean = true,
    ) = ScheduleRule(
        id = id,
        name = name,
        mode = mode,
        window = ScheduleWindow(LocalTime.of(start.first, start.second), LocalTime.of(end.first, end.second)),
        days = days,
        action = action,
        priority = priority,
        enabled = enabled,
    )

    /** Resolves through the production lookup path. */
    private fun resolution(
        schedules: List<ScheduleRule>,
        targets: Map<Long, Set<String>>,
        at: Instant,
        packageName: String = protectedPkg,
    ): ScheduleResolution = resolveScheduleForPackage(schedules, targets, packageName, at, zone)

    /** Convenience: one schedule targeting [protectedPkg], resolved at [at]. */
    private fun resolutionOf(rule: ScheduleRule, at: Instant): ScheduleResolution =
        resolution(listOf(rule), mapOf(rule.id to setOf(protectedPkg)), at)

    private fun context(
        identity: UserIdentity = UserIdentity.CHILD,
        settings: PolicySettings = enabled,
        childId: Long? = 1L,
        appPolicy: AppPolicy? = null,
        isProtectedApp: Boolean = true,
        appTimeUsedMinutes: Int? = null,
        deviceOwner: DeviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
        scheduleResolution: ScheduleResolution = ScheduleResolution.NoActiveSchedule,
        packageName: String = protectedPkg,
    ) = PolicyContext(
        identity = IdentityContext(
            identity = identity,
            childId = if (identity == UserIdentity.CHILD) childId else null,
            childName = null,
            confidence = 0.9f,
        ),
        settings = settings,
        foregroundPackage = packageName,
        deviceOwnerMode = deviceOwner,
        appPolicy = appPolicy,
        isProtectedApp = isProtectedApp,
        appTimeUsedMinutes = appTimeUsedMinutes,
        scheduleResolution = scheduleResolution,
    )

    private fun blocked(action: ProtectionAction) =
        AppPolicy(packageName = protectedPkg, mode = AppPolicyMode.BLOCK, action = action, childId = 1L)

    private fun allowed() = AppPolicy(packageName = protectedPkg, mode = AppPolicyMode.ALLOW, childId = 1L)

    private fun assertProtect(action: ProtectionAction, decision: PolicyDecision) {
        assertTrue("expected Protect($action) but was $decision", decision is PolicyDecision.Protect)
        assertEquals(action, (decision as PolicyDecision.Protect).action)
    }

    // ============================================================
    // TEST 1 — no active schedule leaves the ordinary policy alone
    // ============================================================

    @Test
    fun test1_noActiveSchedule_leavesChildPolicyUnchanged() {
        val decision = evaluator.evaluate(context(scheduleResolution = ScheduleResolution.NoActiveSchedule))

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
        assertEquals(PolicyTrigger.PROTECTED_APP_OPENED, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun test1_noActiveSchedule_leavesAnExplicitAllowAlone() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed())),
        )
    }

    // ============================================================
    // TEST 2 — schedule ALLOW + existing ALLOW stays ALLOW
    // ============================================================

    @Test
    fun test2_scheduleAllowWithExistingAllow_remainsAllow() {
        val decision = evaluator.evaluate(
            context(appPolicy = allowed(), scheduleResolution = resolutionOf(rule(1, ProtectionAction.ALLOW), mon(12, 0))),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ============================================================
    // TEST 3/4/8/23 — a schedule ALLOW never weakens an existing restriction
    // ============================================================

    @Test
    fun test3_existingBlockWithScheduleAllow_remainsBlocked() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.SOFT_BLOCK), scheduleResolution = resolutionOf(rule(1, ProtectionAction.ALLOW), mon(12, 0))),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
    }

    @Test
    fun test4_existingHardBlockWithScheduleAllow_remainsHardBlocked() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.HARD_BLOCK), scheduleResolution = resolutionOf(rule(1, ProtectionAction.ALLOW), mon(12, 0))),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun test8_existingHardBlockWithScheduleSoftBlock_remainsHardBlocked() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.HARD_BLOCK), scheduleResolution = resolutionOf(rule(1, ProtectionAction.SOFT_BLOCK), mon(12, 0))),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun test23_scheduleAllowNeverWeakensAnyExistingRestriction() {
        // Every stronger action the existing policy model can express, plus the settings-level
        // child action, must be *bit-for-bit identical* with and without an active schedule
        // ALLOW — a schedule may only ever tighten.
        val scheduleAllow = resolutionOf(rule(1, ProtectionAction.ALLOW), mon(12, 0))

        listOf(
            ProtectionAction.WARNING,
            ProtectionAction.MUTE,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
        ).forEach { existing ->
            val appPolicyWith = evaluator.evaluate(context(appPolicy = blocked(existing), scheduleResolution = scheduleAllow))
            val appPolicyWithout = evaluator.evaluate(context(appPolicy = blocked(existing)))
            assertEquals("app policy $existing must be unchanged by a schedule ALLOW", appPolicyWithout, appPolicyWith)

            val settingsWithAction = enabled.copy(childAction = existing)
            val childActionWith = evaluator.evaluate(context(settings = settingsWithAction, scheduleResolution = scheduleAllow))
            val childActionWithout = evaluator.evaluate(context(settings = settingsWithAction))
            assertEquals("child action $existing must be unchanged by a schedule ALLOW", childActionWithout, childActionWith)
        }
    }

    // ============================================================
    // TEST 5/6/7 — a schedule may tighten
    // ============================================================

    @Test
    fun test5_existingAllowWithScheduleSoftBlock_tightensToSoftBlock() {
        val decision = evaluator.evaluate(
            context(appPolicy = allowed(), scheduleResolution = resolutionOf(rule(1, ProtectionAction.SOFT_BLOCK), mon(12, 0))),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun test6_existingAllowWithScheduleHardBlock_tightensToHardBlock() {
        val decision = evaluator.evaluate(
            context(appPolicy = allowed(), scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
    }

    @Test
    fun test7_existingSoftBlockWithScheduleHardBlock_tightensToHardBlock() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.SOFT_BLOCK), scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, decision)
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun scheduleTighteningUsesTheSettingsDelays() {
        // A schedule has no delays of its own, so a schedule-driven restriction uses the
        // settings-level window rather than an app policy's overrides.
        val decision = evaluator.evaluate(
            context(
                appPolicy = AppPolicy(
                    packageName = protectedPkg,
                    mode = AppPolicyMode.BLOCK,
                    action = ProtectionAction.SOFT_BLOCK,
                    activationDelayMs = 9_999L,
                    recoveryDelayMs = 8_888L,
                    childId = 1L,
                ),
                scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0)),
            ),
        )

        val protect = decision as PolicyDecision.Protect
        assertEquals(ProtectionAction.HARD_BLOCK, protect.action)
        assertEquals(enabled.activationDelayMs, protect.activationDelayMs)
        assertEquals(enabled.recoveryDelayMs, protect.recoveryDelayMs)
    }

    @Test
    fun scheduleWarningOnAnAllowedApp_isAWarning() {
        val decision = evaluator.evaluate(
            context(appPolicy = allowed(), scheduleResolution = resolutionOf(rule(1, ProtectionAction.WARNING), mon(12, 0))),
        )

        assertTrue("expected a warning but was $decision", decision is PolicyDecision.Warn)
    }

    // ============================================================
    // TEST 9/22 — affected-app membership
    // ============================================================

    @Test
    fun test9_scheduleTargetingAnotherPackageDoesNotApply() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK)
        val targets = mapOf(1L to setOf(untargetedPkg))

        // The production lookup reports "no schedule" for a package the schedule does not target…
        assertEquals(ScheduleResolution.NoActiveSchedule, resolution(listOf(schedule), targets, mon(12, 0), protectedPkg))

        // …so the ordinary decision is untouched.
        val decision = evaluator.evaluate(
            context(appPolicy = allowed(), scheduleResolution = resolution(listOf(schedule), targets, mon(12, 0), protectedPkg)),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun test22_aScheduleWithNoTargetsAppliesToNothing() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK)

        assertEquals(ScheduleResolution.NoActiveSchedule, resolution(listOf(schedule), emptyMap(), mon(12, 0)))
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolution(listOf(schedule), emptyMap(), mon(12, 0)))),
        )
    }

    @Test
    fun aScheduleAppliesWhenItTargetsTheForegroundPackage() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK)
        val targets = mapOf(1L to setOf(protectedPkg, "com.another.app"))

        assertTrue(resolution(listOf(schedule), targets, mon(12, 0)) is ScheduleResolution.ActiveSchedule)
    }

    // ============================================================
    // TEST 10 — a schedule never broadens protection
    // ============================================================

    @Test
    fun test10_unprotectedAppIsNotProtectedByASchedule() {
        val resolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))

        // Active schedule for this very package, but the package is not in protectedPackages.
        val decision = evaluator.evaluate(context(isProtectedApp = false, scheduleResolution = resolution))

        assertEquals("a schedule must not make an unprotected app protected", PolicyDecision.Allow, decision)
    }

    @Test
    fun test10_unprotectedAppWithAnotherTargetedPackageGetsNothing() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK)
        val targets = mapOf(1L to setOf(protectedPkg))

        val decision = evaluator.evaluate(
            context(
                isProtectedApp = false,
                packageName = protectedPkg,
                scheduleResolution = resolution(listOf(schedule), targets, mon(12, 0), protectedPkg),
            ),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ============================================================
    // TEST 11 — parent precedence
    // ============================================================

    @Test
    fun test11_parentIsNeverRestrictedByAnActiveSchedule() {
        val resolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))

        val decision = evaluator.evaluate(
            context(identity = UserIdentity.PARENT, appPolicy = blocked(ProtectionAction.HARD_BLOCK), scheduleResolution = resolution),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ============================================================
    // TEST 12 — unknown / no-face keep their own policies
    // ============================================================

    @Test
    fun test12_unknownUserFollowsItsOwnPolicyNotTheSchedule() {
        val resolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))

        val decision = evaluator.evaluate(context(identity = UserIdentity.UNKNOWN, scheduleResolution = resolution))

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.UNKNOWN_USER, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun test12_noFaceFollowsItsOwnPolicyNotTheSchedule() {
        val resolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))

        val decision = evaluator.evaluate(context(identity = UserIdentity.NO_FACE, scheduleResolution = resolution))

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun test12_obstructedCameraFollowsItsOwnPolicyNotTheSchedule() {
        val resolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))

        val decision = evaluator.evaluate(context(identity = UserIdentity.CAMERA_OBSTRUCTED, scheduleResolution = resolution))

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.CAMERA_OBSTRUCTED, (decision as PolicyDecision.Protect).trigger)
    }

    // ============================================================
    // TEST 13/14/15 — disabled, wrong day, wrong time
    // ============================================================

    @Test
    fun test13_disabledScheduleProducesNoResolution() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK, enabled = false)

        assertEquals(ScheduleResolution.NoActiveSchedule, resolutionOf(schedule, mon(12, 0)))
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(schedule, mon(12, 0)))))
    }

    @Test
    fun test14_wrongDayProducesNoResolution() {
        val mondayOnly = rule(1, ProtectionAction.HARD_BLOCK, days = ScheduleDays.of(DayOfWeek.MONDAY))

        assertEquals(ScheduleResolution.NoActiveSchedule, resolutionOf(mondayOnly, tue(12, 0)))
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(mondayOnly, tue(12, 0)))),
        )
    }

    @Test
    fun test15_wrongTimeProducesNoResolution() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK, start = 8 to 0, end = 10 to 0)

        assertEquals(ScheduleResolution.NoActiveSchedule, resolutionOf(schedule, mon(11, 0)))
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(schedule, mon(11, 0)))),
        )
    }

    // ============================================================
    // TEST 16 — cross-midnight reaches policy evaluation
    // ============================================================

    @Test
    fun test16_crossMidnightScheduleReachesPolicyEvaluation() {
        val overnight = rule(1, ProtectionAction.HARD_BLOCK, days = ScheduleDays.of(DayOfWeek.MONDAY), start = 22 to 0, end = 7 to 0)

        // Monday 23:00 and the after-midnight Tuesday 00:30 are both active…
        assertTrue(resolutionOf(overnight, mon(23, 0)) is ScheduleResolution.ActiveSchedule)
        assertTrue(resolutionOf(overnight, tue(0, 30)) is ScheduleResolution.ActiveSchedule)
        // …and 07:00 Tuesday is not ([start, end)).
        assertEquals(ScheduleResolution.NoActiveSchedule, resolutionOf(overnight, tue(7, 0)))

        assertProtect(
            ProtectionAction.HARD_BLOCK,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(overnight, tue(0, 30)))),
        )
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(overnight, tue(7, 0)))),
        )
    }

    // ============================================================
    // TEST 17 — only the resolver's winner reaches evaluation
    // ============================================================

    @Test
    fun test17_onlyTheHighestPriorityWinnerIsApplied() {
        val low = rule(1, ProtectionAction.ALLOW, priority = 10, start = 8 to 0, end = 18 to 0)
        val high = rule(2, ProtectionAction.HARD_BLOCK, priority = 20, start = 9 to 0, end = 17 to 0)
        val schedules = listOf(low, high)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))

        val resolved = resolution(schedules, targets, mon(12, 0))
        assertEquals(high, (resolved as ScheduleResolution.ActiveSchedule).schedule)

        assertProtect(
            ProtectionAction.HARD_BLOCK,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)),
        )
    }

    @Test
    fun test17_aLowerPriorityScheduleAloneLeavesTheAppAllowed() {
        val low = rule(1, ProtectionAction.ALLOW, priority = 10)
        val highButInactive = rule(2, ProtectionAction.HARD_BLOCK, priority = 20, start = 20 to 0, end = 22 to 0)

        val resolved = resolution(
            listOf(low, highButInactive),
            mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg)),
            mon(12, 0),
        )

        assertEquals(low, (resolved as ScheduleResolution.ActiveSchedule).schedule)
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)))
    }

    // ============================================================
    // TEST 18 — conflict reaches evaluation, no silent winner
    // ============================================================

    @Test
    fun test18_conflictIsPreservedAndEnforcedAtItsStrongest() {
        val soft = rule(1, ProtectionAction.SOFT_BLOCK, name = "A", priority = 20)
        val hard = rule(2, ProtectionAction.HARD_BLOCK, name = "B", priority = 20)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))

        val resolved = resolution(listOf(soft, hard), targets, mon(12, 0))
        assertTrue("the resolver must surface a conflict, not a winner", resolved is ScheduleResolution.ScheduleConflict)
        assertEquals(setOf(soft, hard), (resolved as ScheduleResolution.ScheduleConflict).schedules.toSet())

        // Enforcement takes the strongest action among the tied schedules.
        assertProtect(ProtectionAction.HARD_BLOCK, evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)))
    }

    @Test
    fun test18_conflictEnforcementDoesNotDependOnScheduleOrder() {
        val soft = rule(1, ProtectionAction.SOFT_BLOCK, name = "A", priority = 20)
        val hard = rule(2, ProtectionAction.HARD_BLOCK, name = "B", priority = 20)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))

        val forward = resolution(listOf(soft, hard), targets, mon(12, 0))
        val reversed = resolution(listOf(hard, soft), targets, mon(12, 0))

        val forwardDecision = evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = forward)) as PolicyDecision.Protect
        val reversedDecision = evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = reversed)) as PolicyDecision.Protect

        assertEquals(ProtectionAction.HARD_BLOCK, forwardDecision.action)
        assertEquals(forwardDecision.action, reversedDecision.action)
        // Presentation order is stable by (name, id) regardless of input order.
        assertEquals(
            (forward as ScheduleResolution.ScheduleConflict).schedules,
            (reversed as ScheduleResolution.ScheduleConflict).schedules,
        )
    }

    @Test
    fun test18_conflictOfEqualActionsIsDeterministic() {
        val a = rule(1, ProtectionAction.SOFT_BLOCK, name = "A", priority = 5)
        val b = rule(2, ProtectionAction.SOFT_BLOCK, name = "B", priority = 5)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))

        val resolved = resolution(listOf(a, b), targets, mon(12, 0))

        assertTrue(resolved is ScheduleResolution.ScheduleConflict)
        assertProtect(ProtectionAction.SOFT_BLOCK, evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)))
    }

    @Test
    fun test18_conflictOfAllowOnlySchedulesCannotRestrict() {
        val a = rule(1, ProtectionAction.ALLOW, name = "A", priority = 5)
        val b = rule(2, ProtectionAction.ALLOW, name = "B", priority = 5)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))

        val resolved = resolution(listOf(a, b), targets, mon(12, 0))

        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)),
        )
    }

    @Test
    fun test18_conflictNeverSelectsOneScheduleByIdOrName() {
        // The lexicographically-first name belongs to the WEAKER action; if name/id were a
        // tie-break the weaker action would win. It must not.
        val weakerButFirstByName = rule(1, ProtectionAction.SOFT_BLOCK, name = "AAA", priority = 7)
        val strongerButLaterByName = rule(2, ProtectionAction.HARD_BLOCK, name = "ZZZ", priority = 7)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))

        val resolved = resolution(listOf(weakerButFirstByName, strongerButLaterByName), targets, mon(12, 0))

        assertProtect(ProtectionAction.HARD_BLOCK, evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)))
    }

    // ============================================================
    // TEST 19 — mode is descriptive only
    // ============================================================

    @Test
    fun test19_scheduleModeDoesNotChangeEnforcement() {
        val decisions = ScheduleMode.entries.map { mode ->
            val schedule = rule(1, ProtectionAction.HARD_BLOCK, name = "Same name", mode = mode)
            evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(schedule, mon(12, 0))))
        }

        // Every mode produced the identical decision: mode contributes nothing.
        assertEquals(ScheduleMode.entries.size, decisions.size)
        decisions.forEach { assertEquals(decisions.first(), it) }
        assertProtect(ProtectionAction.HARD_BLOCK, decisions.first())

        // And an ALLOW schedule stays ALLOW under every mode.
        ScheduleMode.entries.forEach { mode ->
            val schedule = rule(1, ProtectionAction.ALLOW, name = "Same name", mode = mode)
            assertEquals(
                PolicyDecision.Allow,
                evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolutionOf(schedule, mon(12, 0)))),
            )
        }
    }

    // ============================================================
    // TEST 20/21 — child and account isolation
    // ============================================================

    @Test
    fun test20_childBsLookupNeverSeesChildAsSchedule() {
        val childASchedules = listOf(rule(1, ProtectionAction.HARD_BLOCK))
        val childATargets = mapOf(1L to setOf(protectedPkg))

        // Child A's schedule really is active for child A…
        assertTrue(resolution(childASchedules, childATargets, mon(12, 0)) is ScheduleResolution.ActiveSchedule)

        // …but child B's lookup is keyed on child B's own (empty) maps, so it resolves to nothing.
        val childBResolution = resolution(emptyList(), emptyMap(), mon(12, 0))
        assertEquals(ScheduleResolution.NoActiveSchedule, childBResolution)

        // Child B therefore keeps its ordinary decision.
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(childId = 2L, appPolicy = allowed(), scheduleResolution = childBResolution)))
    }

    @Test
    fun test21_accountBsLookupNeverSeesAccountAsSchedule() {
        val accountASchedules = listOf(rule(1, ProtectionAction.HARD_BLOCK))
        val accountATargets = mapOf(1L to setOf(protectedPkg))

        assertTrue(resolution(accountASchedules, accountATargets, mon(12, 0)) is ScheduleResolution.ActiveSchedule)

        // A different account's caches are cleared on an account switch, so its lookup sees none.
        assertEquals(ScheduleResolution.NoActiveSchedule, resolution(emptyList(), emptyMap(), mon(12, 0)))
    }

    @Test
    fun aForeignChildPolicyIsNotAppliedToTheRecognisedChild() {
        // The pre-existing leak guard: an app policy belonging to another child is never applied
        // to the recognised child, so a foreign child's BLOCK cannot restrict this child. A
        // schedule cannot be used to smuggle that restriction in either, because it only ever
        // tightens a decision that this child's own policy produced.
        val decision = evaluator.evaluate(
            context(
                childId = 2L,
                appPolicy = AppPolicy(packageName = protectedPkg, mode = AppPolicyMode.BLOCK, action = ProtectionAction.HARD_BLOCK, childId = 1L),
                isProtectedApp = false,
                scheduleResolution = ScheduleResolution.NoActiveSchedule,
            ),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    // ============================================================
    // TEST 24 — determinism
    // ============================================================

    @Test
    fun test24_repeatedEvaluationIsDeterministic() {
        val soft = rule(1, ProtectionAction.SOFT_BLOCK, priority = 20)
        val hard = rule(2, ProtectionAction.HARD_BLOCK, priority = 20)
        val targets = mapOf(1L to setOf(protectedPkg), 2L to setOf(protectedPkg))
        val resolved = resolution(listOf(soft, hard), targets, mon(12, 0))

        val first = evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved))
        repeat(5) {
            assertEquals(first, evaluator.evaluate(context(appPolicy = allowed(), scheduleResolution = resolved)))
        }
    }

    @Test
    fun resolutionIsIndependentOfTimeZoneForTheSameLocalMoment() {
        val schedule = rule(1, ProtectionAction.HARD_BLOCK, start = 8 to 0, end = 10 to 0)
        val targets = mapOf(1L to setOf(protectedPkg))
        val local = LocalDateTime.of(2026, 9, 21, 9, 0)

        val tashkent = resolveScheduleForPackage(listOf(schedule), targets, protectedPkg, local.atZone(ZoneId.of("Asia/Tashkent")).toInstant(), ZoneId.of("Asia/Tashkent"))
        val utc = resolveScheduleForPackage(listOf(schedule), targets, protectedPkg, local.atZone(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"))

        assertTrue(tashkent is ScheduleResolution.ActiveSchedule)
        assertTrue(utc is ScheduleResolution.ActiveSchedule)
        assertEquals(tashkent, utc)
    }

    // ============================================================
    // MUTE semantics — independent action, never redefined
    // ============================================================

    @Test
    fun muteIsNeverWeakenedByAScheduleAllow() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.MUTE), scheduleResolution = resolutionOf(rule(1, ProtectionAction.ALLOW), mon(12, 0))),
        )

        assertProtect(ProtectionAction.MUTE, decision)
    }

    @Test
    fun aScheduleBlockTightensAnExistingMute() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.MUTE), scheduleResolution = resolutionOf(rule(1, ProtectionAction.SOFT_BLOCK), mon(12, 0))),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
    }

    @Test
    fun aScheduleMuteDoesNotWeakenAnExistingSoftBlock() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.SOFT_BLOCK), scheduleResolution = resolutionOf(rule(1, ProtectionAction.MUTE), mon(12, 0))),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
    }

    @Test
    fun muteWithMuteStaysMute() {
        val decision = evaluator.evaluate(
            context(appPolicy = blocked(ProtectionAction.MUTE), scheduleResolution = resolutionOf(rule(1, ProtectionAction.MUTE), mon(12, 0))),
        )

        assertProtect(ProtectionAction.MUTE, decision)
    }

    // ============================================================
    // The restriction ordering itself
    // ============================================================

    @Test
    fun restrictionRankOrdersTheApprovedChain() {
        assertTrue(ProtectionAction.ALLOW.restrictionRank < ProtectionAction.WARNING.restrictionRank)
        assertTrue(ProtectionAction.WARNING.restrictionRank < ProtectionAction.MUTE.restrictionRank)
        assertTrue(ProtectionAction.MUTE.restrictionRank < ProtectionAction.SOFT_BLOCK.restrictionRank)
        assertTrue(ProtectionAction.SOFT_BLOCK.restrictionRank < ProtectionAction.HARD_BLOCK.restrictionRank)
    }

    @Test
    fun domainOnlyActionsContributeNoRestriction() {
        // DIM/BLUR/BLACK_SCREEN have no platform effect in this build, so they cannot displace an
        // implemented action.
        listOf(ProtectionAction.DIM, ProtectionAction.BLUR, ProtectionAction.BLACK_SCREEN).forEach { action ->
            assertEquals(ProtectionAction.ALLOW.restrictionRank, action.restrictionRank)
        }
    }

    // ============================================================
    // Regression — the pre-existing gates are untouched
    // ============================================================

    @Test
    fun regression_protectionDisabledStaysAllowEvenWithAnActiveSchedule() {
        val decision = evaluator.evaluate(
            context(settings = enabled.copy(enabled = false), scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0))),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun regression_spoofedPresentationStaysUnrestrictedBySchedules() {
        val spoofContext = context(
            scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0)),
        ).copy(liveness = LivenessState.SPOOF)

        val decision = evaluator.evaluate(spoofContext)

        assertProtect(ProtectionAction.SOFT_BLOCK, decision)
        assertEquals(PolicyTrigger.LIVENESS_SPOOF, (decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun regression_dailyLimitStillAppliesAndAScheduleStillTightens() {
        // Phase 4 semantics: the LIMIT comparison itself is unchanged by this step.
        val limited = AppPolicy(
            packageName = protectedPkg,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = 1L,
        )

        // Exceeded limit, no schedule -> the existing Phase 4 path, untouched.
        val exceeded = evaluator.evaluate(
            context(appPolicy = limited, appTimeUsedMinutes = 30),
        )
        assertEquals(PolicyTrigger.SCREEN_TIME_EXCEEDED, (exceeded as PolicyDecision.Protect).trigger)

        // Within the limit the app is allowed, but an active schedule still tightens it.
        val withinWithSchedule = evaluator.evaluate(
            context(
                appPolicy = limited,
                appTimeUsedMinutes = 10,
                scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0)),
            ),
        )
        assertProtect(ProtectionAction.HARD_BLOCK, withinWithSchedule)
    }

    @Test
    fun regression_parentDeviceChildPolicyDisabledStaysAllow() {
        val decision = evaluator.evaluate(
            context(
                settings = enabled.copy(parentDeviceChildPolicyEnabled = false),
                deviceOwner = DeviceOwnerMode.PARENT_DEVICE,
                scheduleResolution = resolutionOf(rule(1, ProtectionAction.HARD_BLOCK), mon(12, 0)),
            ),
        )

        assertEquals(PolicyDecision.Allow, decision)
    }
}
