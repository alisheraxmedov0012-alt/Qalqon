package uz.faceguard.app.core

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.embed.FaceEmbeddingCodec
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.core.protection.ProtectionActionExecutor
import uz.faceguard.app.core.protection.ProtectionEngine
import uz.faceguard.app.core.protection.ProtectionSettings
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 4 Step 4: app daily screen-time limits enforced through the **real** protection pipeline.
 *
 * Real [ProtectionEngine] + real [DefaultPolicyEvaluator] + real [Recognizer], driven through the
 * public `evaluate(...)` entry point. The action executor is a recording double (production
 * executors and the overlay stay untouched), and the two lookups the runtime normally supplies —
 * the app policy and the measured usage — are injected here, which is exactly where the engine
 * sits in production.
 *
 * The load-bearing cases are the ones where "we could not measure usage" must not be read as zero,
 * and the ones proving one child's or one package's usage cannot enforce another's limit.
 *
 * The full recovery lifecycle (a timer completing the release, exactly-once PROTECTION_RELEASED)
 * is deliberately not re-tested here: it belongs to the Phase 9 suite
 * ([ProtectionEngineRecoveryLifecycleTest]), which exercises it against a started session and
 * remains green. These tests only assert the state a screen-time limit reaches on its own.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeEnforcementIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val parentVec = FloatArray(8) { if (it == 0) 1f else 0f }
    private val child5Vec = FloatArray(8) { if (it == 1) 1f else 0f }
    private val child6Vec = FloatArray(8) { if (it == 3) 1f else 0f }

    private val parent = ParentProfile(
        id = 1L,
        accountId = 1L,
        displayName = "Parent",
        isFaceEnrolled = true,
        faceTemplateRef = FaceEmbeddingCodec.encode(parentVec),
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private fun child(id: Long, vec: FloatArray) = ChildProfile(
        id = id,
        accountId = 1L,
        childName = "Child$id",
        isFaceEnrolled = true,
        faceTemplateRef = FaceEmbeddingCodec.encode(vec),
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private val youtube = "com.example.youtube"
    private val calculator = "com.example.calculator"
    private val child5 = 5L

    private class RecordingExecutor : ProtectionActionExecutor {
        override val supportedActions = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()

        override fun execute(action: ProtectionAction) {
            executed += action
        }

        override fun clear() = Unit
        override fun mute() = Unit
        override fun unmute() = Unit
    }

    private fun engine(executor: RecordingExecutor) = ProtectionEngine(
        recognizer = Recognizer(),
        monitor = ForegroundAppMonitor(context),
        actions = executor,
        policyEvaluator = DefaultPolicyEvaluator(),
    )

    private fun frame(features: FloatArray?): FrameEvent = FrameEvent(
        image = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0),
        features = features,
    )

    private fun policySettings(
        enabled: Boolean = true,
        recovery: Long = 30_000L,
    ) = PolicySettings(
        enabled = enabled,
        activationDelayMs = 0L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        recoveryDelayMs = recovery,
        parentDeviceChildPolicyEnabled = true,
    )

    private fun ProtectionEngine.drive(
        foreground: String?,
        features: FloatArray?,
        times: Int = 3,
        startAt: Long = 100_000L,
    ) {
        var now = startAt
        repeat(times) {
            evaluate(foreground, frame(features), now)
            now += 50
        }
    }

    /** An engine whose child 5 has exactly one app policy and one measured usage figure. */
    private fun engineWithLimit(
        executor: RecordingExecutor,
        limitMinutes: Int?,
        usedMinutes: Int?,
        mode: AppPolicyMode = AppPolicyMode.LIMIT,
        action: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        packageName: String = youtube,
        policyChildId: Long? = child5,
    ): ProtectionEngine {
        val engine = engine(executor)
        engine.updateContext(parent, listOf(child(child5, child5Vec)), setOf(packageName))
        engine.updateSettings(ProtectionSettings(), policySettings())
        engine.appPolicyLookup = { childId, pkg ->
            if (pkg == packageName) {
                AppPolicy(
                    packageName = packageName,
                    mode = mode,
                    action = action,
                    dailyLimitMinutes = limitMinutes,
                    childId = policyChildId,
                )
            } else {
                null
            }
        }
        engine.appTimeUsedMinutesLookup = { childId, pkg ->
            // The runtime resolves usage per child and package; a miss is "no measurement", not 0.
            if (childId == child5 && pkg == youtube) usedMinutes else null
        }
        return engine
    }

    // ---- the decision -------------------------------------------------------

    @Test
    fun usageBelowTheLimitIsNotBlocked() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 29)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue("under the limit must not act", exec.executed.isEmpty())
    }

    @Test
    fun usageExactlyAtTheLimitBlocksThroughTheExistingActionPath() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 30)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }

    @Test
    fun usageAboveTheLimitBlocks() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 31)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }

    @Test
    fun noMeasurementDoesNotEnforceEvenAZeroLimit() {
        // Usage Access unavailable: usage is unknown, so a 0-minute limit must not fire.
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 0, usedMinutes = null)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue("unknown usage must never be treated as reached", exec.executed.isEmpty())
    }

    @Test
    fun aMeasuredZeroReachesAZeroLimit() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 0, usedMinutes = 0)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
    }

    @Test
    fun aLimitWithoutMinutesIsNeverEnforced() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = null, usedMinutes = 5_000)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun anAllowedAppIsNotRestrictedByItsUsage() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(
            exec,
            limitMinutes = 30,
            usedMinutes = 5_000,
            mode = AppPolicyMode.ALLOW,
            action = ProtectionAction.ALLOW,
        )

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun aBlockedAppIsStillBlockedWithoutAnyUsage() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(
            exec,
            limitMinutes = null,
            usedMinutes = null,
            mode = AppPolicyMode.BLOCK,
            action = ProtectionAction.HARD_BLOCK,
        )

        engine.drive(youtube, child5Vec)

        assertEquals("BLOCK is independent of screen-time usage", ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }

    @Test
    fun protectionDisabledNeverEnforcesAScreenTimeLimit() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 500)
        engine.updateSettings(ProtectionSettings(), policySettings(enabled = false))

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun anAppWithNoUsageAndNoPolicyIsNotBlocked() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = null)

        engine.drive(calculator, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue("an unrelated app must not be blocked", exec.executed.isEmpty())
    }

    @Test
    fun anotherChildsUsageCannotSatisfyTheRecognisedChildsLimit() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        engine.updateContext(parent, listOf(child(child5, child5Vec), child(6L, child6Vec)), setOf(youtube))
        engine.updateSettings(ProtectionSettings(), policySettings())
        // Child 5 has the limit; the only measured usage belongs to child 6.
        engine.appPolicyLookup = { childId, pkg ->
            if (pkg == youtube) {
                AppPolicy(packageName = youtube, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = 30, childId = childId)
            } else {
                null
            }
        }
        engine.appTimeUsedMinutesLookup = { childId, pkg ->
            if (childId == 6L && pkg == youtube) 500 else null
        }

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue("another child's usage must not enforce", exec.executed.isEmpty())
    }

    @Test
    fun aPolicyBelongingToAnotherChildIsNotEnforced() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 500, policyChildId = 99L)

        engine.drive(youtube, child5Vec)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun enforcementFollowsTheCurrentScopedMeasurementAndNothingCached() {
        // The engine holds no usage of its own: it acts on whatever the account-scoped lookup
        // returns at evaluation time. Re-pointing that lookup is how an account switch appears to
        // it, and the decision must follow the new value rather than a remembered one.
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 45)
        engine.drive(youtube, child5Vec, times = 3, startAt = 100_000L)
        assertEquals("the current scope is over the limit", ProtectionState.SOFT_BLOCKED, engine.state.value)

        // A different account's data: no row for this child, so no measurement at all.
        engine.appTimeUsedMinutesLookup = { _, _ -> null }
        engine.drive(youtube, child5Vec, times = 3, startAt = 300_000L)
        assertEquals(
            "another account's absence of usage must not sustain the block",
            ProtectionState.RECOVERING,
            engine.state.value,
        )

        // And the original scope's measurement re-applies its limit.
        engine.appTimeUsedMinutesLookup = { childId, pkg -> if (childId == child5 && pkg == youtube) 45 else null }
        engine.drive(youtube, child5Vec, times = 3, startAt = 500_000L)
        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
    }

    // ---- idempotency --------------------------------------------------------

    @Test
    fun repeatedEvaluationOfAnExceededLimitActsOnceAndLogsOnce() {
        val exec = RecordingExecutor()
        val events = mutableListOf<ActivityEventType>()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 45)
        engine.onEvent = { type, _ -> events += type }

        engine.drive(youtube, child5Vec, times = 3, startAt = 100_000L)
        engine.drive(youtube, child5Vec, times = 3, startAt = 200_000L)
        engine.drive(youtube, child5Vec, times = 3, startAt = 300_000L)

        assertEquals("the block must be entered once, not once per evaluation", 1, exec.executed.size)
        assertEquals(
            "and it must be logged once, not once per evaluation",
            1,
            events.count { it == ActivityEventType.CHILD_BLOCKED },
        )
        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
    }

    // ---- the existing recovery path still governs the release ---------------

    @Test
    fun anExceededLimitEntersRecoveryInsteadOfReleasingImmediately() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 45)
        engine.drive(youtube, child5Vec, times = 3, startAt = 100_000L)
        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)

        // The child looks away: the restriction is lifted into the existing recovery window,
        // not released on the spot and not released by screen-time code.
        engine.drive(youtube, features = null, times = 3, startAt = 200_000L)

        assertEquals(ProtectionState.RECOVERING, engine.state.value)
        assertEquals("no second action was applied during recovery", 1, exec.executed.size)
    }

    @Test
    fun anExceededLimitBlocksAgainWhenTheChildReturns() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 45)
        engine.drive(youtube, child5Vec, times = 3, startAt = 100_000L)
        engine.drive(youtube, features = null, times = 3, startAt = 200_000L)
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        engine.drive(youtube, child5Vec, times = 3, startAt = 300_000L)

        assertEquals("still over the limit, so blocked again", ProtectionState.SOFT_BLOCKED, engine.state.value)
    }

    @Test
    fun aLimitOnlyAffectsTheAppItBelongsTo() {
        val exec = RecordingExecutor()
        val engine = engineWithLimit(exec, limitMinutes = 30, usedMinutes = 45)
        engine.drive(youtube, child5Vec, times = 3, startAt = 100_000L)
        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)

        // A different app with no policy of its own is unaffected by the first app's limit.
        engine.drive(calculator, child5Vec, times = 3, startAt = 300_000L)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }
}
