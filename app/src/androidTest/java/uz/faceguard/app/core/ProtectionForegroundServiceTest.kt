package uz.faceguard.app.core

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.MainActivity
import uz.faceguard.app.core.protection.ProtectionForegroundService
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.protection.ProtectionServiceLauncher
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ResetRepository
import uz.faceguard.app.domain.repository.SettingsRepository

/**
 * Group 6: real Android lifecycle tests for the background protection
 * foundation.
 *
 * Everything is production code: the real Hilt graph, the real
 * [ProtectionRuntime] singleton, the real [ProtectionServiceLauncher] and the
 * real [ProtectionForegroundService]. Interactions happen with the app in the
 * foreground (the app is launched, exactly like a parent flipping the toggle in
 * the UI), because Android 12+ rejects foreground-service starts from the
 * background — the production launcher also degrades gracefully if the OS
 * refuses, which is documented rather than hidden.
 *
 * Camera behaviour is deliberately NOT asserted here: background camera use is
 * restricted by the platform and is out of scope (see the Group 6 report).
 */
@RunWith(AndroidJUnit4::class)
class ProtectionForegroundServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appContext: Context = context.applicationContext

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GraphEntryPoint {
        fun protectionRuntime(): ProtectionRuntime
        fun protectionServiceLauncher(): ProtectionServiceLauncher
        fun accountRepository(): AccountRepository
        fun settingsRepository(): SettingsRepository
        fun resetRepository(): ResetRepository
    }

    private val graph: GraphEntryPoint
        get() = EntryPointAccessors.fromApplication(appContext, GraphEntryPoint::class.java)

    private val running get() = ProtectionForegroundService.running.value

    @Before
    fun setUp() = runBlocking {
        graph.protectionServiceLauncher().stop()
        awaitRunning(false)
        graph.resetRepository().resetAll() // first-run state: signed out, protection off
        awaitRunning(false)
    }

    @After
    fun tearDown() = runBlocking {
        graph.protectionServiceLauncher().stop()
        awaitRunning(false)
        graph.resetRepository().resetAll()
    }

    private suspend fun awaitRunning(expected: Boolean, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (running == expected) return
            delay(50)
        }
        assertEquals("foreground service running state", expected, running)
    }

    private suspend fun ProtectionRuntime.awaitActive(expected: Boolean, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state.value.active == expected) return
            delay(50)
        }
        assertEquals("runtime active state", expected, state.value.active)
    }

    @Test
    fun launcherStart_startsTheService_andStopStopsIt() = runBlocking {
        assertFalse("service must start stopped", running)

        ActivityScenario.launch(MainActivity::class.java).use {
            graph.protectionServiceLauncher().start()
            awaitRunning(true)

            graph.protectionServiceLauncher().stop()
            awaitRunning(false)
        }
    }

    @Test
    fun duplicateStart_keepsOneServiceAndNeverCrashes() = runBlocking {
        val runtime = graph.protectionRuntime()
        assertSame(
            "Hilt must expose exactly one ProtectionRuntime",
            runtime,
            graph.protectionRuntime(),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            graph.protectionServiceLauncher().start()
            awaitRunning(true)

            graph.protectionServiceLauncher().start() // duplicate start
            runtime.start() // runtime start must be idempotent
            runtime.start()

            delay(200L)
            assertTrue("one service must stay running", running)
            assertSame(runtime, graph.protectionRuntime())
            assertFalse("protection is off, so no active session", runtime.state.value.active)
        }
    }

    @Test
    fun repeatedStop_isSafe() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            graph.protectionServiceLauncher().start()
            awaitRunning(true)

            graph.protectionServiceLauncher().stop()
            awaitRunning(false)
            graph.protectionServiceLauncher().stop()
            graph.protectionServiceLauncher().stop()

            delay(200L)
            assertFalse(running)
        }
    }

    @Test
    fun protectionOff_leavesActiveProtectionStopped() = runBlocking {
        val runtime = graph.protectionRuntime()

        ActivityScenario.launch(MainActivity::class.java).use {
            graph.settingsRepository().setProtectionEnabled(false)
            graph.protectionServiceLauncher().start()
            awaitRunning(true)
            delay(200L)

            assertFalse("protection is off", runtime.state.value.enabled)
            assertFalse("no active protection session", runtime.state.value.active)
        }
    }

    @Test
    fun enablingProtection_activatesTheRuntimeAndStartsTheService() = runBlocking {
        val runtime = graph.protectionRuntime()
        val account = (graph.accountRepository().register("Parent", "901234567", "1234") as AuthResult.Success)

        ActivityScenario.launch(MainActivity::class.java).use {
            graph.settingsRepository().setProtectionEnabled(true)
            runtime.awaitActive(true)
            awaitRunning(true)

            graph.settingsRepository().setProtectionEnabled(false)
            runtime.awaitActive(false)
            awaitRunning(false)
        }

        assertTrue(account.account.id > 0L)
    }

    @Test
    fun signingOut_deactivatesProtectionAndStopsTheService() = runBlocking {
        val runtime = graph.protectionRuntime()
        graph.accountRepository().register("Parent", "901234567", "1234")

        ActivityScenario.launch(MainActivity::class.java).use {
            graph.settingsRepository().setProtectionEnabled(true)
            runtime.awaitActive(true)
            awaitRunning(true)

            graph.accountRepository().logout()
            runtime.awaitActive(false)
            awaitRunning(false)
        }
    }
}
