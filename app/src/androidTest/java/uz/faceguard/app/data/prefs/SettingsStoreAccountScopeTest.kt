package uz.faceguard.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

/**
 * Real instrumented tests for the account-scoped [SettingsStore].
 *
 * Uses a fresh on-disk Preferences DataStore plus the real [SessionManager], so
 * the production read/write paths (including DataStore itself) are exercised —
 * no fakes or mocks.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreAccountScopeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        sessionManager = SessionManager(context)
        runBlocking { sessionManager.clearSession() }
        file = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = SettingsStore(dataStore, sessionManager)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        runBlocking { sessionManager.clearSession() }
    }

    private suspend fun signIn(accountId: Long) = sessionManager.setCurrentAccountId(accountId)

    private suspend fun currentSettings() = store.settings.first()

    // --------------------------------------------------------------- defaults

    @Test
    fun defaults_whenSignedOut() = runBlocking {
        val s = currentSettings()
        assertEquals(false, s.protectionEnabled)
        assertEquals(ScanMode.BALANCED, s.scanMode)
        assertEquals(30_000L, s.recoveryDelayMs)
        assertEquals(BlockPolicy.SOFT_BLOCK, s.unknownUserPolicy)
        assertEquals(BlockPolicy.ALLOW, s.noFacePolicy)
        assertEquals(true, s.lowBatteryBehaviorEnabled)
    }

    @Test
    fun defaults_forAFreshAccount() = runBlocking {
        signIn(1L)
        val s = currentSettings()
        assertEquals(false, s.protectionEnabled)
        assertEquals(BlockPolicy.SOFT_BLOCK, s.unknownUserPolicy)
        assertEquals(BlockPolicy.ALLOW, s.noFacePolicy)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun writesArePersistedAndReadBack() = runBlocking {
        signIn(1L)
        store.setProtectionEnabled(true)
        store.setScanMode(ScanMode.STRICT)
        store.setRecoveryDelayMs(12_000L)
        store.setUnknownUserPolicy(BlockPolicy.HARD_BLOCK)
        store.setNoFacePolicy(BlockPolicy.SOFT_BLOCK)
        store.setLowBatteryBehaviorEnabled(false)

        val s = currentSettings()
        assertEquals(true, s.protectionEnabled)
        assertEquals(ScanMode.STRICT, s.scanMode)
        assertEquals(12_000L, s.recoveryDelayMs)
        assertEquals(BlockPolicy.HARD_BLOCK, s.unknownUserPolicy)
        assertEquals(BlockPolicy.SOFT_BLOCK, s.noFacePolicy)
        assertEquals(false, s.lowBatteryBehaviorEnabled)
    }

    @Test
    fun update_replacesThePreviousValue() = runBlocking {
        signIn(1L)
        store.setUnknownUserPolicy(BlockPolicy.HARD_BLOCK)
        store.setUnknownUserPolicy(BlockPolicy.ALLOW)
        assertEquals(BlockPolicy.ALLOW, currentSettings().unknownUserPolicy)
    }

    @Test
    fun signedOut_writesAreIgnoredAndDoNotLeak() = runBlocking {
        signIn(1L)
        store.setProtectionEnabled(true)

        sessionManager.clearSession()
        assertEquals(false, currentSettings().protectionEnabled)

        store.setProtectionEnabled(true) // ignored: no signed-in account
        signIn(1L)
        assertEquals(true, currentSettings().protectionEnabled)
    }

    // --------------------------------------------------------- corrupt values

    @Test
    fun corruptEnum_fallsBackToSafeDefault() = runBlocking {
        signIn(1L)
        store.setUnknownUserPolicy(BlockPolicy.HARD_BLOCK)
        store.setNoFacePolicy(BlockPolicy.HARD_BLOCK)

        dataStore.edit { it[stringPreferencesKey("acc_1_unknown_user_policy")] = "NOT_A_POLICY" }
        dataStore.edit { it[stringPreferencesKey("acc_1_no_face_policy")] = "" }

        val s = currentSettings()
        assertEquals(BlockPolicy.SOFT_BLOCK, s.unknownUserPolicy)
        assertEquals(BlockPolicy.ALLOW, s.noFacePolicy)
    }

    @Test
    fun corruptNumeric_fallsBackToDefault() = runBlocking {
        signIn(1L)
        store.setRecoveryDelayMs(12_000L)

        dataStore.edit { it[longPreferencesKey("acc_1_recovery_delay_ms")] = -5L }
        assertEquals(30_000L, currentSettings().recoveryDelayMs)

        dataStore.edit { it[longPreferencesKey("acc_1_recovery_delay_ms")] = Long.MAX_VALUE }
        assertEquals(30_000L, currentSettings().recoveryDelayMs)
    }

    @Test
    fun invalidRecoveryDelayIsRejectedBeforePersisting() = runBlocking {
        signIn(1L)
        store.setRecoveryDelayMs(-1L)
        assertEquals(30_000L, currentSettings().recoveryDelayMs)
    }

    // ------------------------------------------------------- account scoping

    @Test
    fun accountsDoNotShareSettings() = runBlocking {
        signIn(1L)
        store.setProtectionEnabled(true)
        store.setUnknownUserPolicy(BlockPolicy.HARD_BLOCK)

        signIn(2L)
        val second = currentSettings()
        assertEquals("account B must not inherit A's protection", false, second.protectionEnabled)
        assertEquals(BlockPolicy.SOFT_BLOCK, second.unknownUserPolicy)

        store.setProtectionEnabled(false)
        store.setUnknownUserPolicy(BlockPolicy.ALLOW)

        signIn(1L)
        val first = currentSettings()
        assertEquals("account A keeps its own values", true, first.protectionEnabled)
        assertEquals(BlockPolicy.HARD_BLOCK, first.unknownUserPolicy)
    }

    // ----------------------------------------------------------------- reset

    @Test
    fun clearAll_returnsEveryAccountToDefaults() = runBlocking {
        signIn(1L)
        store.setProtectionEnabled(true)
        store.setUnknownUserPolicy(BlockPolicy.HARD_BLOCK)
        signIn(2L)
        store.setProtectionEnabled(true)

        store.clearAll()

        signIn(1L)
        assertEquals(false, currentSettings().protectionEnabled)
        assertEquals(BlockPolicy.SOFT_BLOCK, currentSettings().unknownUserPolicy)
        signIn(2L)
        assertEquals(false, currentSettings().protectionEnabled)
    }
}
