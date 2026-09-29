package uz.faceguard.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.protection.ProtectionBootReceiver
import uz.faceguard.app.core.protection.ProtectionBootRestorer
import uz.faceguard.app.core.protection.ProtectionServiceLauncher
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.data.repository.SettingsRepositoryImpl

/**
 * Phase 7.4: the boot restoration wiring, over the **production** DataStore/session
 * (no mocks for persistence) plus the manifest declaration.
 *
 * What it can verify here: the receiver is declared for BOOT_COMPLETED with the
 * boot permission, and the restorer restores protection from the persisted intent
 * (ON + signed in → start requested; OFF → nothing). What it cannot verify without
 * a device: that Android actually delivers BOOT_COMPLETED and that the service
 * start is accepted at boot — reported as NOT VERIFIED.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionBootReceiverTest {

    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)
    private val settingsRepository = SettingsRepositoryImpl(settingsStore)

    /** Records start requests without touching a real service. */
    private class RecordingLauncher : ProtectionServiceLauncher {
        var startCalls = 0
        var stopCalls = 0
        override fun start() { startCalls++ }
        override fun stop() { stopCalls++ }
    }

    @Before
    fun setUp() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
    }

    @After
    fun tearDown() = runBlocking {
        settingsStore.clearAll()
        session.clearSession()
    }

    @Test
    fun theBootReceiverIsDeclaredForBootCompleted() {
        val matches = appContext.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED),
            PackageManager.GET_RESOLVED_FILTER,
        )

        val ours = matches.firstOrNull {
            it.activityInfo?.name == ProtectionBootReceiver::class.java.name
        }
        assertTrue("QALQON must declare a BOOT_COMPLETED receiver", ours != null)
        assertTrue("the receiver must be exported so the system can deliver the broadcast", ours!!.activityInfo.exported)
    }

    @Test
    fun theBootPermissionIsRequested() {
        val requested = appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        assertTrue(
            "receiving BOOT_COMPLETED requires RECEIVE_BOOT_COMPLETED",
            "android.permission.RECEIVE_BOOT_COMPLETED" in requested,
        )
    }

    @Test
    fun protectionOnWithASession_restoresStartupFromThePersistedIntent() = runBlocking {
        session.setCurrentAccountId(7L)
        settingsRepository.setProtectionEnabled(true)
        val launcher = RecordingLauncher()

        val restored = ProtectionBootRestorer(settingsRepository, sessionAccount(7L), launcher).restore()

        assertTrue(restored)
        assertEquals(1, launcher.startCalls)
        assertEquals(0, launcher.stopCalls)
    }

    @Test
    fun protectionOff_neverAutoEnablesOnBoot() = runBlocking {
        session.setCurrentAccountId(7L)
        settingsRepository.setProtectionEnabled(false)
        val launcher = RecordingLauncher()

        val restored = ProtectionBootRestorer(settingsRepository, sessionAccount(7L), launcher).restore()

        assertFalse(restored)
        assertEquals(0, launcher.startCalls)
    }

    /** Minimal account source backed by the real persisted session. */
    private fun sessionAccount(id: Long?) = object : uz.faceguard.app.domain.repository.AccountRepository {
        override val currentAccountId = kotlinx.coroutines.flow.flowOf(id)
        override suspend fun register(
            fullName: String,
            phoneNumber: String,
            pin: String,
        ) = throw UnsupportedOperationException()
        override suspend fun login(phoneNumber: String, pin: String) = throw UnsupportedOperationException()
        override suspend fun getCurrentAccount() = null
        override suspend fun logout() = Unit
        override suspend fun verifyPin(pin: String) = false
    }
}
