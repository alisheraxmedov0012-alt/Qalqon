package uz.faceguard.app.core

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 9: the recovery lifecycle of the real [ProtectionEngine].
 *
 * The engine is driven through its real, public `evaluate()` on a single-threaded
 * dispatcher, and its session scope (the field `start()` sets) is injected with
 * that dispatcher so the real recovery coroutine runs there. Not starting the tick
 * loop is deliberate: it removes the only source of nondeterminism (a background
 * tick re-evaluating frames), so every assertion is exact and repeatable while the
 * code under test - `beginRecovery`, the generation guard, `releaseRecovered`,
 * `cancelRecovery` and `clearBlock` - is unchanged production code.
 *
 * Covered: block -> recovering -> release, re-trigger during and after recovery,
 * multiple cycles, duplicate-timer prevention, stale-timer containment, zero and
 * non-zero recovery delays, cancel/stop/emergency-unlock cancellation, overlay
 * removed once, event consistency (no spam), parent/unknown/no-face during
 * recovery, protected-app transition during recovery, and best-effort context.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionEngineRecoveryLifecycleTest {

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
    private val otherApp = "com.example.calculator"

    private class RecordingExecutor : ProtectionActionExecutor {
        override val supportedActions = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()
        var clearCount = 0
        override fun execute(action: ProtectionAction) { executed += action }
        override fun clear() { clearCount++ }
        override fun mute() = Unit
        override fun unmute() = Unit
    }

    private class RecordingEvents {
        private val all = CopyOnWriteArrayList<Pair<ActivityEventType, String?>>()
        fun add(type: ActivityEventType, detail: String?) { all += type to detail }
        fun count(type: ActivityEventType) = all.count { it.first == type }
    }

    private fun frame(features: FloatArray?): FrameEvent = FrameEvent(
        image = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0),
        faceCount = if (features != null) 1 else 0,
        features = features,
    )

    private fun policySettings(recovery: Long = 200L) = PolicySettings(
        enabled = true,
        activationDelayMs = 0L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        recoveryDelayMs = recovery,
    )

    /** Real engine + recording doubles, on one single-threaded dispatcher. */
    private inner class Harness(recovery: Long) : AutoCloseable {
        val exec = RecordingExecutor()
        val events = RecordingEvents()
        val engine = ProtectionEngine(Recognizer(), ForegroundAppMonitor(context), exec, DefaultPolicyEvaluator())
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        init {
            engine.onEvent = { type, detail -> events.add(type, detail) }
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings(recovery))
            // Inject the session scope the engine's recovery coroutine runs on,
            // without starting the (nondeterministic) tick loop.
            ProtectionEngine::class.java.getDeclaredField("scope").let {
                it.isAccessible = true
                it.set(engine, scope)
            }
        }

        fun drive(foreground: String?, features: FloatArray?, times: Int, startAt: Long) {
            var now = startAt
            repeat(times) {
                engine.evaluate(foreground, frame(features), now)
                now += 50
            }
        }

        override fun close() {
            engine.stop()
            scope.cancel()
            executor.shutdown()
        }
    }

    private fun runHarness(recovery: Long = 200L, body: suspend Harness.() -> Unit) = runBlocking {
        val harness = Harness(recovery)
        try {
            harness.body()
        } finally {
            harness.close()
        }
    }

    private suspend fun Harness.block() = withContext(dispatcher) {
        drive(protectedApp, childVec, times = 5, startAt = 100_000L)
    }

    /** 3 confirmation frames: enough to start recovery, short of the obstruction streak. */
    private suspend fun Harness.loseFace(times: Int = 3, startAt: Long = 400_000L) = withContext(dispatcher) {
        drive(protectedApp, null, times = times, startAt = startAt)
    }

    private suspend fun Harness.childReturns(startAt: Long = 700_000L) = withContext(dispatcher) {
        drive(protectedApp, childVec, times = 5, startAt = startAt)
    }

    private suspend fun Harness.awaitReleased(minCount: Int = 1, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (events.count(ActivityEventType.PROTECTION_RELEASED) >= minCount) return true
            delay(10)
        }
        return events.count(ActivityEventType.PROTECTION_RELEASED) >= minCount
    }

    // 1 + 2 + 3
    @Test
    fun childBlocks_thenFaceLostRecovers_thenReleasesOnce() = runHarness(150L) {
        block()
        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
        assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))
        assertEquals("best-effort restoration target", protectedApp, engine.blockedApp.value)

        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)
        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))

        assertTrue("release must follow the recovery delay", awaitReleased(1))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertNull("the cycle's restoration target is dropped", engine.blockedApp.value)
    }

    // 4 + 8 + 19
    @Test
    fun childReturningDuringRecovery_reblocksAndTheStaleTimerNeverReleases() = runHarness(500L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        childReturns()
        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        val clearsAfterReBlock = exec.clearCount

        // Wait well past the first cycle's delay: the stale timer must not release.
        delay(900L)

        assertEquals("a stale timer must not release a newer cycle", ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals("a stale timer must not remove the new overlay", clearsAfterReBlock, exec.clearCount)
        assertEquals(2, events.count(ActivityEventType.CHILD_BLOCKED))
    }

    // 5
    @Test
    fun childReturningAfterRelease_reblocks() = runHarness(150L) {
        block()
        loseFace()
        assertTrue(awaitReleased(1))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)

        childReturns(startAt = 900_000L)

        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        assertEquals(2, events.count(ActivityEventType.CHILD_BLOCKED))
        assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))
    }

    // 6 + 23
    @Test
    fun multipleRecoveryCycles_releaseOncePerCycle() = runHarness(150L) {
        block()
        loseFace()
        assertTrue(awaitReleased(1))

        childReturns(startAt = 900_000L)
        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        loseFace(startAt = 1_200_000L)
        assertEquals(ProtectionState.RECOVERING, engine.state.value)
        assertTrue(awaitReleased(2))

        assertEquals(2, events.count(ActivityEventType.CHILD_BLOCKED))
        assertEquals(2, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
    }

    // 7
    @Test
    fun duplicateRecoveryEvaluation_doesNotCreateASecondTimer() = runHarness(300L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        // More no-face frames while already RECOVERING must not arm another timer
        // (the obstruction streak is kept below its limit).
        loseFace(times = 2, startAt = 405_000L)
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        assertTrue(awaitReleased(1))
        delay(400L)

        assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals("the overlay is removed exactly once", 1, exec.clearCount)
    }

    // 9
    @Test
    fun zeroRecoveryDelay_releasesImmediatelyExactlyOnce() = runHarness(0L) {
        block()
        loseFace()

        assertTrue(awaitReleased(1))
        delay(300L)

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals(1, exec.clearCount)
    }

    // 22
    @Test
    fun noEventSpamAcrossARecoveryCycle() = runHarness(150L) {
        block()
        loseFace()
        assertTrue(awaitReleased(1))

        // Extra frames after the release must not produce duplicate events.
        loseFace(times = 3, startAt = 700_000L)
        delay(300L)

        assertEquals(1, events.count(ActivityEventType.CHILD_RECOGNIZED))
        assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))
        assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals(0, events.count(ActivityEventType.PARENT_UNLOCKED))
    }

    // 11 + 13
    @Test
    fun cancelRecovery_dropsThePendingReleaseWithoutLoggingIt() = runHarness(300L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        withContext(dispatcher) { engine.cancelRecovery() }
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertNull(engine.blockedApp.value)

        delay(500L)

        assertEquals("a cancelled cycle must not release", 0, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
    }

    // 13
    @Test
    fun stop_cancelsAPendingRecovery() = runHarness(300L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        withContext(dispatcher) { engine.stop() }
        delay(500L)

        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
    }

    @Test
    fun emergencyUnlock_cancelsAPendingRecovery() = runHarness(300L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        withContext(dispatcher) { engine.emergencyUnlock() }
        delay(500L)

        assertEquals(1, events.count(ActivityEventType.EMERGENCY_UNLOCK))
        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
    }

    // 15
    @Test
    fun parentDuringRecovery_releasesThroughThePolicyEngine() = runHarness(600L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        withContext(dispatcher) { drive(protectedApp, parentVec, times = 5, startAt = 900_000L) }

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertEquals(1, events.count(ActivityEventType.PARENT_UNLOCKED))
        assertEquals(1, events.count(ActivityEventType.PARENT_RECOGNIZED))
        delay(900L)
        assertEquals("the superseded recovery must not also release", 0, events.count(ActivityEventType.PROTECTION_RELEASED))
    }

    // 16
    @Test
    fun unknownDuringRecovery_followsTheUnknownPolicy() = runHarness(600L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        withContext(dispatcher) { drive(protectedApp, unknownVec, times = 5, startAt = 900_000L) }

        assertEquals(ProtectionState.SOFT_BLOCKED, engine.state.value)
        assertEquals(1, events.count(ActivityEventType.UNKNOWN_USER))
        delay(900L)
        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))
    }

    // 17 + 13 (policy stays the source of truth)
    @Test
    fun noFaceDuringRecovery_staysRecoveringUntilTheDelayElapses() = runHarness(300L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)
        // no-face policy is ALLOW, but the recovery window must not be bypassed.
        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))

        assertTrue(awaitReleased(1))
        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
    }

    // 18/24
    @Test
    fun leavingTheProtectedAppDuringRecovery_releasesWithoutLeakingToTheOtherApp() = runHarness(600L) {
        block()
        loseFace()
        assertEquals(ProtectionState.RECOVERING, engine.state.value)

        withContext(dispatcher) { drive(otherApp, null, times = 3, startAt = 900_000L) }

        assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
        assertNull(engine.blockedApp.value)
        assertTrue("the overlay is removed when the protected app is left", exec.clearCount >= 1)
        // The aborted cycle did not run the recovery rule, so it is not reported
        // as a recovery release (at most one per cycle, never a false one).
        assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))
    }
}
