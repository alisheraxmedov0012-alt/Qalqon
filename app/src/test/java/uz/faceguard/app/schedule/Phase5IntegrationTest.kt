package uz.faceguard.app.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleDraft
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRepository
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleStateIdentity
import uz.faceguard.app.domain.schedule.ScheduleTransitionTracker
import uz.faceguard.app.domain.schedule.ScheduleWindow
import uz.faceguard.app.domain.schedule.effectiveScheduleState
import uz.faceguard.app.domain.schedule.resolveScheduleForPackage
import uz.faceguard.app.domain.schedule.scheduleEventDetail
import uz.faceguard.app.feature.schedule.ScheduleConfigController

/**
 * Phase 5 Step 7: cross-step integration across the whole Phase 5 stack.
 *
 * Unlike the per-step unit tests, these wire the layers *together* through production code:
 *
 * ```
 * ScheduleConfigController  →  ScheduleRepository  →  resolveScheduleForPackage
 *     →  ScheduleResolver  →  DefaultPolicyEvaluator  →  ScheduleTransitionTracker
 * ```
 *
 * The only substitutions are the two Room-backed adapters (`ScheduleRepository` and the
 * protected-app catalog), which cannot run on the JVM; their real implementations are covered by
 * the instrumented tests in Step 2. Everything else — the configuration controller, the targeting
 * filter, the resolver, the policy evaluator and the transition tracker — is the shipped code, so a
 * scenario here fails if any layer of the composition regresses.
 *
 * 2026-09-20 is a Sunday, 2026-09-21 a Monday and 2026-09-22 a Tuesday.
 */
class Phase5IntegrationTest {

    private val accountId = 1L
    private val childA = 10L
    private val childB = 11L
    private val zone = ZoneId.of("Asia/Tashkent")

    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val calculator = "com.android.calculator2"

    private fun mon(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 21, hour, minute).atZone(zone).toInstant()

    private fun tue(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 22, hour, minute).atZone(zone).toInstant()

    private val enabledSettings = PolicySettings(
        enabled = true,
        activationDelayMs = 3_000L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    // ================================================================
    // Room substitute (interface is production; impl is instrumented-tested)
    // ================================================================

    private class FakeScheduleRepository : ScheduleRepository {
        private val rules = MutableStateFlow<Map<Pair<Long, Long>, List<ScheduleRule>>>(emptyMap())
        private val targets = MutableStateFlow<Map<Pair<Long, Long>, Map<Long, Set<String>>>>(emptyMap())
        private var nextId = 1L

        private fun key(accountId: Long, childId: Long) = accountId to childId

        fun seed(accountId: Long, childId: Long, rule: ScheduleRule, targetPackages: Set<String>) {
            val k = key(accountId, childId)
            rules.value = rules.value + (k to (rules.value[k].orEmpty() + rule))
            targets.value = targets.value + (k to (targets.value[k].orEmpty() + (rule.id to targetPackages)))
        }

        override fun observeSchedules(accountId: Long, childId: Long): Flow<List<ScheduleRule>> =
            rules.map { it[key(accountId, childId)].orEmpty() }

        override suspend fun schedules(accountId: Long, childId: Long): List<ScheduleRule> =
            rules.value[key(accountId, childId)].orEmpty()

        override suspend fun schedule(accountId: Long, childId: Long, scheduleId: Long): ScheduleRule? =
            rules.value[key(accountId, childId)].orEmpty().firstOrNull { it.id == scheduleId }

        override suspend fun create(
            accountId: Long,
            childId: Long,
            draft: ScheduleDraft,
            targetPackages: Set<String>,
        ): ScheduleRule {
            val k = key(accountId, childId)
            val rule = draft.withId(nextId++)
            rules.value = rules.value + (k to (rules.value[k].orEmpty() + rule))
            targets.value = targets.value + (k to (targets.value[k].orEmpty() + (rule.id to targetPackages)))
            return rule
        }

        override suspend fun update(
            accountId: Long,
            childId: Long,
            schedule: ScheduleRule,
            targetPackages: Set<String>,
        ): ScheduleRule? {
            val k = key(accountId, childId)
            val existing = rules.value[k].orEmpty()
            if (existing.none { it.id == schedule.id }) return null
            rules.value = rules.value + (k to existing.map { if (it.id == schedule.id) schedule else it })
            targets.value = targets.value + (k to (targets.value[k].orEmpty() + (schedule.id to targetPackages)))
            return schedule
        }

        override suspend fun delete(accountId: Long, childId: Long, scheduleId: Long) {
            val k = key(accountId, childId)
            rules.value = rules.value + (k to rules.value[k].orEmpty().filterNot { it.id == scheduleId })
            targets.value = targets.value + (k to (targets.value[k].orEmpty() - scheduleId))
        }

        override suspend fun deleteAllForChild(accountId: Long, childId: Long) {
            val k = key(accountId, childId)
            rules.value = rules.value + (k to emptyList())
            targets.value = targets.value + (k to emptyMap())
        }

        override fun observeTargetPackages(accountId: Long, childId: Long, scheduleId: Long): Flow<List<String>> =
            targets.map { (it[key(accountId, childId)]?.get(scheduleId) ?: emptySet()).sorted() }

        override suspend fun targetPackages(accountId: Long, childId: Long, scheduleId: Long): List<String> =
            (targets.value[key(accountId, childId)]?.get(scheduleId) ?: emptySet()).sorted()

        override suspend fun replaceTargetPackages(
            accountId: Long,
            childId: Long,
            scheduleId: Long,
            packageNames: Set<String>,
        ) {
            val k = key(accountId, childId)
            targets.value = targets.value + (k to (targets.value[k].orEmpty() + (scheduleId to packageNames)))
        }
    }

    // ================================================================
    // The Phase 5 pipeline, wired exactly like production
    // ================================================================

    /**
     * Mirrors `ProtectionRuntime`: the repository is snapshotted into the per-child in-memory caches
     * the engine reads, so the 500ms evaluation path never touches Room.
     */
    private inner class Pipeline(
        val repository: FakeScheduleRepository,
        val protectedPackages: Set<String> = setOf(youtube, tiktok),
    ) {
        val evaluator = DefaultPolicyEvaluator()
        val tracker = ScheduleTransitionTracker()
        val scheduleEvents = mutableListOf<String?>()

        /** A per-child cache, exactly like `ProtectionRuntime.childSchedules`. */
        fun schedulesFor(childId: Long): List<ScheduleRule> =
            runBlocking { repository.schedules(accountId, childId) }

        fun targetsFor(childId: Long): Map<Long, Set<String>> =
            schedulesFor(childId).associate { rule ->
                rule.id to runBlocking { repository.targetPackages(accountId, childId, rule.id) }.toSet()
            }

        /** The production lookup the runtime installs on the engine. */
        fun resolveFor(childId: Long, packageName: String, at: Instant): ScheduleResolution =
            resolveScheduleForPackage(schedulesFor(childId), targetsFor(childId), packageName, at, zone)

        /**
         * One engine evaluation: resolve → record (transition + event) → decide. Mirrors
         * `ProtectionEngine.evaluate` ordering, including the protected-app effectiveness gate.
         */
        fun evaluate(
            foreground: String?,
            childId: Long?,
            at: Instant,
            identity: UserIdentity = UserIdentity.CHILD,
            appPolicy: AppPolicy? = null,
            settings: PolicySettings = enabledSettings,
            appTimeUsedMinutes: Int? = null,
            deviceOwner: DeviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
        ): Evaluation {
            val isProtected = foreground != null && foreground in protectedPackages
            val resolution = if (childId != null && foreground != null) {
                resolveFor(childId, foreground, at)
            } else {
                ScheduleResolution.NoActiveSchedule
            }

            tracker.onResolution(resolution, childId, isProtected)?.let { transition ->
                scheduleEvents += scheduleEventDetail(transition.to)
            }

            val decision = evaluator.evaluate(
                PolicyContext(
                    identity = IdentityContext(
                        identity = identity,
                        childId = if (identity == UserIdentity.CHILD) childId else null,
                        confidence = 0.9f,
                    ),
                    settings = settings,
                    foregroundPackage = foreground,
                    deviceOwnerMode = deviceOwner,
                    appPolicy = appPolicy,
                    isProtectedApp = isProtected,
                    appTimeUsedMinutes = appTimeUsedMinutes,
                    scheduleResolution = resolution,
                ),
            )

            return Evaluation(
                resolution = resolution,
                published = effectiveScheduleState(resolution, childId, isProtected),
                decision = decision,
                scheduleEvents = scheduleEvents.toList(),
            )
        }
    }

    private data class Evaluation(
        val resolution: ScheduleResolution,
        val published: ScheduleStateIdentity,
        val decision: PolicyDecision,
        val scheduleEvents: List<String?>,
    )

    private fun rule(
        id: Long,
        name: String = "Schedule $id",
        mode: ScheduleMode = ScheduleMode.CUSTOM,
        priority: Int = 0,
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
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

    private fun assertProtect(action: ProtectionAction, decision: PolicyDecision) {
        assertTrue("expected Protect($action) but was $decision", decision is PolicyDecision.Protect)
        assertEquals(action, (decision as PolicyDecision.Protect).action)
    }

    private fun activeSchedule(resolution: ScheduleResolution): ScheduleRule {
        assertTrue("expected an active schedule but was $resolution", resolution is ScheduleResolution.ActiveSchedule)
        return (resolution as ScheduleResolution.ActiveSchedule).schedule
    }

    private fun controllerFor(pipeline: Pipeline, childId: Long, catalog: List<ProtectedApp>): ScheduleConfigController =
        ScheduleConfigController(
            repository = pipeline.repository,
            accountId = accountId,
            childId = childId,
            protectedApps = flowOf(catalog),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val fullCatalog = listOf(
        ProtectedApp(youtube, "YouTube", isProtected = true),
        ProtectedApp(tiktok, "TikTok", isProtected = true),
        ProtectedApp(calculator, "Calculator", isProtected = false),
    )

    // ================================================================
    // SCENARIO A — basic active schedule end to end
    // ================================================================

    @Test
    fun scenarioA_protectedTargetedEnabledActiveScheduleReachesThePolicyLayer() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val schedule = rule(1, priority = 5, action = ProtectionAction.HARD_BLOCK)
        pipeline.repository.seed(accountId, childA, schedule, setOf(youtube))

        // A protected app with no explicit child policy only warns, so the schedule's HARD_BLOCK
        // must be the thing that tightens it — which is how we know the schedule reached the
        // evaluator rather than the ordinary protected-app path deciding on its own.
        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            settings = enabledSettings.copy(childAction = ProtectionAction.WARNING),
        )

        // Resolver → active
        assertEquals(schedule, activeSchedule(evaluation.resolution))
        // Targeting → applies (the package is a target)
        assertEquals(ScheduleStateIdentity.Active(childA, 1L), evaluation.published)
        // Policy → the schedule's action reached the evaluator through the existing single evaluator
        assertProtect(ProtectionAction.HARD_BLOCK, evaluation.decision)
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, (evaluation.decision as PolicyDecision.Protect).trigger)
        // Exactly one transition event
        assertEquals(listOf<String?>("child 10 / schedule 1"), evaluation.scheduleEvents)
    }

    @Test
    fun scenarioA_withoutAScheduleTheProtectedAppFollowsTheOrdinaryChildAction() {
        val pipeline = Pipeline(FakeScheduleRepository())

        val evaluation = pipeline.evaluate(youtube, childA, mon(12, 0))

        assertProtect(ProtectionAction.HARD_BLOCK, evaluation.decision)
        assertEquals(PolicyTrigger.PROTECTED_APP_OPENED, (evaluation.decision as PolicyDecision.Protect).trigger)
        assertTrue(evaluation.scheduleEvents.isEmpty())
    }

    @Test
    fun scenarioA_anActiveScheduleWithAllowDoesNotRestrictAnAllowedApp() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.ALLOW), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    // ================================================================
    // SCENARIO B — schedule does not target the foreground app
    // ================================================================

    @Test
    fun scenarioB_aScheduleTargetingAnotherAppDoesNotApply() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        val evaluation = pipeline.evaluate(
            tiktok,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(tiktok, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertEquals(ScheduleResolution.NoActiveSchedule, evaluation.resolution)
        assertEquals(ScheduleStateIdentity.None, evaluation.published)
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertTrue(evaluation.scheduleEvents.isEmpty())
    }

    @Test
    fun scenarioB_aNonTargetingScheduleLeavesTheOrdinaryProtectedAppDecisionIdenticalToTheNoScheduleBaseline() {
        // Baseline: a protected app with no schedules configured at all.
        val baseline = Pipeline(FakeScheduleRepository()).evaluate(youtube, childA, mon(12, 0))

        // Same context, but a schedule exists that targets a *different* app.
        val withUnrelatedSchedule = Pipeline(FakeScheduleRepository())
        withUnrelatedSchedule.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(tiktok))
        val actual = withUnrelatedSchedule.evaluate(youtube, childA, mon(12, 0))

        assertEquals(baseline.decision, actual.decision)
        assertEquals(baseline.published, actual.published)
        assertTrue(actual.scheduleEvents.isEmpty())
    }

    // ================================================================
    // SCENARIO C — unprotected app may not be made protected
    // ================================================================

    @Test
    fun scenarioC_anUnprotectedPackageIsNeverRestrictedByASchedule() {
        val pipeline = Pipeline(FakeScheduleRepository())
        // The schedule targets the calculator, but the calculator is not in protectedPackages.
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(calculator))

        val evaluation = pipeline.evaluate(calculator, childA, mon(12, 0))

        // The resolver may see it as targeted, but it is not *effective* and not enforced.
        assertEquals(ScheduleStateIdentity.None, evaluation.published)
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertTrue(evaluation.scheduleEvents.isEmpty())
    }

    @Test
    fun scenarioC_aTargetedPackageOutsideTheProtectedSetIsNotProtectedByTheSchedule() {
        val pipeline = Pipeline(FakeScheduleRepository(), protectedPackages = setOf(tiktok))
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        val evaluation = pipeline.evaluate(youtube, childA, mon(12, 0))

        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertEquals(ScheduleStateIdentity.None, evaluation.published)
    }

    // ================================================================
    // SCENARIO D — parent precedence
    // ================================================================

    @Test
    fun scenarioD_parentIsNeverRestrictedAndNoChildEventIsAttributed() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        // Child: schedule active.
        val asChild = pipeline.evaluate(youtube, childA, mon(12, 0))
        assertProtect(ProtectionAction.HARD_BLOCK, asChild.decision)

        // Parent recognised: no child scope, so the schedule is no longer effective.
        val asParent = pipeline.evaluate(youtube, childId = null, at = mon(12, 1), identity = UserIdentity.PARENT)

        assertEquals(PolicyDecision.Allow, asParent.decision)
        assertEquals(ScheduleStateIdentity.None, asParent.published)
        assertEquals("a deactivation, not a parent activation", listOf<String?>("none"), asParent.scheduleEvents.drop(1))
    }

    // ================================================================
    // SCENARIO E — priority
    // ================================================================

    @Test
    fun scenarioE_higherPriorityWinsAcrossTheStack() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, priority = 1, action = ProtectionAction.WARNING), setOf(youtube))
        pipeline.repository.seed(accountId, childA, rule(2, priority = 5, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertEquals(2L, activeSchedule(evaluation.resolution).id)
        assertProtect(ProtectionAction.HARD_BLOCK, evaluation.decision)
    }

    // ================================================================
    // SCENARIO F — equal priority conflict
    // ================================================================

    @Test
    fun scenarioF_equalPriorityIsAConflictAndNeverSilentlySelectsOne() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val warning = rule(1, name = "A", priority = 5, action = ProtectionAction.WARNING)
        val hard = rule(2, name = "B", priority = 5, action = ProtectionAction.HARD_BLOCK)
        pipeline.repository.seed(accountId, childA, warning, setOf(youtube))
        pipeline.repository.seed(accountId, childA, hard, setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertTrue(evaluation.resolution is ScheduleResolution.ScheduleConflict)
        val tied = (evaluation.resolution as ScheduleResolution.ScheduleConflict).schedules
        assertEquals(setOf(warning, hard), tied.toSet())
        // Step 4's documented conflict behavior: the strongest tied action is enforced.
        assertProtect(ProtectionAction.HARD_BLOCK, evaluation.decision)
        assertEquals(ScheduleStateIdentity.Conflict(childA, listOf(1L, 2L)), evaluation.published)
    }

    @Test
    fun scenarioF_conflictEnforcementIsIndependentOfSeedOrder() {
        val forward = Pipeline(FakeScheduleRepository())
        val reversed = Pipeline(FakeScheduleRepository())
        val a = rule(1, name = "A", priority = 5, action = ProtectionAction.WARNING)
        val b = rule(2, name = "B", priority = 5, action = ProtectionAction.HARD_BLOCK)
        forward.repository.seed(accountId, childA, a, setOf(youtube))
        forward.repository.seed(accountId, childA, b, setOf(youtube))
        reversed.repository.seed(accountId, childA, b, setOf(youtube))
        reversed.repository.seed(accountId, childA, a, setOf(youtube))

        val f = forward.evaluate(youtube, childA, mon(12, 0))
        val r = reversed.evaluate(youtube, childA, mon(12, 0))

        assertEquals(f.published, r.published)
        assertEquals(
            (f.decision as PolicyDecision.Protect).action,
            (r.decision as PolicyDecision.Protect).action,
        )
        assertEquals(f.scheduleEvents, r.scheduleEvents)
    }

    // ================================================================
    // SCENARIO G/H/I/J — restriction combination
    // ================================================================

    @Test
    fun scenarioG_scheduleAllowCannotWeakenAnExistingHardBlock() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.ALLOW), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.BLOCK, ProtectionAction.HARD_BLOCK, childId = childA),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, evaluation.decision)
    }

    @Test
    fun scenarioH_existingAllowWithAScheduleHardBlockBecomesHardBlock() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertProtect(ProtectionAction.HARD_BLOCK, evaluation.decision)
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, (evaluation.decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun scenarioI_existingWarningWithAScheduleSoftBlockBecomesSoftBlock() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.SOFT_BLOCK), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.BLOCK, ProtectionAction.WARNING, childId = childA),
        )

        assertProtect(ProtectionAction.SOFT_BLOCK, evaluation.decision)
    }

    @Test
    fun scenarioJ_muteFollowsStep4SemanticsAndIsNeverCombinedIntoANewAction() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.MUTE), setOf(youtube))

        // Existing ALLOW + schedule MUTE -> MUTE (the schedule tightens).
        val tightened = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )
        assertProtect(ProtectionAction.MUTE, tightened.decision)

        // Existing SOFT_BLOCK + schedule MUTE -> SOFT_BLOCK (no downgrade, no combined action).
        val notWeakened = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 1),
            appPolicy = AppPolicy(youtube, AppPolicyMode.BLOCK, ProtectionAction.SOFT_BLOCK, childId = childA),
        )
        assertProtect(ProtectionAction.SOFT_BLOCK, notWeakened.decision)
    }

    // ================================================================
    // SCENARIO K/L — cross-midnight and day boundaries
    // ================================================================

    @Test
    fun scenarioK_crossMidnightWindowIsActiveAcrossTheBoundary() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val overnight = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY), start = 23 to 0, end = 6 to 0)
        pipeline.repository.seed(accountId, childA, overnight, setOf(youtube))

        assertEquals(activeSchedule(pipeline.evaluate(youtube, childA, mon(23, 0)).resolution), overnight)
        assertEquals(activeSchedule(pipeline.evaluate(youtube, childA, mon(23, 59)).resolution), overnight)
        assertEquals(activeSchedule(pipeline.evaluate(youtube, childA, tue(0, 0)).resolution), overnight)
        assertEquals(activeSchedule(pipeline.evaluate(youtube, childA, tue(5, 59)).resolution), overnight)
        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            pipeline.evaluate(youtube, childA, tue(6, 0)).resolution,
        )
    }

    @Test
    fun scenarioK_theStartDayOwnsTheCrossMidnightContinuation() {
        val pipeline = Pipeline(FakeScheduleRepository())
        // Monday-only window: its after-midnight continuation is owned by Monday, so Tuesday need
        // not be selected for Tuesday 02:00 to be inside it.
        val overnight = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY), start = 23 to 0, end = 6 to 0)
        pipeline.repository.seed(accountId, childA, overnight, setOf(youtube))

        assertFalse(overnight.days.contains(DayOfWeek.TUESDAY))
        assertProtect(
            ProtectionAction.HARD_BLOCK,
            pipeline.evaluate(youtube, childA, tue(2, 0)).decision,
        )
        // But Tuesday 20:00 is outside both the window and its owning day.
        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            pipeline.evaluate(youtube, childA, tue(20, 0)).resolution,
        )
    }

    @Test
    fun scenarioL_explicitDayMembershipIsRespectedForNormalAndCrossMidnightWindows() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val weekendOvernight = rule(
            1,
            days = ScheduleDays.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            start = 22 to 0,
            end = 7 to 0,
        )
        pipeline.repository.seed(accountId, childA, weekendOvernight, setOf(youtube))

        // Sunday 22:00 (2026-09-20) is inside the window and Sunday is selected.
        val sundayNight = LocalDateTime.of(2026, 9, 20, 22, 0).atZone(zone).toInstant()
        assertTrue(pipeline.evaluate(youtube, childA, sundayNight).resolution is ScheduleResolution.ActiveSchedule)

        // Monday 22:00 (2026-09-21): Monday is not selected, so nothing applies.
        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            pipeline.evaluate(youtube, childA, mon(22, 0)).resolution,
        )
    }

    // ================================================================
    // SCENARIO M — injected zone
    // ================================================================

    @Test
    fun scenarioM_theSuppliedZoneIsUsedNotTheSystemDefault() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, start = 22 to 0, end = 7 to 0), setOf(youtube))

        // 2026-09-21T20:00Z is Monday 20:00 UTC (before 22:00) but Tuesday 01:00 in Tashkent
        // (UTC+5), inside Monday's after-midnight portion.
        val instant = Instant.parse("2026-09-21T20:00:00Z")
        val schedules = pipeline.schedulesFor(childA)
        val targets = pipeline.targetsFor(childA)

        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            resolveScheduleForPackage(schedules, targets, youtube, instant, ZoneId.of("UTC")),
        )
        assertTrue(
            resolveScheduleForPackage(schedules, targets, youtube, instant, ZoneId.of("Asia/Tashkent"))
                is ScheduleResolution.ActiveSchedule,
        )
    }

    // ================================================================
    // SCENARIO N — disabled
    // ================================================================

    @Test
    fun scenarioN_aDisabledScheduleNeverAffectsPolicy() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK, enabled = false), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertEquals(ScheduleStateIdentity.None, evaluation.published)
        assertTrue(evaluation.scheduleEvents.isEmpty())
    }

    // ================================================================
    // SCENARIO O/P — child and account isolation
    // ================================================================

    @Test
    fun scenarioO_childSchedulesAreCompletelyIsolated() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, name = "For A", action = ProtectionAction.HARD_BLOCK), setOf(youtube))
        pipeline.repository.seed(accountId, childB, rule(2, name = "For B", action = ProtectionAction.ALLOW), setOf(youtube))

        val forA = pipeline.evaluate(youtube, childA, mon(12, 0))
        val forB = pipeline.evaluate(youtube, childB, mon(12, 0))

        assertEquals(1L, activeSchedule(forA.resolution).id)
        assertEquals(2L, activeSchedule(forB.resolution).id)
        assertEquals(ScheduleStateIdentity.Active(childA, 1L), forA.published)
        assertEquals(ScheduleStateIdentity.Active(childB, 2L), forB.published)
    }

    @Test
    fun scenarioO_aChildWithNoSchedulesIsUnaffectedByASiblings() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        val forB = pipeline.evaluate(
            youtube,
            childB,
            mon(12, 0),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childB),
        )

        assertEquals(ScheduleResolution.NoActiveSchedule, forB.resolution)
        assertEquals(ScheduleStateIdentity.None, forB.published)
        assertEquals(PolicyDecision.Allow, forB.decision)
        assertTrue(forB.scheduleEvents.isEmpty())
    }

    @Test
    fun scenarioO_childBsDecisionIsIdenticalToItsOwnNoScheduleBaseline() {
        val baseline = Pipeline(FakeScheduleRepository()).evaluate(youtube, childB, mon(12, 0))

        val withSiblingSchedule = Pipeline(FakeScheduleRepository())
        withSiblingSchedule.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))
        val actual = withSiblingSchedule.evaluate(youtube, childB, mon(12, 0))

        assertEquals(baseline.decision, actual.decision)
        assertEquals(baseline.published, actual.published)
        assertTrue(actual.scheduleEvents.isEmpty())
    }

    @Test
    fun scenarioP_accountsAreCompletelyIsolatedEvenWithOverlappingIdsAndPackages() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val accountB = 2L
        // Same id and same target package in both accounts, different meaning.
        pipeline.repository.seed(accountId, childA, rule(7, name = "A's", action = ProtectionAction.HARD_BLOCK), setOf(youtube))
        pipeline.repository.seed(accountB, childA, rule(7, name = "B's", action = ProtectionAction.ALLOW), setOf(youtube))

        val accountAEvaluation = pipeline.evaluate(youtube, childA, mon(12, 0))

        val accountBRepo = pipeline.repository
        val accountBSchedules = runBlocking { accountBRepo.schedules(accountB, childA) }
        val accountBTargets = accountBSchedules.associate { it.id to runBlocking { accountBRepo.targetPackages(accountB, childA, it.id) }.toSet() }
        val accountBResolution = resolveScheduleForPackage(accountBSchedules, accountBTargets, youtube, mon(12, 0), zone)

        assertEquals("A's", activeSchedule(accountAEvaluation.resolution).name)
        assertEquals("B's", activeSchedule(accountBResolution).name)
    }

    // ================================================================
    // SCENARIO Q — enable/disable transition
    // ================================================================

    @Test
    fun scenarioQ_disablingAnActiveScheduleProducesExactlyOneTransition() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val schedule = rule(1, action = ProtectionAction.HARD_BLOCK)
        pipeline.repository.seed(accountId, childA, schedule, setOf(youtube))

        val active = pipeline.evaluate(youtube, childA, mon(12, 0))
        assertEquals(1, active.scheduleEvents.size)

        // The parent disables it through the production repository (the same call the UI makes).
        runBlocking {
            pipeline.repository.update(
                accountId,
                childA,
                schedule.copy(enabled = false),
                setOf(youtube),
            )
        }

        // The next existing engine evaluation detects the change.
        val afterDisable = pipeline.evaluate(youtube, childA, mon(12, 1))
        assertEquals(ScheduleStateIdentity.None, afterDisable.published)
        assertEquals(2, afterDisable.scheduleEvents.size)
        assertEquals("none", afterDisable.scheduleEvents.last())

        // Repeated ticks produce no further events.
        repeat(10) { pipeline.evaluate(youtube, childA, mon(12, 2)) }
        assertEquals(2, pipeline.scheduleEvents.size)
    }

    // ================================================================
    // SCENARIO R — time boundary detection on the existing tick
    // ================================================================

    @Test
    fun scenarioR_enteringTheWindowIsDetectedByTheNextEvaluation() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, start = 8 to 0, end = 10 to 0), setOf(youtube))

        val before = pipeline.evaluate(youtube, childA, mon(7, 59))
        assertEquals(ScheduleStateIdentity.None, before.published)
        assertTrue(before.scheduleEvents.isEmpty())

        // The next evaluation after the boundary sees the activation — no timer involved.
        val after = pipeline.evaluate(youtube, childA, mon(8, 0))
        assertEquals(ScheduleStateIdentity.Active(childA, 1L), after.published)
        assertEquals(1, after.scheduleEvents.size)

        // And leaving again is one more.
        val ended = pipeline.evaluate(youtube, childA, mon(10, 0))
        assertEquals(ScheduleStateIdentity.None, ended.published)
        assertEquals(2, ended.scheduleEvents.size)
    }

    // ================================================================
    // SCENARIO S — persistence round trip through the UI controller
    // ================================================================

    @Test
    fun scenarioS_aScheduleCreatedThroughTheUiSurvivesAReloadIntact() = runBlocking {
        val pipeline = Pipeline(FakeScheduleRepository())
        val controller = controllerFor(pipeline, childA, fullCatalog)

        controller.startAdd()
        controller.onNameChange("Homework")
        controller.onModeChange(ScheduleMode.STUDY)
        controller.onActionChange(ProtectionAction.SOFT_BLOCK)
        controller.onSetDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        controller.onStartMinuteChange(600)
        controller.onEndMinuteChange(720)
        controller.onPriorityChange("4")
        controller.onEnabledChange(false)
        controller.onTogglePackage(youtube)
        controller.onTogglePackage(tiktok)
        assertTrue(controller.saveEditor())
        val createdId = controller.editor.value.scheduleId!!

        // Reload through the repository, as opening the editor again would.
        val reloaded = pipeline.repository.schedule(accountId, childA, createdId)!!
        assertEquals("Homework", reloaded.name)
        assertEquals(ScheduleMode.STUDY, reloaded.mode)
        assertEquals(ProtectionAction.SOFT_BLOCK, reloaded.action)
        assertEquals(ScheduleDays.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), reloaded.days)
        assertEquals(600, reloaded.window.startMinuteOfDay)
        assertEquals(720, reloaded.window.endMinuteOfDay)
        assertEquals(4, reloaded.priority)
        assertFalse(reloaded.enabled)
        assertEquals(setOf(tiktok, youtube), pipeline.repository.targetPackages(accountId, childA, createdId).toSet())

        // And the editor loads exactly that back.
        controller.startEdit(reloaded)
        assertEquals("Homework", controller.editor.value.name)
        assertEquals(setOf(tiktok, youtube), controller.editor.value.selectedPackages)
    }

    @Test
    fun scenarioS_theUiCanOnlyTargetProtectedApps() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val controller = controllerFor(pipeline, childA, fullCatalog)
        runBlocking { kotlinx.coroutines.yield() }

        assertEquals(
            setOf(youtube, tiktok),
            controller.catalog.value.apps.map { it.packageName }.toSet(),
        )
        assertFalse("an unprotected app is not offered", controller.catalog.value.isSelectedOffered(calculator))
    }

    private fun uz.faceguard.app.feature.schedule.ScheduleCatalogState.isSelectedOffered(packageName: String) =
        apps.any { it.packageName == packageName }

    // ================================================================
    // SCENARIO T — target replacement
    // ================================================================

    @Test
    fun scenarioT_replacingTargetsRemovesOldMembershipsWithNoOrphans() = runBlocking {
        val pipeline = Pipeline(FakeScheduleRepository())
        val controller = controllerFor(pipeline, childA, fullCatalog)

        controller.startAdd()
        controller.onNameChange("Study")
        controller.onTogglePackage(youtube)
        controller.onTogglePackage(tiktok)
        assertTrue(controller.saveEditor())
        val scheduleId = controller.editor.value.scheduleId!!

        controller.startEdit(pipeline.repository.schedule(accountId, childA, scheduleId)!!)
        controller.onTogglePackage(youtube) // deselect one
        assertTrue(controller.saveEditor())

        assertEquals(
            listOf(tiktok),
            pipeline.repository.targetPackages(accountId, childA, scheduleId),
        )
        // The released package no longer resolves as targeted.
        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            pipeline.resolveFor(childA, youtube, mon(12, 0)),
        )
    }

    // ================================================================
    // SCENARIO U/V/W/X — activity event transitions
    // ================================================================

    @Test
    fun scenarioU_noActiveToActiveIsOneEventAndRepeatedTicksAddNone() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1), setOf(youtube))

        pipeline.evaluate(youtube, childA, mon(12, 0))
        repeat(100) { pipeline.evaluate(youtube, childA, mon(12, 0)) }

        assertEquals(1, pipeline.scheduleEvents.size)
        assertEquals("child 10 / schedule 1", pipeline.scheduleEvents.single())
    }

    @Test
    fun scenarioV_activeToInactiveIsOneEvent() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, start = 8 to 0, end = 12 to 0), setOf(youtube))

        pipeline.evaluate(youtube, childA, mon(11, 0))
        pipeline.evaluate(youtube, childA, mon(12, 0))
        repeat(5) { pipeline.evaluate(youtube, childA, mon(13, 0)) }

        assertEquals(2, pipeline.scheduleEvents.size)
        assertEquals(listOf<String?>("child 10 / schedule 1", "none"), pipeline.scheduleEvents)
    }

    @Test
    fun scenarioW_activeToDifferentActiveIsOneEvent() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, start = 8 to 0, end = 11 to 0), setOf(youtube))
        pipeline.repository.seed(accountId, childA, rule(2, start = 10 to 0, end = 13 to 0), setOf(youtube))

        pipeline.evaluate(youtube, childA, mon(9, 0))   // A only
        pipeline.evaluate(youtube, childA, mon(12, 0))  // B only
        repeat(5) { pipeline.evaluate(youtube, childA, mon(12, 30)) }

        assertEquals(2, pipeline.scheduleEvents.size)
        assertEquals(listOf<String?>("child 10 / schedule 1", "child 10 / schedule 2"), pipeline.scheduleEvents)
    }

    @Test
    fun scenarioX_conflictEnterAndLeaveEachProduceOneEvent() {
        val pipeline = Pipeline(FakeScheduleRepository())
        // 1 runs 08:00-13:00, 2 runs 10:00-18:00 with equal priority, so they overlap 10:00-13:00.
        pipeline.repository.seed(accountId, childA, rule(1, priority = 5, start = 8 to 0, end = 13 to 0), setOf(youtube))
        pipeline.repository.seed(accountId, childA, rule(2, priority = 5, start = 10 to 0, end = 18 to 0), setOf(youtube))

        pipeline.evaluate(youtube, childA, mon(9, 0))   // A only
        pipeline.evaluate(youtube, childA, mon(11, 0))  // conflict A,B
        pipeline.evaluate(youtube, childA, mon(14, 0))  // B only

        assertEquals(3, pipeline.scheduleEvents.size)
        assertEquals(
            listOf<String?>(
                "child 10 / schedule 1",
                "child 10 / conflict 1+2",
                "child 10 / schedule 2",
            ),
            pipeline.scheduleEvents,
        )
    }

    @Test
    fun scenarioX_conflictOrderingProducesNoDuplicateEvents() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, priority = 5), setOf(youtube))
        pipeline.repository.seed(accountId, childA, rule(2, priority = 5), setOf(youtube))

        val first = pipeline.evaluate(youtube, childA, mon(12, 0))
        assertEquals(1, first.scheduleEvents.size)

        // The resolver rebuilds the conflict list on every evaluation; a different internal order
        // must not look like a new state.
        repeat(30) { pipeline.evaluate(youtube, childA, mon(12, 0)) }
        assertEquals(1, pipeline.scheduleEvents.size)
        assertEquals(ScheduleStateIdentity.Conflict(childA, listOf(1L, 2L)), pipeline.evaluate(youtube, childA, mon(12, 0)).published)
    }

    // ================================================================
    // SCENARIO Y — restart
    // ================================================================

    @Test
    fun scenarioY_aFreshPipelineObservingAnActiveScheduleRecordsOneActivation() {
        val repository = FakeScheduleRepository()
        repository.seed(accountId, childA, rule(1), setOf(youtube))

        // First session.
        val first = Pipeline(repository)
        first.evaluate(youtube, childA, mon(12, 0))
        assertEquals(1, first.scheduleEvents.size)

        // A restarted pipeline (new tracker) holds no baseline, so the first observation of the
        // already-active schedule is one activation, not a burst.
        val restarted = Pipeline(repository)
        restarted.evaluate(youtube, childA, mon(12, 0))
        assertEquals(1, restarted.scheduleEvents.size)

        repeat(50) { restarted.evaluate(youtube, childA, mon(12, 0)) }
        assertEquals(1, restarted.scheduleEvents.size)
    }

    // ================================================================
    // SCENARIO Z — Phase 4 remains authoritative and ungated by schedules
    // ================================================================

    @Test
    fun scenarioZ_aScheduleDoesNotChangeThePhase4DailyLimitComparison() {
        val pipeline = Pipeline(FakeScheduleRepository())
        val limited = AppPolicy(
            packageName = youtube,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = childA,
        )

        // No schedule configured: the Phase 4 path decides exactly as before.
        val exceeded = pipeline.evaluate(youtube, childA, mon(12, 0), appPolicy = limited, appTimeUsedMinutes = 30)
        assertEquals(PolicyTrigger.SCREEN_TIME_EXCEEDED, (exceeded.decision as PolicyDecision.Protect).trigger)

        val within = pipeline.evaluate(youtube, childA, mon(12, 1), appPolicy = limited, appTimeUsedMinutes = 10)
        assertEquals(PolicyDecision.Allow, within.decision)

        // Unknown usage still suppresses the screen-time restriction (never treated as zero).
        val unknown = pipeline.evaluate(youtube, childA, mon(12, 2), appPolicy = limited, appTimeUsedMinutes = null)
        assertEquals(PolicyDecision.Allow, unknown.decision)
    }

    @Test
    fun scenarioZ_anActiveScheduleTightensButNeverBypassesThePhase4LimitPath() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))
        val limited = AppPolicy(
            packageName = youtube,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = childA,
        )

        // Within the limit the app is allowed by Phase 4, and the schedule then tightens it.
        val within = pipeline.evaluate(youtube, childA, mon(12, 0), appPolicy = limited, appTimeUsedMinutes = 10)
        assertProtect(ProtectionAction.HARD_BLOCK, within.decision)
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, (within.decision as PolicyDecision.Protect).trigger)

        // Exceeded: the schedule cannot weaken the limit's own decision.
        val exceeded = pipeline.evaluate(youtube, childA, mon(12, 1), appPolicy = limited, appTimeUsedMinutes = 45)
        assertTrue(exceeded.decision is PolicyDecision.Protect)
    }

    // ================================================================
    // Property checks — order independence
    // ================================================================

    @Test
    fun permutationsOfThreeSchedulesResolveToTheSameWinner() {
        val a = rule(1, name = "A", priority = 1)
        val b = rule(2, name = "B", priority = 20)
        val c = rule(3, name = "C", priority = 5)
        val all = listOf(a, b, c)
        val permutations = listOf(
            listOf(a, b, c), listOf(a, c, b), listOf(b, a, c),
            listOf(b, c, a), listOf(c, a, b), listOf(c, b, a),
        )

        val outcomes = permutations.map { order ->
            val pipeline = Pipeline(FakeScheduleRepository())
            order.forEach { pipeline.repository.seed(accountId, childA, it, setOf(youtube)) }
            val evaluation = pipeline.evaluate(youtube, childA, mon(12, 0))
            Triple(
                activeSchedule(evaluation.resolution).id,
                evaluation.published,
                evaluation.scheduleEvents,
            )
        }

        assertTrue(outcomes.all { it == outcomes.first() })
        assertEquals(2L, outcomes.first().first)
    }

    @Test
    fun permutationsOfAConflictProduceTheSameIdentityAndEventCount() {
        val a = rule(1, name = "A", priority = 5)
        val b = rule(2, name = "B", priority = 5)

        val forward = Pipeline(FakeScheduleRepository())
        forward.repository.seed(accountId, childA, a, setOf(youtube))
        forward.repository.seed(accountId, childA, b, setOf(youtube))

        val reversed = Pipeline(FakeScheduleRepository())
        reversed.repository.seed(accountId, childA, b, setOf(youtube))
        reversed.repository.seed(accountId, childA, a, setOf(youtube))

        repeat(20) {
            forward.evaluate(youtube, childA, mon(12, 0))
            reversed.evaluate(youtube, childA, mon(12, 0))
        }

        assertEquals(1, forward.scheduleEvents.size)
        assertEquals(1, reversed.scheduleEvents.size)
        assertEquals(ScheduleStateIdentity.Conflict(childA, listOf(1L, 2L)), forward.evaluate(youtube, childA, mon(12, 0)).published)
        assertEquals(forward.evaluate(youtube, childA, mon(12, 0)).published, reversed.evaluate(youtube, childA, mon(12, 0)).published)
    }

    @Test
    fun aLongActiveWindowProducesExactlyOneEventAcrossManyEvaluations() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, start = 0 to 0, end = 23 to 59), setOf(youtube))

        // 23h59m of whole-minute evaluations.
        for (minute in 0 until 23 * 60 + 59) {
            pipeline.evaluate(youtube, childA, mon(minute / 60, minute % 60))
        }

        assertEquals(1, pipeline.scheduleEvents.size)
    }

    @Test
    fun noEventIsLoggedWhenNothingIsEverActive() {
        val pipeline = Pipeline(FakeScheduleRepository())

        repeat(200) { pipeline.evaluate(youtube, childA, mon(12, 0)) }

        assertTrue(pipeline.scheduleEvents.isEmpty())
        assertNull(pipeline.scheduleEvents.lastOrNull())
    }

    // ================================================================
    // Unknown / no-face must not activate a child schedule
    // ================================================================

    @Test
    fun anUnknownUserNeverActivatesAChildSchedule() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childId = null,
            at = mon(12, 0),
            identity = UserIdentity.UNKNOWN,
        )

        assertEquals(ScheduleStateIdentity.None, evaluation.published)
        assertProtect(ProtectionAction.SOFT_BLOCK, evaluation.decision) // unknown policy, not the schedule
        assertEquals(PolicyTrigger.UNKNOWN_USER, (evaluation.decision as PolicyDecision.Protect).trigger)
    }

    @Test
    fun aNoFaceObservationNeverActivatesAChildSchedule() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childId = null,
            at = mon(12, 0),
            identity = UserIdentity.NO_FACE,
        )

        assertEquals(ScheduleStateIdentity.None, evaluation.published)
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    @Test
    fun protectionDisabledGivesNoScheduleRestrictionAndNoEvent() {
        val pipeline = Pipeline(FakeScheduleRepository())
        pipeline.repository.seed(accountId, childA, rule(1, action = ProtectionAction.HARD_BLOCK), setOf(youtube))

        val evaluation = pipeline.evaluate(
            youtube,
            childA,
            mon(12, 0),
            settings = enabledSettings.copy(enabled = false),
        )

        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    // ================================================================
    // Mode is descriptive across the whole stack
    // ================================================================

    @Test
    fun everyScheduleModeProducesTheSameDecisionForTheSameAction() {
        val decisions = ScheduleMode.entries.map { mode ->
            val pipeline = Pipeline(FakeScheduleRepository())
            pipeline.repository.seed(accountId, childA, rule(1, mode = mode, action = ProtectionAction.SOFT_BLOCK), setOf(youtube))
            pipeline.evaluate(
                youtube,
                childA,
                mon(12, 0),
                appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            ).decision
        }

        assertEquals(ScheduleMode.entries.size, decisions.size)
        decisions.forEach { assertProtect(ProtectionAction.SOFT_BLOCK, it) }
    }

    @Test
    fun onlyImplementedActionsCanReachThePolicyLayerThroughTheDomain() {
        val rejected = runCatching { rule(1, action = ProtectionAction.DIM) }
        assertTrue("a domain-only action cannot be a schedule", rejected.isFailure)
    }
}
