package uz.faceguard.app.core

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.FaceGuardApp
import uz.faceguard.app.core.embed.FaceEmbeddingCodec
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.core.protection.ProtectionEngine
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.protection.ProtectionSettings
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 9: the runtime lifecycle boundaries must drop a pending recovery.
 *
 * The CI emulator has no controllable camera, so the runtime's own engine is
 * driven through its real, production `evaluate()` (reached via reflection, the
 * same instance the runtime owns) instead of through frames. Each test asserts
 * the binding invariant from the spec: after an account change / sign-out /
 * disable / explicit stop, the previous cycle's pending recovery must never emit
 * a `PROTECTION_RELEASED` (or modify the new session), even after its original
 * delay has long elapsed. The engine-level cancellation mechanic itself is
 * verified directly in [ProtectionEngineRecoveryLifecycleTest].
 */
@RunWith(AndroidJUnit4::class)
class ProtectionRuntimeRecoveryTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app = appContext as FaceGuardApp

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)

    private val runtime: ProtectionRuntime get() = app.protectionRuntime

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
    private val recoveryMs = 250L

    private val released = CopyOnWriteArrayList<ActivityEventType>()
    private var previousOnEvent: ((ActivityEventType, String?) -> Unit)? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val engine: ProtectionEngine
        get() = ProtectionRuntime::class.java.getDeclaredField("engine").let {
            it.isAccessible = true
            it.get(runtime) as ProtectionEngine
        }

    private val monitor: ForegroundAppMonitor
        get() = ProtectionRuntime::class.java.getDeclaredField("monitor").let {
            it.isAccessible = true
            it.get(runtime) as ForegroundAppMonitor
        }

    private fun withForeground(packageName: String?) {
        val field = ForegroundAppMonitor::class.java.getDeclaredField("_current")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(monitor) as MutableStateFlow<String?>).value = packageName
    }

    @Before
    fun setUp() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        released.clear()
        // The runtime may have left the singleton engine started from an earlier
        // test; settle so this test owns a stopped engine, then capture its sink.
        delay(700L)
        previousOnEvent = engine.onEvent
        engine.onEvent = { type, _ -> released += type }
        engine.updateContext(parent, listOf(child), setOf(protectedApp))
        engine.updateSettings(ProtectionSettings(), policySettings())
        withForeground(protectedApp)
        engine.start(scope)
    }

    @After
    fun tearDown() = runBlocking {
        withContext(dispatcher) { engine.stop() }
        previousOnEvent?.let { engine.onEvent = it }
        scope.cancel()
        executor.shutdown()
        settingsStore.clearAll()
        session.clearSession()
        withForeground(null)
        delay(200L)
    }

    private fun policySettings() = PolicySettings(
        enabled = true,
        activationDelayMs = 0L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        recoveryDelayMs = recoveryMs,
    )

    private fun frame(features: FloatArray?): FrameEvent = FrameEvent(
        image = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0),
        faceCount = if (features != null) 1 else 0,
        features = features,
    )

    private suspend fun ProtectionEngine.driveBlocked() = withContext(dispatcher) {
        var now = 100_000L
        repeat(5) { evaluate(protectedApp, frame(childVec), now); now += 50 }
    }

    private suspend fun ProtectionEngine.driveNoFace() = withContext(dispatcher) {
        var now = 400_000L
        repeat(5) { evaluate(protectedApp, frame(null), now); now += 50 }
    }

    private suspend fun awaitState(expected: ProtectionState, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.state.value == expected) return true
            delay(10)
        }
        return engine.state.value == expected
    }

    private suspend fun enterRecovery() {
        withContext(dispatcher) { engine.driveBlocked() }
        assertEquals(ProtectionState.HARD_BLOCKED, engine.state.value)
        withContext(dispatcher) { engine.driveNoFace() }
        assertEquals(ProtectionState.RECOVERING, engine.state.value)
    }

    private suspend fun assertNeverReleased() {
        delay(recoveryMs * 3)
        assertEquals(
            "a stale recovery cycle must not emit PROTECTION_RELEASED",
            0,
            released.count { it == ActivityEventType.PROTECTION_RELEASED },
        )
        assertNotEquals(ProtectionState.RECOVERING, engine.state.value)
    }

    @Test
    fun accountChange_dropsThePendingRecovery() = runBlocking {
        session.setCurrentAccountId(11L)
        delay(700L)
        withContext(dispatcher) {
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings())
        }
        withForeground(protectedApp)
        enterRecovery()

        session.setCurrentAccountId(12L)

        assertTrue("the pending recovery must not survive an account change", awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }

    @Test
    fun signOut_dropsThePendingRecovery() = runBlocking {
        session.setCurrentAccountId(21L)
        delay(700L)
        withContext(dispatcher) {
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings())
        }
        withForeground(protectedApp)
        enterRecovery()

        session.clearSession()

        assertTrue("signing out must drop the pending recovery", awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }

    @Test
    fun runtimeStop_dropsThePendingRecovery() = runBlocking {
        session.setCurrentAccountId(31L)
        delay(700L)
        withContext(dispatcher) {
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings())
        }
        withForeground(protectedApp)
        enterRecovery()

        runtime.stop()

        assertTrue(awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }

    @Test
    fun protectionDisabled_dropsThePendingRecovery() = runBlocking {
        // Enabled with no signed-in account: protection still must not run, so the
        // runtime never owns the engine and this test keeps control of it.
        settingsStore.setProtectionEnabled(true)
        delay(500L)
        withContext(dispatcher) {
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings())
        }
        withForeground(protectedApp)
        enterRecovery()

        settingsStore.setProtectionEnabled(false)

        assertTrue(awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }
}
