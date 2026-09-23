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
import uz.faceguard.app.core.pipeline.FaceQuality
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
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Group 9: the engine's liveness signal, driven with real recognition input (real
 * [Recognizer] + encoded templates + real frames), exactly like the Group 8
 * identity tests. Proves: liveness and identity stay independent, SPOOF is a safe
 * gate that is evaluated before identity (so a spoofed parent cannot unlock), and
 * LIVE leaves the existing child/parent behaviour untouched.
 *
 * No anti-spoofing model is bundled, so the SPOOF cases inject a per-frame model
 * score ([FrameEvent.liveProbability]); this tests the architecture, not
 * real-world anti-spoofing efficacy.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionEngineLivenessTest {

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

    private fun frame(
        features: FloatArray?,
        timestamp: Long,
        facePresent: Boolean = features != null,
        yaw: Float = 0f,
        pitch: Float = 0f,
        liveProbability: Float? = null,
    ): FrameEvent = FrameEvent(
        image = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0),
        faceCount = if (facePresent) 1 else 0,
        features = features,
        quality = FaceQuality(
            faceCount = if (facePresent) 1 else 0,
            headEulerAngleX = pitch,
            headEulerAngleY = yaw,
        ),
        liveProbability = liveProbability,
        timestamp = timestamp,
    )

    private fun policySettings(
        unknown: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        noFace: ProtectionAction = ProtectionAction.ALLOW,
        spoof: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ) = PolicySettings(
        enabled = true,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = unknown,
        noFaceAction = noFace,
        spoofAction = spoof,
    )

    /** Drives frames with alternating yaw so the passive heuristic sees motion. */
    private fun ProtectionEngine.driveLive(
        foreground: String?,
        features: FloatArray?,
        times: Int = 4,
        startAt: Long = 100_000L,
    ) {
        val yaws = floatArrayOf(0f, 6f, -6f, 8f, -8f, 10f)
        var now = startAt
        repeat(times) { i ->
            evaluate(foreground, frame(features, timestamp = now, yaw = yaws[i % yaws.size]), now)
            now += 50
        }
    }

    /** Drives frames carrying a low model score so the detector reports SPOOF. */
    private fun ProtectionEngine.driveSpoof(
        foreground: String?,
        features: FloatArray?,
        times: Int = 4,
        startAt: Long = 100_000L,
    ) {
        var now = startAt
        repeat(times) {
            evaluate(foreground, frame(features, timestamp = now, liveProbability = 0.1f), now)
            now += 50
        }
    }

    /** Drives frames with no detected face (camera present, face absent). */
    private fun ProtectionEngine.driveNoFace(
        foreground: String?,
        times: Int = 4,
        startAt: Long = 100_000L,
    ) {
        var now = startAt
        repeat(times) {
            evaluate(foreground, frame(features = null, timestamp = now, facePresent = false), now)
            now += 50
        }
    }

    private fun engineWith(
        parentProfile: ParentProfile?,
        children: List<ChildProfile>,
        policy: PolicySettings = policySettings(),
    ): Pair<ProtectionEngine, RecordingExecutor> {
        val exec = RecordingExecutor()
        val engine = engine(exec)
        engine.updateContext(parentProfile, children, setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policy)
        return engine to exec
    }

    @Test
    fun liveChildOnProtectedApp_stillBlocks() {
        val (engine, exec) = engineWith(parent, listOf(child))

        engine.driveLive(protectedApp, childVec)

        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
        assertEquals(UserIdentity.CHILD, engine.identity.value?.identity)
        assertEquals(LivenessState.LIVE, engine.liveness.value?.state)
    }

    @Test
    fun liveParentIsNeverBlocked() {
        val (engine, exec) = engineWith(parent, listOf(child))

        engine.driveLive(protectedApp, parentVec)

        assertEquals(UserIdentity.PARENT, engine.identity.value?.identity)
        assertEquals(LivenessState.LIVE, engine.liveness.value?.state)
        assertTrue("a live parent must not be blocked", exec.executed.isEmpty())
    }

    @Test
    fun spoofedChildIdentity_isNotTrustedAndUsesTheSpoofAction() {
        val (engine, exec) = engineWith(parent, listOf(child))

        engine.driveSpoof(protectedApp, childVec)

        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
        assertEquals(LivenessState.SPOOF, engine.liveness.value?.state)
        // The face is still *recognised* as the child; the spoof gate is what blocks.
        assertEquals(UserIdentity.CHILD, engine.identity.value?.identity)
    }

    @Test
    fun spoofedParentDoesNotUnlock() {
        val (engine, exec) = engineWith(parent, listOf(child))

        engine.driveSpoof(protectedApp, parentVec)

        assertEquals(UserIdentity.PARENT, engine.identity.value?.identity)
        assertEquals(LivenessState.SPOOF, engine.liveness.value?.state)
        assertEquals(
            "a spoofed parent frame must not inherit the parent override",
            listOf(ProtectionAction.SOFT_BLOCK),
            exec.executed,
        )
    }

    @Test
    fun theSpoofActionIsConfigurable() {
        val (engine, exec) = engineWith(parent, listOf(child), policySettings(spoof = ProtectionAction.HARD_BLOCK))

        engine.driveSpoof(protectedApp, childVec)

        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
    }

    @Test
    fun noFaceFollowsTheNoFacePolicy() {
        val (engine, exec) = engineWith(parent, listOf(child), policySettings(noFace = ProtectionAction.ALLOW))

        engine.driveNoFace(protectedApp)

        assertEquals(LivenessState.NO_FACE, engine.liveness.value?.state)
        assertTrue("no-face with ALLOW must not act", exec.executed.isEmpty())
    }

    @Test
    fun unknownUserFollowsTheUnknownPolicy() {
        val (engine, exec) = engineWith(parent, listOf(child), policySettings(unknown = ProtectionAction.SOFT_BLOCK))

        engine.driveLive(protectedApp, unknownVec)

        assertEquals(UserIdentity.UNKNOWN, engine.identity.value?.identity)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), exec.executed)
    }

    @Test
    fun livenessAndIdentityAreTwoIndependentSignals() {
        val (engine, _) = engineWith(parent, listOf(child))

        engine.driveLive(protectedApp, childVec)

        assertEquals("identity signal", UserIdentity.CHILD, engine.identity.value?.identity)
        assertEquals("liveness signal", LivenessState.LIVE, engine.liveness.value?.state)
        assertEquals(7L, engine.identity.value?.childId)
    }

    @Test
    fun resetLivenessClearsTheSignal() {
        val (engine, _) = engineWith(parent, listOf(child))
        engine.driveLive(protectedApp, childVec)

        engine.resetLiveness()

        assertNull(engine.liveness.value)
    }
}
