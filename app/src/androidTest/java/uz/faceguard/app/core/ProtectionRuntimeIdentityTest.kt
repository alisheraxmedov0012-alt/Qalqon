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
import uz.faceguard.app.core.protection.IdentitySnapshot
import uz.faceguard.app.core.protection.IdentitySource
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Group 8: runtime-level identity signal behaviour on the production graph.
 *
 * The identity signal is injected through the runtime's single production funnel
 * ([ProtectionRuntime.onIdentityChanged], the exact entry point the engine
 * collector uses) because the CI emulator has no controllable camera feed; the
 * camera-to-identity mapping itself is covered by [ProtectionEngineIdentityTest]
 * with real recognition frames.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionRuntimeIdentityTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app = appContext as FaceGuardApp

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)

    private val runtime: ProtectionRuntime get() = app.protectionRuntime

    @Before
    fun setUp() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        runtime.onIdentityChanged(null)
        // Let the runtime's account collector settle so the tests below do not
        // race a pending session emission.
        delay(500L)
        assertNull("tests must start without an identity", runtime.state.value.identity)
    }

    @After
    fun tearDown() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        runtime.onIdentityChanged(null)
        runtime.onAccessibilityDisconnected()
        delay(300L)
    }

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

    private suspend fun awaitIdentity(
        expected: UserIdentity?,
        timeoutMs: Long = 10_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runtime.state.value.identity?.identity == expected) return true
            delay(50L)
        }
        return runtime.state.value.identity?.identity == expected
    }

    @Test
    fun identitySignal_isMirroredInTheRuntimeState() = runBlocking {
        runtime.onIdentityChanged(childSnapshot())

        assertTrue(awaitIdentity(UserIdentity.CHILD))
        assertEquals(7L, runtime.state.value.identity?.childId)
        assertEquals("Vali", runtime.state.value.identity?.childName)
        assertEquals(IdentitySource.CAMERA, runtime.state.value.identity?.source)
    }

    @Test
    fun identityAndForegroundApp_areTwoIndependentSignals() = runBlocking {
        val packageName = "com.example.qalqon.identity.a"

        runtime.onIdentityChanged(childSnapshot())
        runtime.onAccessibilityForegroundApp(packageName)

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val state = runtime.state.value
            if (state.identity?.identity == UserIdentity.CHILD && state.foregroundApp == packageName) break
            delay(50L)
        }

        val state = runtime.state.value
        assertEquals("identity signal", UserIdentity.CHILD, state.identity?.identity)
        assertEquals("foreground signal", packageName, state.foregroundApp)
        runtime.onAccessibilityDisconnected()
    }

    @Test
    fun accountChange_clearsAStaleIdentity() = runBlocking {
        session.setCurrentAccountId(11L)
        delay(500L)
        runtime.onIdentityChanged(childSnapshot())
        assertTrue(awaitIdentity(UserIdentity.CHILD))

        session.setCurrentAccountId(12L)

        assertTrue("a stale child identity must not survive an account change", awaitIdentity(null))
        assertNull(runtime.state.value.identity)
    }

    @Test
    fun signOut_clearsAStaleIdentity() = runBlocking {
        session.setCurrentAccountId(21L)
        delay(500L)
        runtime.onIdentityChanged(childSnapshot())
        assertTrue(awaitIdentity(UserIdentity.CHILD))

        session.clearSession()

        assertTrue("signing out must clear the identity signal", awaitIdentity(null))
        assertNull(runtime.state.value.identity)
    }

    @Test
    fun clearingTheSessionBoundary_removesTheIdentitySnapshotEntirely() = runBlocking {
        runtime.onIdentityChanged(childSnapshot(childId = 9L))
        assertTrue(awaitIdentity(UserIdentity.CHILD))
        assertEquals(9L, runtime.state.value.identity?.childId)

        runtime.onIdentityChanged(null)

        assertTrue(awaitIdentity(null))
        assertNull(runtime.state.value.identity)
    }
}
