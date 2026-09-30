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
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Group 3 integration: real ProtectionEngine + real DefaultPolicyEvaluator,
 * driven through the public evaluate(...) entry point with real recognition
 * input (real Recognizer, encoded templates). The action executor is a
 * recording test double - production executors stay untouched.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionEnginePolicyIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val parentVec = FloatArray(8) { if (it == 0) 1f else 0f }
    private val childVec = FloatArray(8) { if (it == 1) 1f else 0f }
    private val child6Vec = FloatArray(8) { if (it == 3) 1f else 0f }
    private val unknownVec = FloatArray(8) { if (it == 2) 1f else 0f }

    private val parent = ParentProfile(
        id = 1L,
        accountId = 1L,
        displayName = "Parent",
        isFaceEnrolled = true,
        faceTemplateRef = FaceEmbeddingCodec.encode(parentVec),
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private fun child(id: Long, vec: FloatArray = childVec) = ChildProfile(
        id = id,
        accountId = 1L,
        childName = "Child" + id,
        isFaceEnrolled = true,
        faceTemplateRef = FaceEmbeddingCodec.encode(vec),
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private val protectedApp = "com.example.youtube"
    private val allowedApp = "com.example.calculator"

    private class RecordingExecutor : ProtectionActionExecutor {
        override val supportedActions = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()
        var clearCount = 0
        override fun execute(action: ProtectionAction) { executed += action }
        override fun clear() { clearCount++ }
        override fun mute() = Unit
        override fun unmute() = Unit
    }

    private fun engine(executor: RecordingExecutor): ProtectionEngine =
        ProtectionEngine(
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
        activation: Long = 0L,
        recovery: Long = 30_000L,
        unknown: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        noFace: ProtectionAction = ProtectionAction.ALLOW,
    ) = PolicySettings(
        enabled = enabled,
        activationDelayMs = activation,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = unknown,
        noFaceAction = noFace,
        recoveryDelayMs = recovery,
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

    @Test
    fun parent_isNeverRestricted() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings())

        e.drive(protectedApp, parentVec)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue("parent must not trigger any protection action", exec.executed.isEmpty())
    }

    @Test
    fun child_onProtectedApp_hardBlocks() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings())

        e.drive(protectedApp, childVec)

        assertEquals(ProtectionState.HARD_BLOCKED, e.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }

    @Test
    fun child_onAllowedApp_isNotBlocked() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings())

        e.drive(allowedApp, childVec)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun childAppPolicy_allow_overridesGlobalProtectedList() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.appPolicyLookup = { childId, pkg ->
            if (childId == 5L && pkg == protectedApp) {
                AppPolicy(protectedApp, mode = AppPolicyMode.ALLOW, childId = 5L)
            } else null
        }
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings())

        e.drive(protectedApp, childVec)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun childAppPolicy_block_usesItsAction() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.appPolicyLookup = { _, _ ->
            AppPolicy(protectedApp, mode = AppPolicyMode.BLOCK, action = ProtectionAction.SOFT_BLOCK, childId = 5L)
        }
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings())

        e.drive(protectedApp, childVec)

        assertEquals(ProtectionState.SOFT_BLOCKED, e.state.value)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }

    @Test
    fun childAppPolicy_limit_isAllowedUntilTheDailyLimit() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.appPolicyLookup = { _, _ ->
            AppPolicy(protectedApp, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = 30, childId = 5L)
        }
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        // Usage tracking lands later; appTimeUsedMinutes is 0 so LIMIT is not exceeded.
        e.updateSettings(ProtectionSettings(), policySettings())

        e.drive(protectedApp, childVec)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun unknownUser_followsConfiguredPolicy() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(unknown = ProtectionAction.SOFT_BLOCK))

        e.drive(protectedApp, unknownVec)

        assertEquals(ProtectionState.SOFT_BLOCKED, e.state.value)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }

    @Test
    fun noFace_allowPolicy_doesNotBlock() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(noFace = ProtectionAction.ALLOW))

        e.drive(protectedApp, null)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun noFaceIsNotTreatedAsUnknownOrChild() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(
            ProtectionSettings(),
            policySettings(unknown = ProtectionAction.HARD_BLOCK, noFace = ProtectionAction.ALLOW),
        )

        e.drive(protectedApp, null)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue("no-face must follow the no-face policy", exec.executed.isEmpty())
    }

    @Test
    fun protectionDisabled_neverExecutesActions() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(enabled = false))

        e.drive(protectedApp, childVec)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue("disabled protection must not act", exec.executed.isEmpty())
    }

    @Test
    fun activationDelay_defersThenAppliesTheAction() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(activation = 400L))

        e.drive(protectedApp, childVec)
        assertTrue("action must not apply before the activation delay", exec.executed.isEmpty())
        assertEquals(ProtectionState.UNPROTECTED, e.state.value)

        Thread.sleep(500L)
        e.drive(protectedApp, childVec, startAt = 200_000L)

        assertEquals(ProtectionState.HARD_BLOCKED, e.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }

    @Test
    fun zeroActivationDelay_appliesImmediately() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(activation = 0L))

        e.drive(protectedApp, childVec)

        assertEquals(ProtectionState.HARD_BLOCKED, e.state.value)
    }

    @Test
    fun parentCancelsAPendingActivation() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(activation = 60_000L))

        e.drive(protectedApp, childVec)
        assertTrue(exec.executed.isEmpty())

        e.drive(protectedApp, parentVec, startAt = 300_000L)

        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue("parent must cancel the pending child activation", exec.executed.isEmpty())
    }

    @Test
    fun blockedThenFaceLost_entersRecovery() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(recovery = 60_000L))

        e.drive(protectedApp, childVec)
        assertEquals(ProtectionState.HARD_BLOCKED, e.state.value)

        e.drive(protectedApp, null, startAt = 400_000L)

        assertEquals(ProtectionState.RECOVERING, e.state.value)
    }

    @Test
    fun childAppIsolation_perChildPoliciesDoNotLeak() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.appPolicyLookup = { childId, _ ->
            if (childId == 5L) AppPolicy(protectedApp, mode = AppPolicyMode.BLOCK, childId = 5L)
            else AppPolicy(protectedApp, mode = AppPolicyMode.ALLOW, childId = 6L)
        }
        e.updateContext(parent, listOf(child(5L), child(6L, child6Vec)), setOf(protectedApp))

        e.updateSettings(ProtectionSettings(), policySettings())
        e.drive(protectedApp, child6Vec)
        assertEquals(ProtectionState.UNPROTECTED, e.state.value)
        assertTrue(exec.executed.isEmpty())

        e.drive(protectedApp, childVec, startAt = 500_000L)
        assertEquals(ProtectionState.HARD_BLOCKED, e.state.value)
    }

    @Test
    fun settingsUpdate_changesTheRuntimeDecision() {
        val exec = RecordingExecutor()
        val e = engine(exec)
        e.updateContext(parent, listOf(child(5L)), setOf(protectedApp))
        e.updateSettings(ProtectionSettings(), policySettings(enabled = false))

        e.drive(protectedApp, childVec)
        assertTrue(exec.executed.isEmpty())

        e.updateSettings(ProtectionSettings(), policySettings(enabled = true))
        e.drive(protectedApp, childVec, startAt = 600_000L)

        assertEquals(ProtectionState.HARD_BLOCKED, e.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }
}
