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
 * The runtime's own engine instance is used (reached via reflection, exactly the
 * one the runtime owns), driven through its real `evaluate()`. The runtime's
 * private context/policy fields are set to the same test data, so the runtime's
 * own `syncContext()` re-asserts that context instead of wiping it; protection
 * stays disabled at the settings level (no account), so the runtime never
 * activates the session and the test keeps deterministic control of the engine.
 *
 * Each test asserts the binding invariant from the spec: after an account change /
 * sign-out / disable / explicit stop, the previous cycle's pending recovery must
 * never emit a `PROTECTION_RELEASED` (or modify the new session), even after its
 * original delay has elapsed. The cancellation mechanic itself is verified
 * directly in [ProtectionEngineRecoveryLifecycleTest].
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
    private val recoveryMs = 3_000L

    private val released = CopyOnWriteArrayList<ActivityEventType>()
    private var previousOnEvent: ((ActivityEventType, String?) -> Unit)? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val engine: ProtectionEngine
        get() = field("engine") as ProtectionEngine

    private val monitor: ForegroundAppMonitor
        get() = field("monitor") as ForegroundAppMonitor

    private fun field(name: String): Any? =
        ProtectionRuntime::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(runtime)
        }

    private fun setField(name: String, value: Any?) {
        ProtectionRuntime::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(runtime, value)
        }
    }

    private fun withForeground(packageName: String?) {
        val f = ForegroundAppMonitor::class.java.getDeclaredField("_current")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (f.get(monitor) as MutableStateFlow<String?>).value = packageName
    }

    /**
     * Test plumbing: resets the engine's per-session timing trackers so the test's
     * logical clock starts fresh. The app-scoped singleton may carry a wall-clock
     * `lastStableAt` from earlier activity, which would otherwise make the engine's
     * 1.2s debounce swallow every evaluation driven with the test clock.
     */
    private fun resetEngineClock() {
        fun set(name: String, value: Any) {
            ProtectionEngine::class.java.getDeclaredField(name).let {
                it.isAccessible = true
                it.set(engine, value)
            }
        }
        set("lastStableAt", 0L)
        set("emptyFaceStreak", 0)
        ProtectionEngine::class.java.getDeclaredField("pending").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (it.get(engine) as MutableList<Any?>).clear()
        }
        ProtectionEngine::class.java.getDeclaredField("recentConfidences").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (it.get(engine) as ArrayDeque<Any?>).clear()
        }
    }

    private fun injectScope() {
        ProtectionEngine::class.java.getDeclaredField("scope").let {
            it.isAccessible = true
            it.set(engine, scope)
        }
    }

    private fun policySettings() = PolicySettings(
        enabled = true,
        activationDelayMs = 0L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        recoveryDelayMs = recoveryMs,
    )

    /** Re-asserts the runtime's own context/policy to the test data. */
    private suspend fun installContext() {
        setField("parent", parent)
        setField("children", listOf(child))
        setField("protectedPackages", setOf(protectedApp))
        setField("policy", policySettings())
        withContext(dispatcher) {
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings())
        }
        injectScope()
    }

    @Before
    fun setUp() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        released.clear()
        delay(700L)
        previousOnEvent = engine.onEvent
        engine.onEvent = { type, _ -> released += type }
        withForeground(protectedApp)
        installContext()
    }

    @After
    fun tearDown() = runBlocking {
        withContext(dispatcher) { engine.scanScheduler?.detach() }
        withContext(dispatcher) { engine.stop() }
        previousOnEvent?.let { engine.onEvent = it }
        scope.cancel()
        executor.shutdown()
        settingsStore.clearAll()
        session.clearSession()
        withForeground(null)
        delay(200L)
    }

    private fun frame(features: FloatArray?): FrameEvent = FrameEvent(
        image = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0),
        faceCount = if (features != null) 1 else 0,
        features = features,
    )

    private fun ProtectionEngine.driveBlocked() {
        var now = 100_000L
        repeat(5) { evaluate(protectedApp, frame(childVec), now); now += 50 }
    }

    private fun ProtectionEngine.driveNoFace() {
        var now = 400_000L
        repeat(3) { evaluate(protectedApp, frame(null), now); now += 50 }
    }

    private suspend fun awaitState(expected: ProtectionState, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.state.value == expected) return true
            delay(10)
        }
        return engine.state.value == expected
    }

    /**
     * Drives the real engine into BLOCKED then RECOVERING.
     *
     * The context/settings are re-asserted on the engine inside the same
     * single-threaded block as the evaluation, so the runtime's asynchronous
     * sync cannot slip a different context between setup and the decision.
     */
    private suspend fun enterRecovery() {
        withContext(dispatcher) {
            engine.updateContext(parent, listOf(child), setOf(protectedApp))
            engine.updateSettings(ProtectionSettings(), policySettings())
            // The runtime's engine gates evaluation on its scan scheduler being in
            // an open window; arm it (the runtime does this on activation) so the
            // engine actually decides. detach() in tearDown resets it afterwards.
            engine.scanScheduler?.attach(scope)
            engine.scanScheduler?.onProtectedAppOpened()
            resetEngineClock()
            engine.driveBlocked()
            assertEquals("the runtime's engine must block the child", ProtectionState.HARD_BLOCKED, engine.state.value)
            engine.driveNoFace()
            assertEquals("losing the face must start the recovery window", ProtectionState.RECOVERING, engine.state.value)
        }
    }

    private suspend fun assertNeverReleased() {
        delay(recoveryMs + 900L)
        assertEquals(
            "a dropped recovery cycle must not emit PROTECTION_RELEASED",
            0,
            released.count { it == ActivityEventType.PROTECTION_RELEASED },
        )
        assertNotEquals(ProtectionState.RECOVERING, engine.state.value)
    }

    @Test
    fun accountChange_dropsThePendingRecovery() = runBlocking {
        session.setCurrentAccountId(11L)
        delay(700L)
        enterRecovery()

        session.setCurrentAccountId(12L)

        assertTrue("the pending recovery must not survive an account change", awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }

    @Test
    fun signOut_dropsThePendingRecovery() = runBlocking {
        session.setCurrentAccountId(21L)
        delay(700L)
        enterRecovery()

        session.clearSession()

        assertTrue("signing out must drop the pending recovery", awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }

    @Test
    fun runtimeStop_dropsThePendingRecovery() = runBlocking {
        session.setCurrentAccountId(31L)
        delay(700L)
        enterRecovery()

        runtime.stop()

        assertTrue(awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }

    @Test
    fun protectionDisabled_dropsThePendingRecovery() = runBlocking {
        // Enabling with no signed-in account keeps the runtime from activating, so
        // the settings path (`syncActive` -> cancelRecovery) is exercised directly.
        settingsStore.setProtectionEnabled(true)
        delay(500L)
        enterRecovery()

        settingsStore.setProtectionEnabled(false)

        assertTrue(awaitState(ProtectionState.UNPROTECTED))
        assertNeverReleased()
    }
}
