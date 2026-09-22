package uz.faceguard.app.core

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.embed.FaceEmbeddingCodec
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.core.protection.IdentitySource
import uz.faceguard.app.core.protection.ProtectionActionExecutor
import uz.faceguard.app.core.protection.ProtectionEngine
import uz.faceguard.app.core.protection.ProtectionSettings
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Group 8: the engine's identity signal, driven with real recognition input
 * (real [Recognizer] + encoded templates). This is the "who is looking" signal;
 * the foreground app is a separate signal covered by the Group 7 tests.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionEngineIdentityTest {

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
        id = 7L,
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
        unknown: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        noFace: ProtectionAction = ProtectionAction.ALLOW,
    ) = PolicySettings(
        enabled = true,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = unknown,
        noFaceAction = noFace,
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

    /** Drives the engine with no camera frame at all (stale/absent signal). */
    private fun ProtectionEngine.driveWithoutFrame(
        foreground: String?,
        times: Int = 3,
        startAt: Long = 100_000L,
    ) {
        var now = startAt
        repeat(times) {
            evaluate(foreground, null, now)
            now += 50
        }
    }

    @Test
    fun childFrame_publishesChildIdentityWithItsId() {
        val engine = engine(RecordingExecutor())
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, childVec)

        val identity = engine.identity.value
        assertEquals(UserIdentity.CHILD, identity?.identity)
        assertEquals(7L, identity?.childId)
        assertEquals("Vali", identity?.childName)
        assertEquals(IdentitySource.CAMERA, identity?.source)
    }

    @Test
    fun parentFrame_publishesParentIdentity() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, parentVec)

        assertEquals(UserIdentity.PARENT, engine.identity.value?.identity)
        assertNull("parent identity carries no child id", engine.identity.value?.childId)
        assertTrue("parent must never be blocked", exec.executed.isEmpty())
    }

    @Test
    fun unknownFrame_publishesUnknownIdentity() {
        val engine = engine(RecordingExecutor())
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, unknownVec)

        assertEquals(UserIdentity.UNKNOWN, engine.identity.value?.identity)
    }

    @Test
    fun missingFrame_publishesNoFaceWithNoCameraSource() {
        val engine = engine(RecordingExecutor())
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings(noFace = ProtectionAction.ALLOW))

        engine.driveWithoutFrame(protectedApp)

        assertEquals(UserIdentity.NO_FACE, engine.identity.value?.identity)
        assertEquals(IdentitySource.NONE, engine.identity.value?.source)
    }

    @Test
    fun identityTransitionFromChildToNoFaceIsRecorded() {
        val engine = engine(RecordingExecutor())
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings(noFace = ProtectionAction.ALLOW))

        engine.drive(protectedApp, childVec)
        assertEquals(UserIdentity.CHILD, engine.identity.value?.identity)

        engine.driveWithoutFrame(protectedApp, startAt = 400_000L)

        assertEquals(UserIdentity.NO_FACE, engine.identity.value?.identity)
    }

    @Test
    fun resetIdentity_clearsTheSignal() {
        val engine = engine(RecordingExecutor())
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())
        engine.drive(protectedApp, childVec)

        engine.resetIdentity()

        assertNull(engine.identity.value)
    }

    @Test
    fun childOnProtectedApp_blocksAndKeepsTheChildIdentity() {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())

        engine.drive(protectedApp, childVec)

        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(UserIdentity.CHILD, engine.identity.value?.identity)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }
}
