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
 * Group 5: the protection engine's activity-log events.
 *
 * Real engine + real evaluator + real recognizer; the action executor and the
 * event sink are recording test doubles. Verifies that events describe
 * meaningful transitions (no per-frame storm), keep no-face distinct from
 * unknown, only report a block once the action actually applied, and that a
 * failing event sink never breaks protection.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionEngineEventLoggingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val parentVec = FloatArray(8) { if (it == 0) 1f else 0f }
    private val childVec = FloatArray(8) { if (it == 1) 1f else 0f }
    private val unknownVec = FloatArray(8) { if (it == 2) 1f else 0f }

    private val parent = ParentProfile(
        id = 1L,
        accountId = 1L,
        displayName = "Parent",
        isFaceEnrolled = true,
        faceTemplateRef = FaceEmbeddingCodec.encode(parentVec),
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private val child = ChildProfile(
        id = 5L,
        accountId = 1L,
        childName = "Vali",
        isFaceEnrolled = true,
        faceTemplateRef = FaceEmbeddingCodec.encode(childVec),
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private val protectedApp = "com.example.youtube"

    private class RecordingExecutor : ProtectionActionExecutor {
        override val supportedActions = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()
        override fun execute(action: ProtectionAction) { executed += action }
        override fun clear() = Unit
        override fun mute() = Unit
        override fun unmute() = Unit
    }

    private fun engine(executor: RecordingExecutor): ProtectionEngine = ProtectionEngine(
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
        recovery: Long = 60_000L,
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

    private class RecordingEvents {
        val all = mutableListOf<Pair<ActivityEventType, String?>>()
        fun types() = all.map { it.first }
        fun count(type: ActivityEventType) = all.count { it.first == type }
    }

    private fun ProtectionEngine.collectEvents(): RecordingEvents {
        val recorder = RecordingEvents()
        onEvent = { type, detail -> recorder.all += type to detail }
        return recorder
    }

    @Test
    fun childRecognition_isLoggedOnceDespiteManyFrames() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, childVec)
        engine.drive(protectedApp, childVec, startAt = 200_000L)
        engine.drive(protectedApp, childVec, startAt = 300_000L)

        assertEquals(1, events.count(ActivityEventType.CHILD_RECOGNIZED))
        assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }

    @Test
    fun unknownUser_isLoggedOnceDespiteManyFrames() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings(unknown = ProtectionAction.SOFT_BLOCK))

        engine.drive(protectedApp, unknownVec)
        engine.drive(protectedApp, unknownVec, startAt = 200_000L)

        assertEquals(1, events.count(ActivityEventType.UNKNOWN_USER))
        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
    }

    @Test
    fun noFace_isLoggedAsNoFaceAndNeverAsUnknownUser() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(
            ProtectionSettings(),
            policySettings(unknown = ProtectionAction.HARD_BLOCK, noFace = ProtectionAction.ALLOW),
        )

        engine.drive(protectedApp, null)

        assertEquals(1, events.count(ActivityEventType.NO_FACE))
        assertEquals(0, events.count(ActivityEventType.UNKNOWN_USER))
    }

    @Test
    fun parentRecognition_isLoggedOnceAndNeverBlocks() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, parentVec)
        engine.drive(protectedApp, parentVec, startAt = 200_000L)

        assertEquals(1, events.count(ActivityEventType.PARENT_RECOGNIZED))
        assertEquals(0, events.count(ActivityEventType.CHILD_BLOCKED))
        assertTrue(exec.executed.isEmpty())
    }

    @Test
    fun softBlockPolicy_isLoggedAsAChildBlock() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.appPolicyLookup = { _, _ ->
            AppPolicy(protectedApp, mode = AppPolicyMode.BLOCK, action = ProtectionAction.SOFT_BLOCK, childId = 5L)
        }
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, childVec)

        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
        assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }

    @Test
    fun pendingActivation_logsNoBlockEvent() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings(activation = 400L))

        engine.drive(protectedApp, childVec)

        // The decision exists, but the action was not applied yet -> no event.
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertTrue(exec.executed.isEmpty())
        assertEquals(0, events.count(ActivityEventType.CHILD_BLOCKED))
    }

    @Test
    fun recovery_producesNoSpuriousBlockEvents() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        val events = engine.collectEvents()
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings(recovery = 60_000L))

        engine.drive(protectedApp, childVec)
        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)

        engine.drive(protectedApp, null, startAt = 400_000L)

        assertEquals(ProtectionState.RECOVERING, engine.state.value)
        assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))
    }

    @Test
    fun failingEventSink_neverBreaksProtection() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        engine.onEvent = { _, _ -> throw IllegalStateException("log sink down") }
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, childVec)

        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }
}
