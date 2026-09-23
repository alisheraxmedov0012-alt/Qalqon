package uz.faceguard.app.core

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.FaceGuardApp
import uz.faceguard.app.core.liveness.LivenessResult
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.core.protection.IdentitySnapshot
import uz.faceguard.app.core.protection.IdentitySource
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Group 9: runtime-level liveness behaviour on the production graph.
 *
 * Liveness is injected through the runtime's single production funnel
 * ([ProtectionRuntime.onLivenessChanged], the exact entry point the engine
 * collector uses) because the CI emulator has no controllable camera feed; the
 * camera-to-liveness mapping itself is covered by [ProtectionEngineLivenessTest].
 */
@RunWith(AndroidJUnit4::class)
class ProtectionRuntimeLivenessTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app = appContext as FaceGuardApp

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)

    private val runtime: ProtectionRuntime get() = app.protectionRuntime

    @Before
    fun setUp() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        runtime.onLivenessChanged(null)
        runtime.onIdentityChanged(null)
        delay(500L)
        assertNull("tests must start without a liveness signal", runtime.state.value.liveness)
    }

    @After
    fun tearDown() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        runtime.onLivenessChanged(null)
        runtime.onIdentityChanged(null)
        runtime.onAccessibilityDisconnected()
        delay(300L)
    }

    private fun liveResult(state: LivenessState = LivenessState.LIVE) = LivenessResult(
        state = state,
        confidence = null,
        timestamp = System.currentTimeMillis(),
        source = LivenessSource.HEURISTIC,
    )

    private fun childSnapshot(childId: Long = 7L) = IdentitySnapshot(
        context = IdentityContext(
            identity = UserIdentity.CHILD,
            childId = childId,
            childName = "Vali",
            confidence = 0.8f,
        ),
        source = IdentitySource.CAMERA,
        updatedAt = System.currentTimeMillis(),
    )

    private suspend fun awaitLiveness(expected: LivenessState?, timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runtime.state.value.liveness?.state == expected) return true
            delay(50L)
        }
        return runtime.state.value.liveness?.state == expected
    }

    @Test
    fun livenessSignal_isMirroredInTheRuntimeState() = runBlocking {
        runtime.onLivenessChanged(liveResult())

        assertTrue(awaitLiveness(LivenessState.LIVE))
        assertEquals(LivenessSource.HEURISTIC, runtime.state.value.liveness?.source)
    }

    @Test
    fun livenessIdentityAndForeground_areIndependentSignals() = runBlocking {
        val packageName = "com.example.qalqon.liveness.a"

        runtime.onLivenessChanged(liveResult(LivenessState.SPOOF))
        runtime.onIdentityChanged(childSnapshot())
        runtime.onAccessibilityForegroundApp(packageName)

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val state = runtime.state.value
            if (state.liveness?.state == LivenessState.SPOOF &&
                state.identity?.identity == UserIdentity.CHILD &&
                state.foregroundApp == packageName
            ) {
                break
            }
            delay(50L)
        }

        val state = runtime.state.value
        assertEquals("liveness signal", LivenessState.SPOOF, state.liveness?.state)
        assertEquals("identity signal", UserIdentity.CHILD, state.identity?.identity)
        assertEquals("foreground signal", packageName, state.foregroundApp)
        runtime.onAccessibilityDisconnected()
    }

    @Test
    fun accountChange_clearsAStaleLiveness() = runBlocking {
        session.setCurrentAccountId(11L)
        delay(500L)
        runtime.onLivenessChanged(liveResult())
        assertTrue(awaitLiveness(LivenessState.LIVE))

        session.setCurrentAccountId(12L)

        assertTrue("a stale liveness signal must not survive an account change", awaitLiveness(null))
        assertNull(runtime.state.value.liveness)
    }

    @Test
    fun signOut_clearsAStaleLiveness() = runBlocking {
        session.setCurrentAccountId(21L)
        delay(500L)
        runtime.onLivenessChanged(liveResult())
        assertTrue(awaitLiveness(LivenessState.LIVE))

        session.clearSession()

        assertTrue("signing out must clear the liveness signal", awaitLiveness(null))
        assertNull(runtime.state.value.liveness)
    }

    @Test
    fun clearingTheSessionBoundary_removesTheLivenessSnapshotEntirely() = runBlocking {
        runtime.onLivenessChanged(liveResult(LivenessState.UNSTABLE))
        assertTrue(awaitLiveness(LivenessState.UNSTABLE))

        runtime.onLivenessChanged(null)

        assertTrue(awaitLiveness(null))
        assertNull(runtime.state.value.liveness)
    }
}
