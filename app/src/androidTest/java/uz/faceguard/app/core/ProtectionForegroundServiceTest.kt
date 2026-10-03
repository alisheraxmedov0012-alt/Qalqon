package uz.faceguard.app.core

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.FaceGuardApp
import uz.faceguard.app.MainActivity
import uz.faceguard.app.core.protection.AndroidProtectionServiceLauncher
import uz.faceguard.app.core.protection.ProtectionForegroundService
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.data.repository.SettingsRepositoryImpl

/**
 * Group 6: real Android lifecycle tests for the background protection
 * foundation.
 *
 * Uses the production graph end to end: the app's Hilt-injected
 * [ProtectionRuntime] singleton, the real [AndroidProtectionServiceLauncher] and
 * the real [ProtectionForegroundService], over the production DataStore/session
 * (the same instances the runtime observes). No test-only Hilt component or
 * entry point is added, so nothing here exists only for tests.
 *
 * Service interactions happen with the app launched (foreground), exactly like a
 * parent flipping the toggle in the UI, because Android 12+ rejects
 * foreground-service starts from the background. Camera behaviour is not
 * asserted: background camera use is restricted by the platform and is out of
 * scope (see the Group 6 report).
 */
@RunWith(AndroidJUnit4::class)
class ProtectionForegroundServiceTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app = appContext as FaceGuardApp

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)
    private val settingsRepository = SettingsRepositoryImpl(settingsStore)
    private val launcher = AndroidProtectionServiceLauncher(appContext)

    private val runtime: ProtectionRuntime get() = app.protectionRuntime
    private val running get() = ProtectionForegroundService.running.value

    @Before
    fun setUp() = runBlocking {
        launcher.stop()
        settingsStore.clearAll()
        session.clearSession()
        awaitRunning(false)
        runtime.awaitActive(false)
    }

    @After
    fun tearDown() = runBlocking {
        launcher.stop()
        awaitRunning(false)
        settingsStore.clearAll()
        session.clearSession()
        runtime.awaitActive(false)
    }

    // The CI emulator is software-rendered (swiftshader) and regularly stalls the
    // main looper for tens of seconds when the app renders (observed 30s+ and, in
    // one suite, 126s). Both the runtime's observers and the service's `running`
    // flag are published from the main thread, so the waits must be generous enough
    // to outlast such a stall instead of asserting against it.
    private suspend fun awaitRunning(expected: Boolean, timeoutMs: Long = 180_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (running == expected) return
            delay(50)
        }
        assertEquals("foreground service running state", expected, running)
    }

    private suspend fun ProtectionRuntime.awaitActive(expected: Boolean, timeoutMs: Long = 180_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state.value.active == expected) return
            delay(50)
        }
        assertEquals("runtime active state", expected, state.value.active)
    }

    /**
     * Launches the real app activity so the process is in the foreground before
     * any foreground-service start (Android 12+ restriction), then runs [block].
     *
     * Blocks until the main thread is idle after the launch: the activity's first
     * composition/render is what stalls the main looper on the CI emulator, and the
     * runtime/service state asserted by [block] is published from that same looper.
     */
    private fun withForegroundApp(block: suspend () -> Unit) = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            block()
        }
    }

    @Test
    fun launcherStart_startsTheService_andStopStopsIt() = withForegroundApp {
        assertFalse("service must start stopped", running)

        launcher.start()
        awaitRunning(true)

        launcher.stop()
        awaitRunning(false)
    }

    @Test
    fun duplicateStart_keepsOneServiceAndNeverCrashes() = withForegroundApp {
        launcher.start()
        awaitRunning(true)

        launcher.start() // duplicate service start
        runtime.start() // runtime start must be idempotent
        runtime.start()

        delay(200L)
        assertTrue("one service must stay running", running)
        assertFalse("protection is off, so no active session", runtime.state.value.active)
    }

    @Test
    fun repeatedStop_isSafe() = withForegroundApp {
        launcher.start()
        awaitRunning(true)

        launcher.stop()
        awaitRunning(false)
        launcher.stop()
        launcher.stop()

        delay(200L)
        assertFalse(running)
    }

    @Test
    fun protectionOff_leavesActiveProtectionStopped() = withForegroundApp {
        settingsRepository.setProtectionEnabled(false)
        launcher.start()
        awaitRunning(true)
        delay(200L)

        assertFalse("protection is off", runtime.state.value.enabled)
        assertFalse("no active protection session", runtime.state.value.active)
    }

    @Test
    fun enablingProtection_activatesTheRuntimeAndStartsTheService() = withForegroundApp {
        session.setCurrentAccountId(1L)
        settingsRepository.setProtectionEnabled(true)

        // Foreground the app first: the runtime publishes `state.active` from
        // `Dispatchers.Main.immediate` collectors, so on the software-rendered CI
        // emulator those collectors only advance once the activity launch has
        // pumped/idled the main looper (see the sibling ScreenTimeCollectionLifecycleTest,
        // which asserts the same activation from inside withForegroundApp). Asserting
        // activation *before* the launch raced an unpumped main looper and timed out.
        // `withForegroundApp` has already launched MainActivity and waited for idle here,
        // which is also the real parent flow (protection is toggled while the app is visible).
        runtime.awaitActive(true)
        awaitRunning(true)

        settingsRepository.setProtectionEnabled(false)
        runtime.awaitActive(false)
        awaitRunning(false)
    }

    @Test
    fun signingOut_deactivatesProtectionAndStopsTheService() = withForegroundApp {
        session.setCurrentAccountId(1L)
        settingsRepository.setProtectionEnabled(true)

        // Foreground first for the same reason as above: activation is published from the
        // runtime's main-thread collectors, which need the main looper to be pumped.
        runtime.awaitActive(true)
        awaitRunning(true)

        session.clearSession()
        runtime.awaitActive(false)
        awaitRunning(false)
    }
}
