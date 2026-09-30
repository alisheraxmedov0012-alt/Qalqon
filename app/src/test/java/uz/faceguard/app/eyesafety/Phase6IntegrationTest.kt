package uz.faceguard.app.eyesafety

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.eyesafety.EyeSafetyObservationState
import uz.faceguard.app.core.eyesafety.eyeSafetyFrameOf
import uz.faceguard.app.core.pipeline.FaceQuality
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
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
import uz.faceguard.app.domain.schedule.resolveScheduleForPackage
import uz.faceguard.app.feature.eyesafety.EyeSafetyConfigController

/**
 * Phase 6 Step 6: the end-to-end closure suite.
 *
 * Every other Phase 6 test drives one layer. `EyeSafetyPolicyTest` sets `eyeSafetyState` on the
 * context directly and `EyeSafetyRuntimeTest` feeds the observation state by hand; neither walks a
 * **saved** configuration from the UI through to the decision. That is the chain this file proves:
 *
 * ```
 * EyeSafetyConfigController (the real UI logic)
 *   → EyeSafetyRepository.save
 *   → observeConfig                ← the flow ProtectionRuntime.collects
 *   → the runtime's per-child cache
 *   → ProtectionEngine's lookup     ← eyeSafetyConfigLookup
 *   → EyeSafetyFrame (from FaceQuality, as the camera produces it)
 *   → EyeSafetyObservationState / EyeSafetyEvaluator
 *   → PolicyContext.eyeSafetyState
 *   → DefaultPolicyEvaluator        → PolicyDecision
 * ```
 *
 * Only two things are substituted: the Room-backed repository (covered by the Phase 6 Step 3
 * instrumented tests) and the one-line cache copy in `ProtectionRuntime.refreshChildEyeSafety`,
 * which needs an Android `Context` to instantiate — see [RuntimeConfigCache]. Everything else is
 * the shipped code, so a scenario here fails if any layer of the composition regresses.
 */
class Phase6IntegrationTest {

    private val accountId = 1L
    private val accountB = 2L
    private val childA = 10L
    private val childB = 11L
    private val zone = ZoneId.of("Asia/Tashkent")
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.music"

    private fun at(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 21, hour, minute).atZone(zone).toInstant()

    // ================================================================
    // The Room substitute (interface is production; impl is instrumented-tested)
    // ================================================================

    private class FakeEyeSafetyRepository : EyeSafetyRepository {
        private val rows = mutableMapOf<Pair<Long, Long>, ChildEyeSafetyConfig>()

        var saveCalls = 0
        var failSave = false

        private fun key(accountId: Long, childId: Long) = accountId to childId

        fun seed(accountId: Long, childId: Long, model: ChildEyeSafetyConfig) {
            rows[key(accountId, childId)] = model
        }

        fun stored(accountId: Long, childId: Long): ChildEyeSafetyConfig? = rows[key(accountId, childId)]

        fun clearAll() = rows.clear()

        override suspend fun config(accountId: Long, childId: Long): ChildEyeSafetyConfig? =
            rows[key(accountId, childId)]

        override fun observeConfig(accountId: Long, childId: Long): Flow<ChildEyeSafetyConfig?> =
            MutableStateFlow(rows[key(accountId, childId)]).map { it }

        override suspend fun save(config: ChildEyeSafetyConfig) {
            saveCalls++
            if (failSave) throw IllegalStateException("injected save failure")
            rows[key(config.accountId, config.childId)] = config
        }

        override suspend fun delete(accountId: Long, childId: Long) {
            rows.remove(key(accountId, childId))
        }
    }

    /**
     * Mirrors `ProtectionRuntime.refreshChildEyeSafety`: each child's `observeConfig` is collected
     * into a `childId -> config` map, and the engine's `eyeSafetyConfigLookup` reads that map.
     *
     * The real copy is a single line inside `ProtectionRuntime` that needs an Android `Context` to
     * instantiate, so it is reproduced here rather than executed. Nothing downstream of it is
     * reproduced: the lookup, the observation state, the evaluator and the evaluator's composition
     * are all the shipped code.
     */
    private inner class RuntimeConfigCache(
        val repository: FakeEyeSafetyRepository,
        val accountId: Long = this@Phase6IntegrationTest.accountId,
        val children: List<Long> = listOf(childA, childB),
    ) {
        private var cache: Map<Long, ChildEyeSafetyConfig> = emptyMap()

        /** Collects each child's configuration once, as the runtime does on refresh. */
        fun refresh() {
            cache = children.mapNotNull { childId ->
                runBlocking { repository.config(accountId, childId) }?.let { childId to it }
            }.toMap()
        }

        /** The engine's lookup. */
        fun lookup(childId: Long): ChildEyeSafetyConfig? = cache[childId]

        /** Drops the cache, as an account switch does. */
        fun clear() {
            cache = emptyMap()
        }
    }

    // ================================================================
    // The Phase 6 pipeline, wired as production composes it
    // ================================================================

    private inner class Pipeline(
        val repository: FakeEyeSafetyRepository,
        val protectedPackages: Set<String> = setOf(youtube, tiktok),
        accountId: Long = this@Phase6IntegrationTest.accountId,
    ) {
        val evaluator = DefaultPolicyEvaluator()
        val eyeSafety = EyeSafetyObservationState()
        val cache = RuntimeConfigCache(repository, accountId)

        init {
            cache.refresh()
        }

        /** The camera's per-frame metrics for a face of [faceWidthRatio]. */
        private fun observation(timestampMs: Long, faceWidthRatio: Float) =
            eyeSafetyFrameOf(FaceQuality(faceCount = 1, faceWidthRatio = faceWidthRatio), timestampMs)

        private fun noFaceObservation(timestampMs: Long) =
            eyeSafetyFrameOf(FaceQuality(faceCount = 0, faceWidthRatio = 0f), timestampMs)

        /**
         * One engine evaluation for [childId] (or `null` for parent/unknown/no-face), returning the
         * state and decision the shipped layers produced.
         */
        fun evaluate(
            faceWidthRatio: Float?,
            childId: Long?,
            at: Instant,
            facePresent: Boolean = faceWidthRatio != null,
            appPackage: String? = youtube,
            appPolicy: AppPolicy? = null,
            settings: PolicySettings = enabledSettings,
            scheduleResolution: ScheduleResolution = ScheduleResolution.NoActiveSchedule,
            appTimeUsedMinutes: Int? = null,
            identity: UserIdentity = if (childId != null) UserIdentity.CHILD else UserIdentity.UNKNOWN,
        ): Evaluation {
            val now = at.toEpochMilli()
            val frame = if (!facePresent) noFaceObservation(now) else observation(now, faceWidthRatio ?: 0f)
            val isProtected = appPackage != null && appPackage in protectedPackages

            val state = eyeSafety.observe(
                observation = frame,
                childId = childId,
                child = childId?.let { cache.lookup(it) },
                now = now,
            )

            val decision = evaluator.evaluate(
                PolicyContext(
                    identity = IdentityContext(
                        identity = identity,
                        childId = if (identity == UserIdentity.CHILD) childId else null,
                    ),
                    settings = settings,
                    foregroundPackage = appPackage,
                    appPolicy = appPolicy,
                    isProtectedApp = isProtected,
                    appTimeUsedMinutes = appTimeUsedMinutes,
                    scheduleResolution = scheduleResolution,
                    eyeSafetyState = state,
                ),
            )

            return Evaluation(state = state, decision = decision, protectedApp = isProtected)
        }

        /** The runtime's action override: the child's configured eye-safety actions. */
        fun settingsWithChildActions(childId: Long, base: PolicySettings = enabledSettings): PolicySettings {
            val child = cache.lookup(childId) ?: return base
            return base.copy(
                eyeSafetyWarningAction = child.warningAction,
                eyeSafetyDangerAction = child.dangerAction,
            )
        }
    }

    private data class Evaluation(
        val state: EyeSafetyState,
        val decision: PolicyDecision,
        val protectedApp: Boolean,
    )

    private val enabledSettings = PolicySettings(
        enabled = true,
        activationDelayMs = 3_000L,
        childAction = ProtectionAction.WARNING,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    private fun actionOf(decision: PolicyDecision): ProtectionAction = when (decision) {
        is PolicyDecision.Allow -> ProtectionAction.ALLOW
        is PolicyDecision.Warn -> ProtectionAction.WARNING
        is PolicyDecision.Protect -> decision.action
    }

    private fun triggerOf(decision: PolicyDecision): PolicyTrigger? = (decision as? PolicyDecision.Protect)?.trigger

    /**
     * The thresholds and confirmation count a saved child would have. Actions are deliberately
     * *not* parameters here: they live on [ChildEyeSafetyConfig], not on [EyeSafetyConfig], and are
     * supplied separately (see [pipelineWith]).
     */
    private fun config(
        confirmFrames: Int = 1,
        enabled: Boolean = true,
        warningEnter: Float = 0.30f,
        warningExit: Float = 0.27f,
        dangerEnter: Float = 0.40f,
        dangerExit: Float = 0.35f,
    ) = EyeSafetyConfig(
        enabled = enabled,
        warningEnterThreshold = warningEnter,
        warningExitThreshold = warningExit,
        dangerEnterThreshold = dangerEnter,
        dangerExitThreshold = dangerExit,
        confirmFrames = confirmFrames,
    )

    /**
     * The *n*-th observation instant, spaced like the engine's 500 ms tick.
     *
     * Multi-frame confirmation is counted over a window that expires after
     * `EyeSafetyConfig.DEFAULT_MAX_WINDOW_AGE_MS` (2.5 s), so frames that confirm a transition must
     * arrive close together — exactly as they do in production. Spreading them across minutes would
     * let each frame age out before the next arrives, and nothing would ever be confirmed.
     */
    private fun tick(base: Instant = at(12), index: Int): Instant = base.plusMillis(index * 500L)

    private fun model(
        childId: Long = childA,
        accountId: Long = this.accountId,
        config: EyeSafetyConfig = config(),
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        updatedAt: Long = 1_000L,
    ) = ChildEyeSafetyConfig(
        accountId = accountId,
        childId = childId,
        config = config,
        warningAction = warningAction,
        dangerAction = dangerAction,
        updatedAt = updatedAt,
    )

    /** A configured child, saved through the same state the UI writes. */
    private fun pipelineWith(
        childId: Long = childA,
        config: EyeSafetyConfig = config(),
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ): Pipeline {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childId, model(childId = childId, config = config, warningAction = warningAction, dangerAction = dangerAction))
        return Pipeline(repository)
    }

    // ================================================================
    // SCENARIO A — child, comfortably far
    // ================================================================

    @Test
    fun scenarioA_aComfortablyDistantChildIsSafeAndUnrestricted() {
        val pipeline = pipelineWith(config = config(warningEnter = 0.80f, warningExit = 0.70f, dangerEnter = 0.90f, dangerExit = 0.85f))

        val evaluation = pipeline.evaluate(faceWidthRatio = 0.20f, childId = childA, at = at(12))

        assertEquals(EyeSafetyState.SAFE, evaluation.state)
        // No eye-safety restriction is added: the ordinary protected-app path is what stands, and at
        // this build's WARNING childAction that path is a warning, not a protection.
        assertTrue("expected the ordinary warning, was ${evaluation.decision}", evaluation.decision is PolicyDecision.Warn)
        assertNull("a warning carries no trigger", triggerOf(evaluation.decision))
    }

    @Test
    fun scenarioA_aSafeStateNeverAddsRestrictionEvenWithBlockingActionsConfigured() {
        val pipeline = pipelineWith(
            config = config(warningEnter = 0.80f, warningExit = 0.70f, dangerEnter = 0.90f, dangerExit = 0.85f),
            warningAction = ProtectionAction.HARD_BLOCK,
            dangerAction = ProtectionAction.HARD_BLOCK,
        )

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.20f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
        )

        assertEquals(EyeSafetyState.SAFE, evaluation.state)
        assertEquals("a SAFE child is not restricted by eye safety", PolicyDecision.Allow, evaluation.decision)
    }

    // ================================================================
    // SCENARIO B — WARNING reaches policy
    // ================================================================

    @Test
    fun scenarioB_aWarningStateAppliesTheConfiguredWarningAction() {
        val pipeline = pipelineWith(warningAction = ProtectionAction.MUTE)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.33f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
        )

        assertEquals(EyeSafetyState.WARNING, evaluation.state)
        assertEquals(ProtectionAction.MUTE, actionOf(evaluation.decision))
        assertEquals(PolicyTrigger.EYE_SAFETY_WARNING, triggerOf(evaluation.decision))
    }

    @Test
    fun scenarioB_theConfiguredConfirmationCountIsHonoured() {
        val pipeline = pipelineWith(config = config(confirmFrames = 3))

        // Two ticks are not yet a confirmation, so nothing is applied.
        pipeline.evaluate(0.45f, childA, tick(index = 0))
        val second = pipeline.evaluate(0.45f, childA, tick(index = 1))
        assertEquals(EyeSafetyState.UNKNOWN, second.state)

        val third = pipeline.evaluate(0.45f, childA, tick(index = 2))
        assertEquals(EyeSafetyState.DANGER, third.state)
    }

    @Test
    fun scenarioB_confirmationStartsOverWhenTheEvidenceIsInterrupted() {
        val pipeline = pipelineWith(config = config(confirmFrames = 3))

        // Three confirming ticks reach DANGER…
        (0..2).forEach { index -> pipeline.evaluate(0.45f, childA, tick(index = index)) }
        assertEquals(EyeSafetyState.DANGER, pipeline.eyeSafety.currentState)

        // …and a window that has decayed (a long gap) cannot confirm anything on its own.
        val afterGap = pipeline.evaluate(0.45f, childA, at(12).plusSeconds(30))
        assertEquals(EyeSafetyState.UNKNOWN, afterGap.state)
    }

    // ================================================================
    // SCENARIO C — DANGER reaches policy
    // ================================================================

    @Test
    fun scenarioC_aDangerStateAppliesTheConfiguredDangerAction() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.45f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
        )

        assertEquals(EyeSafetyState.DANGER, evaluation.state)
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, triggerOf(evaluation.decision))
    }

    // ================================================================
    // SCENARIO D/E — hysteresis and recovery
    // ================================================================

    @Test
    fun scenarioD_dangerIsHeldInsideItsExitBand() {
        val pipeline = pipelineWith()

        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.45f, childA, at(12, 0)).state)
        // 0.36 is above dangerExit (0.35), so the state must not flicker back.
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.36f, childA, at(12, 1)).state)
    }

    @Test
    fun scenarioE_dangerIsLeftBelowItsExitThreshold() {
        val pipeline = pipelineWith()

        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.45f, childA, at(12, 0)).state)
        // 0.34 is below dangerExit (0.35) but above warningExit, so it falls to WARNING.
        assertEquals(EyeSafetyState.WARNING, pipeline.evaluate(0.34f, childA, at(12, 1)).state)
        // 0.20 is below warningExit (0.27), so it reaches SAFE.
        assertEquals(EyeSafetyState.SAFE, pipeline.evaluate(0.20f, childA, at(12, 2)).state)
    }

    // ================================================================
    // SCENARIO F — no face
    // ================================================================

    @Test
    fun scenarioF_noFaceIsNeverSafeAndNeverADistance() {
        val pipeline = pipelineWith()

        val evaluation = pipeline.evaluate(faceWidthRatio = null, childId = childA, at = at(12), facePresent = false)

        assertEquals(EyeSafetyState.UNKNOWN, evaluation.state)
    }

    @Test
    fun scenarioF_aStaleDangerDoesNotSurviveLosingTheFaceForever() {
        val pipeline = pipelineWith()
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.45f, childA, at(12, 0)).state)

        // The face is gone: the observation carries no measurement, and the window ages out, so the
        // state becomes UNKNOWN rather than a DANGER that holds forever.
        val lost = pipeline.evaluate(faceWidthRatio = null, childId = childA, at = at(12, 1), facePresent = false)
        assertEquals(EyeSafetyState.UNKNOWN, lost.state)

        // And a long idle period keeps it UNKNOWN.
        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(faceWidthRatio = null, childId = childA, at = at(13), facePresent = false).state)
    }

    // ================================================================
    // SCENARIO G/H — parent and unknown after a child
    // ================================================================

    @Test
    fun scenarioG_aChildRestrictionDoesNotContinueForTheParent() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.90f, childA, at(12, 0)).state)

        val parent = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = null,
            at = at(12, 1),
            identity = UserIdentity.PARENT,
        )

        assertEquals(EyeSafetyState.UNKNOWN, parent.state)
        assertEquals("parent precedence is intact", PolicyDecision.Allow, parent.decision)
        assertNull("no child session is active", pipeline.eyeSafety.activeChildId)
    }

    @Test
    fun scenarioH_aChildRestrictionDoesNotContinueForAnUnknownUser() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.90f, childA, at(12, 0)).state)

        val unknown = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = null,
            at = at(12, 1),
            identity = UserIdentity.UNKNOWN,
        )

        assertEquals(EyeSafetyState.UNKNOWN, unknown.state)
        // The unknown-user policy decides, not eye safety.
        assertEquals(PolicyTrigger.UNKNOWN_USER, triggerOf(unknown.decision))
        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(unknown.decision))
    }

    // ================================================================
    // SCENARIO I — child A → child B
    // ================================================================

    @Test
    fun scenarioI_eachChildIsEvaluatedAgainstItsOwnConfiguration() {
        val repository = FakeEyeSafetyRepository()
        // A permissive child and a strict one, saved for different children of the same account.
        repository.seed(
            accountId,
            childA,
            model(childId = childA, config = config(warningEnter = 0.10f, warningExit = 0.08f, dangerEnter = 0.20f, dangerExit = 0.15f)),
        )
        repository.seed(
            accountId,
            childB,
            model(childId = childB, config = config(warningEnter = 0.60f, warningExit = 0.55f, dangerEnter = 0.80f, dangerExit = 0.70f)),
        )
        val pipeline = Pipeline(repository)

        // The same 0.45 is DANGER for child A and SAFE for child B.
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.45f, childA, at(12, 0)).state)
        assertEquals(EyeSafetyState.SAFE, pipeline.evaluate(0.45f, childB, at(12, 1)).state)
    }

    @Test
    fun scenarioI_childAsDangerDoesNotLeakIntoChildB() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(childId = childA, config = config(confirmFrames = 3)))
        repository.seed(accountId, childB, model(childId = childB, config = config(confirmFrames = 3)))
        val pipeline = Pipeline(repository)

        (0..2).forEach { index -> pipeline.evaluate(0.45f, childA, tick(index = index)) }
        assertEquals(EyeSafetyState.DANGER, pipeline.eyeSafety.currentState)

        // Child B's first tick cannot inherit A's DANGER: its own confirmation has not been met.
        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(0.45f, childB, tick(index = 3)).state)
        assertEquals(childB, pipeline.eyeSafety.activeChildId)
    }

    // ================================================================
    // SCENARIO J — protected vs unprotected app
    // ================================================================

    @Test
    fun scenarioJ_eyeSafetyRestrictsAProtectedApp() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPackage = youtube,
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
        )

        assertTrue(evaluation.protectedApp)
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
    }

    @Test
    fun scenarioJ_eyeSafetyNeverRestrictsAnUnprotectedApp() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPackage = "com.example.unprotected",
        )

        assertFalse(evaluation.protectedApp)
        // The state may be DANGER, but the protected-app boundary is what decides enforcement.
        assertEquals(EyeSafetyState.DANGER, evaluation.state)
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    // ================================================================
    // SCENARIO K — eye safety + schedule
    // ================================================================

    private fun schedule(action: ProtectionAction): ScheduleResolution = ScheduleResolution.ActiveSchedule(
        ScheduleRule(
            id = 1L,
            name = "Bedtime",
            mode = ScheduleMode.SLEEP,
            window = ScheduleWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)),
            days = ScheduleDays.of(DayOfWeek.MONDAY),
            action = action,
        ),
    )

    @Test
    fun scenarioK_theStrongerOfEyeSafetyAndScheduleWins() {
        // Eye safety DANGER configures SOFT_BLOCK; the schedule configures HARD_BLOCK.
        val pipeline = pipelineWith(dangerAction = ProtectionAction.SOFT_BLOCK)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
            scheduleResolution = schedule(ProtectionAction.HARD_BLOCK),
        )

        assertEquals(EyeSafetyState.DANGER, evaluation.state)
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
        // The outer layer names itself when it is strictly stronger — the Phase 5 rule, unchanged.
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, triggerOf(evaluation.decision))
    }

    @Test
    fun scenarioK_aScheduleAllowCannotWeakenEyeSafety() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
            scheduleResolution = schedule(ProtectionAction.ALLOW),
        )

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
    }

    @Test
    fun scenarioK_eyeSafetyAllowDoesNotWeakenAScheduleBlock() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.ALLOW)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
            settings = pipeline.settingsWithChildActions(childA),
            scheduleResolution = schedule(ProtectionAction.HARD_BLOCK),
        )

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
        assertEquals(PolicyTrigger.SCHEDULE_ACTIVE, triggerOf(evaluation.decision))
    }

    // ================================================================
    // SCENARIO L — eye safety + screen time
    // ================================================================

    @Test
    fun scenarioL_anExceededScreenTimeLimitIsNotWeakenedByEyeSafety() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.ALLOW)
        val limited = AppPolicy(
            packageName = youtube,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.HARD_BLOCK,
            dailyLimitMinutes = 30,
            childId = childA,
        )

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPolicy = limited,
            settings = pipeline.settingsWithChildActions(childA),
            appTimeUsedMinutes = 30,
        )

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
        assertEquals(PolicyTrigger.SCREEN_TIME_EXCEEDED, triggerOf(evaluation.decision))
    }

    @Test
    fun scenarioL_eyeSafetyTightensAWithinLimitAppWithoutTouchingTheLimit() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)
        val limited = AppPolicy(
            packageName = youtube,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = childA,
        )

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPolicy = limited,
            settings = pipeline.settingsWithChildActions(childA),
            appTimeUsedMinutes = 10,
        )

        // The limit was not reached, so the app was allowed — and eye safety then tightened it.
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluation.decision))
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, triggerOf(evaluation.decision))
    }

    @Test
    fun scenarioL_anUnmeasuredUsageLimitStillDoesNotFire() {
        val pipeline = pipelineWith(dangerAction = ProtectionAction.ALLOW)
        val limited = AppPolicy(
            packageName = youtube,
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 0,
            childId = childA,
        )

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.90f,
            childId = childA,
            at = at(12),
            appPolicy = limited,
            settings = pipeline.settingsWithChildActions(childA),
            appTimeUsedMinutes = null,
        )

        // A null measurement is "unknown", never zero; and eye safety is ALLOW here, so nothing
        // restricts.
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    // ================================================================
    // Section 9 — configuration to runtime propagation
    // ================================================================

    @Test
    fun propagation_anEnabledSaveReachesTheEngineAndThePolicy() {
        val repository = FakeEyeSafetyRepository()
        val controller = EyeSafetyConfigController(
            repository = repository,
            accountId = accountId,
            childId = childA,
            clock = { 1_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        // The child starts unconfigured: the screen writes nothing on open.
        assertEquals(0, repository.saveCalls)
        assertFalse(controller.state.value.configured)

        runBlocking {
            controller.onEnabledChange(true)
            controller.onWarningEnterChange("30")
            controller.onWarningExitChange("27")
            controller.onDangerEnterChange("40")
            controller.onDangerExitChange("35")
            controller.onWarningActionChange(ProtectionAction.MUTE)
            controller.onDangerActionChange(ProtectionAction.HARD_BLOCK)
            assertTrue(controller.save())
        }

        // The runtime collects the saved configuration exactly as it does on refresh.
        val pipeline = Pipeline(repository)

        // The screen saved the domain default of 3 confirmation frames, so three closely-spaced
        // ticks are what confirm the danger — the same cadence the engine really ticks at.
        val danger = (0..2).map { index ->
            pipeline.evaluate(
                faceWidthRatio = 0.45f,
                childId = childA,
                at = tick(index = index),
                appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
                settings = pipeline.settingsWithChildActions(childA),
            )
        }.last()

        assertEquals(EyeSafetyState.DANGER, danger.state)
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(danger.decision))
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, triggerOf(danger.decision))
    }

    @Test
    fun propagation_anUpdatedThresholdTakesEffectOnTheNextEvaluation() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(config = config(warningEnter = 0.10f, warningExit = 0.08f, dangerEnter = 0.20f, dangerExit = 0.15f)))
        val pipeline = Pipeline(repository)
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.45f, childA, at(12, 0)).state)

        // The parent saves a stricter configuration; the runtime picks it up on its next refresh.
        runBlocking {
            repository.save(model(config = config(warningEnter = 0.60f, warningExit = 0.55f, dangerEnter = 0.80f, dangerExit = 0.70f)))
        }
        pipeline.cache.refresh()

        assertEquals(EyeSafetyState.SAFE, pipeline.evaluate(0.45f, childA, at(12, 1)).state)
    }

    @Test
    fun propagation_disablingTheConfigurationStopsEyeSafety() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(dangerAction = ProtectionAction.HARD_BLOCK))
        val pipeline = Pipeline(repository)
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.90f, childA, at(12, 0)).state)

        runBlocking { repository.save(model(config = config(enabled = false), dangerAction = ProtectionAction.HARD_BLOCK)) }
        pipeline.cache.refresh()

        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(0.90f, childA, at(12, 1)).state)
        assertEquals(ProtectionAction.WARNING, actionOf(pipeline.evaluate(0.90f, childA, at(12, 2)).decision))
    }

    @Test
    fun propagation_deletingTheConfigurationStopsEyeSafety() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(dangerAction = ProtectionAction.HARD_BLOCK))
        val pipeline = Pipeline(repository)
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.90f, childA, at(12, 0)).state)

        runBlocking { repository.delete(accountId, childA) }
        pipeline.cache.refresh()

        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(0.90f, childA, at(12, 1)).state)
    }

    @Test
    fun propagation_aSavedChildDoesNotAffectItsSibling() {
        val repository = FakeEyeSafetyRepository()
        val controller = EyeSafetyConfigController(
            repository = repository,
            accountId = accountId,
            childId = childA,
            clock = { 1_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        runBlocking {
            controller.onEnabledChange(true)
            controller.onDangerEnterChange("40")
            assertTrue(controller.save())
        }

        val pipeline = Pipeline(repository)

        // Child A: the screen's saved configuration needs its 3 confirmation frames.
        val forA = (0..2).map { index -> pipeline.evaluate(0.45f, childA, tick(index = index)) }.last()
        assertEquals(EyeSafetyState.DANGER, forA.state)
        // Child B has no row, so it has no eye safety at all.
        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(0.45f, childB, tick(index = 3)).state)
    }

    @Test
    fun propagation_anAccountSwitchCannotCarryAChildConfigurationAcross() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(config = config(warningEnter = 0.10f, warningExit = 0.08f, dangerEnter = 0.20f, dangerExit = 0.15f)))

        val forAccountA = Pipeline(repository)
        assertEquals(EyeSafetyState.DANGER, forAccountA.evaluate(0.45f, childA, at(12, 0)).state)

        // A different account's runtime observes its own children — and has no configuration.
        val forAccountB = Pipeline(repository, accountId = accountB)

        assertNull("account B must not see account A's configuration", forAccountB.cache.lookup(childA))
        assertEquals(EyeSafetyState.UNKNOWN, forAccountB.evaluate(0.45f, childA, at(12, 1)).state)
    }

    // ================================================================
    // Section 10 — stale state
    // ================================================================

    @Test
    fun staleState_aRefreshAfterAnAccountChangeLeavesNoConfiguration() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model())
        val cache = RuntimeConfigCache(repository, accountId = accountId)
        cache.refresh()
        assertTrue(cache.lookup(childA) != null)

        // The runtime clears its cache on an account switch.
        cache.clear()
        repository.clearAll()
        cache.refresh()

        assertNull(cache.lookup(childA))
        assertNull(cache.lookup(childB))
    }

    @Test
    fun staleState_anExplicitResetLeavesNoSession() {
        val pipeline = pipelineWith()
        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.90f, childA, at(12, 0)).state)

        pipeline.eyeSafety.reset()

        assertNull(pipeline.eyeSafety.activeChildId)
        assertEquals(EyeSafetyState.UNKNOWN, pipeline.eyeSafety.currentState)
        // The next evaluation starts from scratch rather than resuming the old verdict.
        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(0.90f, childA, at(12, 1), facePresent = false).state)
    }

    @Test
    fun staleState_aChildWithoutAConfigurationNeverInheritsASiblingsState() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model())
        val pipeline = Pipeline(repository)

        assertEquals(EyeSafetyState.DANGER, pipeline.evaluate(0.90f, childA, at(12, 0)).state)
        assertEquals(EyeSafetyState.UNKNOWN, pipeline.evaluate(0.90f, childB, at(12, 1)).state)
        assertNull(pipeline.eyeSafety.activeChildId)
    }

    // ================================================================
    // Determinism across the whole pipeline
    // ================================================================

    @Test
    fun theSameSavedConfigurationAndFrameSequenceProduceTheSameDecision() {
        fun run(): List<ProtectionAction> {
            val pipeline = pipelineWith(dangerAction = ProtectionAction.HARD_BLOCK)
            val ratios = listOf(0.10f, 0.35f, 0.45f, 0.36f, 0.20f)
            return ratios.mapIndexed { index, ratio ->
                actionOf(
                    pipeline.evaluate(
                        faceWidthRatio = ratio,
                        childId = childA,
                        at = at(12, index),
                        appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
                        settings = pipeline.settingsWithChildActions(childA),
                    ).decision,
                )
            }
        }

        assertEquals(run(), run())
    }

    @Test
    fun anUnconfiguredChildIsNeverRestrictedByEyeSafetyHoweverCloseTheFaceIs() {
        val pipeline = Pipeline(FakeEyeSafetyRepository())

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.99f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertEquals(EyeSafetyState.UNKNOWN, evaluation.state)
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    @Test
    fun aDisabledConfigurationIsRestrictionFreeEvenThoughItStoresActions() {
        val pipeline = pipelineWith(config = config(enabled = false), dangerAction = ProtectionAction.HARD_BLOCK)

        val evaluation = pipeline.evaluate(
            faceWidthRatio = 0.99f,
            childId = childA,
            at = at(12),
            appPolicy = AppPolicy(youtube, AppPolicyMode.ALLOW, ProtectionAction.ALLOW, childId = childA),
        )

        assertEquals(EyeSafetyState.UNKNOWN, evaluation.state)
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        // The stored action is still there, so re-enabling restores the parent's choice.
        assertEquals(ProtectionAction.HARD_BLOCK, pipeline.cache.lookup(childA)!!.dangerAction)
    }
}
