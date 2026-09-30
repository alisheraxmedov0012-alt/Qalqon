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
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.screentime.CollectionInterval
import uz.faceguard.app.core.screentime.ScreenTimeUsageCollectionRunner
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.data.repository.SettingsRepositoryImpl

/**
 * Phase 4 Step 1B-7: the collection loop rides the existing protection lifecycle.
 *
 * Uses the production graph end to end — the app's Hilt-injected [ProtectionRuntime] and
 * the runtime's own [ScreenTimeUsageCollectionRunner] (reached via reflection, exactly as
 * the existing runtime tests reach `engine`/`monitor`). No test-only Hilt component is
 * added, so nothing here exists only for tests.
 *
 * This is what pins the product decision: collection runs **only** while protection is
 * enabled, and there is exactly one loop with no second service or scheduler.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeCollectionLifecycleTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app = appContext as FaceGuardApp

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)
    private val settingsRepository = SettingsRepositoryImpl(settingsStore)

    private val runtime: ProtectionRuntime get() = app.protectionRuntime

    private val collection: ScreenTimeUsageCollectionRunner
        get() = ProtectionRuntime::class.java.getDeclaredField("screenTimeCollection").let {
            it.isAccessible = true
            it.get(runtime) as ScreenTimeUsageCollectionRunner
        }

    @Before
    fun setUp() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        runtime.awaitActive(false)
    }

    @After
    fun tearDown() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
        runtime.awaitActive(false)
    }

    private suspend fun ProtectionRuntime.awaitActive(expected: Boolean, timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state.value.active == expected) return
            delay(50)
        }
        assertEquals("runtime active state", expected, state.value.active)
    }

    private fun withForegroundApp(block: suspend () -> Unit) = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { block() }
    }

    @Test
    fun protectionOff_meansNoCollectionLoop() = withForegroundApp {
        assertFalse("nothing may collect while signed out and disabled", collection.running)

        settingsRepository.setProtectionEnabled(true)
        delay(200L)

        assertFalse("still signed out, so still nothing to collect", collection.running)
    }

    @Test
    fun enablingProtectionStartsExactlyOneLoop() = withForegroundApp {
        session.setCurrentAccountId(1L)

        settingsRepository.setProtectionEnabled(true)
        runtime.awaitActive(true)

        val started = collection.loopsStarted
        assertTrue("protection being on must start collection", collection.running)

        // Repeated runtime starts (what a service restart does) must not stack loops.
        runtime.start()
        runtime.start()
        delay(200L)

        assertTrue(collection.running)
        assertEquals("exactly one loop may exist", started, collection.loopsStarted)
    }

    @Test
    fun disablingProtectionStopsCollection() = withForegroundApp {
        session.setCurrentAccountId(1L)
        settingsRepository.setProtectionEnabled(true)
        runtime.awaitActive(true)
        assertTrue(collection.running)

        settingsRepository.setProtectionEnabled(false)
        runtime.awaitActive(false)

        assertFalse("collection must stop with protection", collection.running)
    }

    @Test
    fun signingOutStopsCollection() = withForegroundApp {
        session.setCurrentAccountId(1L)
        settingsRepository.setProtectionEnabled(true)
        runtime.awaitActive(true)
        assertTrue(collection.running)

        session.clearSession()
        runtime.awaitActive(false)

        assertFalse("a signed-out device must not collect", collection.running)
    }

    @Test
    fun restartingProtectionResumesCollection() = withForegroundApp {
        session.setCurrentAccountId(1L)

        settingsRepository.setProtectionEnabled(true)
        runtime.awaitActive(true)
        settingsRepository.setProtectionEnabled(false)
        runtime.awaitActive(false)
        assertFalse(collection.running)

        settingsRepository.setProtectionEnabled(true)
        runtime.awaitActive(true)

        assertTrue("collection must resume with protection", collection.running)
    }

    @Test
    fun anExplicitRuntimeStopAlsoEndsCollection() = withForegroundApp {
        session.setCurrentAccountId(1L)
        settingsRepository.setProtectionEnabled(true)
        runtime.awaitActive(true)
        assertTrue(collection.running)

        // What the foreground service does when it is destroyed.
        runtime.stop()

        assertFalse("an explicit stop must not leave a loop behind", collection.running)
    }

    @Test
    fun theLoopUsesTheTenMinuteInterval() {
        assertEquals(CollectionInterval.SCREEN_TIME_COLLECTION_INTERVAL_MS, collection.interval)
        assertEquals(600_000L, collection.interval)
    }
}
