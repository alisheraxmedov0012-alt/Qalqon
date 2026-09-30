package uz.faceguard.app.core

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Group 5 gap closure: the real [ProtectionEngine] lifecycle
 * (`start()` → tick loop → recovery coroutine → `safeLog` → event sink) for the
 * `PROTECTION_RELEASED` event.
 *
 * The engine is started for real, so its tick loop runs alongside the test. The
 * tick only tears a recovery session down when the foreground is not a protected
 * app, so the test drives the foreground exactly like the runtime does (the
 * protected app stays open while the child looks away). [withForeground] injects
 * that value; `ForegroundAppMonitor` exposes only a read-only `StateFlow` and no
 * mock framework is available, so this stays a test-only detail and production
 * code is untouched.
 *
 * All engine interaction and every coroutine share one single-threaded
 * dispatcher, so the tick loop and the test cannot race on engine state.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionEngineRecoveryReleaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val parentVec = FloatArray(8) { if (it == 0) 1f else 0f }
    private val childVec = FloatArray(8) { if (it == 1) 1f else 0f }

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

    private fun frame(features: FloatArray?): FrameEvent = FrameEvent(
        image = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0),
        features = features,
    )

    private fun policySettings(recovery: Long) = PolicySettings(
        enabled = true,
        activationDelayMs = 0L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        recoveryDelayMs = recovery,
    )

    private fun ProtectionEngine.drive(
        foreground: String?,
        features: FloatArray?,
        times: Int,
        startAt: Long,
    ) {
        var now = startAt
        repeat(times) {
            evaluate(foreground, frame(features), now)
            now += 50
        }
    }

    private class RecordingEvents {
        private val list = CopyOnWriteArrayList<Pair<ActivityEventType, String?>>()
        fun add(type: ActivityEventType, detail: String?) { list += type to detail }
        fun count(type: ActivityEventType) = list.count { it.first == type }
    }

    private suspend fun RecordingEvents.awaitCount(
        type: ActivityEventType,
        expected: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (count(type) >= expected) return true
            delay(25)
        }
        return count(type) >= expected
    }

    /** Test-only: see the class comment. */
    private fun ForegroundAppMonitor.withForeground(packageName: String?) {
        val field = ForegroundAppMonitor::class.java.getDeclaredField("_current")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableStateFlow<String?>).value = packageName
    }

    @Test
    fun realRecoveryLifecycle_emitsProtectionReleasedOnceAfterTheDelay() = runBlocking {
        val exec = RecordingExecutor()
        val monitor = ForegroundAppMonitor(context).apply { withForeground(protectedApp) }
        val engine = ProtectionEngine(Recognizer(), monitor, exec, DefaultPolicyEvaluator())
        val events = RecordingEvents()
        engine.onEvent = { type, detail -> events.add(type, detail) }
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings(recovery = 200L))

        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            engine.start(scope) // real lifecycle: tick loop + frame job on this scope

            // 1. Child is recognized on a protected app -> the block is applied.
            withContext(dispatcher) { engine.drive(protectedApp, childVec, times = 5, startAt = 100_000L) }
            assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
            assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
            assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))

            // 2. Child disappears -> recovery starts, nothing released yet.
            withContext(dispatcher) { engine.drive(protectedApp, null, times = 3, startAt = 400_000L) }
            assertEquals(ProtectionState.RECOVERING, engine.state.value)
            assertEquals(0, events.count(ActivityEventType.PROTECTION_RELEASED))

            // 3. The recovery delay elapses -> protection is really released and logged.
            assertTrue(
                "PROTECTION_RELEASED must arrive after the recovery delay",
                events.awaitCount(ActivityEventType.PROTECTION_RELEASED, 1, 5_000L),
            )
            assertEquals(ProtectionState.UNPROTECTED, engine.state.value)
            assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))

            // 4. No duplicate storm while the same state keeps being observed.
            withContext(dispatcher) { engine.drive(protectedApp, null, times = 3, startAt = 600_000L) }
            delay(300L)
            assertEquals(1, events.count(ActivityEventType.PROTECTION_RELEASED))
            assertEquals(1, events.count(ActivityEventType.CHILD_BLOCKED))
        } finally {
            engine.stop()
            scope.cancel()
            executor.shutdown()
        }
    }
}
