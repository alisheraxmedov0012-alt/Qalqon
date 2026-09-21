package uz.faceguard.app.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentProfileRepositoryImpl
import uz.faceguard.app.data.repository.ProtectedAppsRepositoryImpl
import uz.faceguard.app.data.repository.ResetRepositoryImpl
import uz.faceguard.app.data.repository.SettingsRepositoryImpl
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

/**
 * Group 4 integration tests for [SettingsViewModel].
 *
 * Real DataStore (fresh file per test), real [SettingsStore], real repositories
 * and real Room — no mocks. Verifies every policy setting the parent manages is
 * written through the repository and reflected back in the UI state, and that
 * the state is null (loading) until a persisted value arrives.
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var sessionManager: SessionManager
    private lateinit var store: SettingsStore
    private lateinit var db: FaceGuardDatabase
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var accountRepository: AccountRepositoryImpl
    private lateinit var parentProfileRepository: ParentProfileRepositoryImpl
    private lateinit var childRepository: ChildProfileRepositoryImpl
    private lateinit var protectedAppsRepository: ProtectedAppsRepositoryImpl
    private lateinit var resetRepository: ResetRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        sessionManager = SessionManager(context)
        sessionManager.clearSession()
        file = File(context.cacheDir, "settings-vm-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = SettingsStore(dataStore, sessionManager)
        settingsRepository = SettingsRepositoryImpl(store)

        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepository = AccountRepositoryImpl(db.userAccountDao(), sessionManager)
        parentProfileRepository = ParentProfileRepositoryImpl(db.parentProfileDao())
        childRepository = ChildProfileRepositoryImpl(db.childProfileDao())
        protectedAppsRepository = ProtectedAppsRepositoryImpl(context, db.protectedAppDao())
        resetRepository = ResetRepositoryImpl(db, store, sessionManager)

        val result = accountRepository.register("Parent", "901234567", "1234")
        @Suppress("UNUSED_VARIABLE")
        val account = (result as AuthResult.Success).account
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { sessionManager.clearSession() }
        db.close()
        file.delete()
    }

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = settingsRepository,
        accountRepository = accountRepository,
        protectedAppsRepository = protectedAppsRepository,
        parentProfileRepository = parentProfileRepository,
        childRepository = childRepository,
        resetRepository = resetRepository,
    )

    private suspend fun awaitSettings(
        viewModel: SettingsViewModel,
        predicate: (AppSettings) -> Boolean = { true },
    ): AppSettings = withTimeout(5_000) { viewModel.settings.first { it != null && predicate(it) }!! }

    @Test
    fun beforeAnyCollector_settingsAreLoading_notDefaults() {
        val viewModel = viewModel()

        // WhileSubscribed + null initial value: nothing has been read yet, so the
        // UI must not show product defaults as if they were persisted settings.
        assertNull(viewModel.settings.value)
    }

    @Test
    fun initialContent_matchesThePersistedDefaults() = runBlocking {
        val settings = awaitSettings(viewModel())

        assertEquals(false, settings.protectionEnabled)
        assertEquals(ScanMode.BALANCED, settings.scanMode)
        assertEquals(BlockPolicy.SOFT_BLOCK, settings.unknownUserPolicy)
        assertEquals(BlockPolicy.ALLOW, settings.noFacePolicy)
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, settings.recoveryDelayMs)
        assertEquals(true, settings.lowBatteryBehaviorEnabled)
    }

    @Test
    fun protectionToggle_persistsAndIsReflectedInUi() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        viewModel.setProtectionEnabled(true)

        assertEquals(true, awaitSettings(viewModel) { it.protectionEnabled }.protectionEnabled)
        assertEquals(true, store.settings.first().protectionEnabled)
    }

    @Test
    fun unknownUserPolicy_persistsAndIsReflectedInUi() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        viewModel.setUnknownPolicy(BlockPolicy.HARD_BLOCK)

        assertEquals(
            BlockPolicy.HARD_BLOCK,
            awaitSettings(viewModel) { it.unknownUserPolicy == BlockPolicy.HARD_BLOCK }.unknownUserPolicy,
        )
        assertEquals(BlockPolicy.HARD_BLOCK, store.settings.first().unknownUserPolicy)
    }

    @Test
    fun noFacePolicy_persistsAndIsReflectedInUi() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        viewModel.setNoFacePolicy(BlockPolicy.SOFT_BLOCK)

        assertEquals(
            BlockPolicy.SOFT_BLOCK,
            awaitSettings(viewModel) { it.noFacePolicy == BlockPolicy.SOFT_BLOCK }.noFacePolicy,
        )
        assertEquals(BlockPolicy.SOFT_BLOCK, store.settings.first().noFacePolicy)
    }

    @Test
    fun recoveryDelay_persistsInMilliseconds() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        viewModel.setRecoveryDelay(45)

        assertEquals(45_000L, awaitSettings(viewModel) { it.recoveryDelayMs == 45_000L }.recoveryDelayMs)
        assertEquals(45_000L, store.settings.first().recoveryDelayMs)
    }

    @Test
    fun scanMode_persistsAndIsReflectedInUi() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        viewModel.setScanMode(ScanMode.STRICT)

        assertEquals(ScanMode.STRICT, awaitSettings(viewModel) { it.scanMode == ScanMode.STRICT }.scanMode)
        assertEquals(ScanMode.STRICT, store.settings.first().scanMode)
    }

    @Test
    fun lowBatteryBehavior_persistsAndIsReflectedInUi() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        viewModel.setLowBatteryBehavior(false)

        assertEquals(false, awaitSettings(viewModel) { !it.lowBatteryBehaviorEnabled }.lowBatteryBehaviorEnabled)
        assertEquals(false, store.settings.first().lowBatteryBehaviorEnabled)
    }

    @Test
    fun externalRepositoryUpdate_isReflectedInTheUi() = runBlocking {
        val viewModel = viewModel()
        awaitSettings(viewModel)

        store.setScanMode(ScanMode.BATTERY_SAVER)

        assertEquals(
            ScanMode.BATTERY_SAVER,
            awaitSettings(viewModel) { it.scanMode == ScanMode.BATTERY_SAVER }.scanMode,
        )
    }

    @Test
    fun persistedSettings_surviveViewModelRecreation() = runBlocking {
        val first = viewModel()
        awaitSettings(first)
        first.setProtectionEnabled(true)
        first.setUnknownPolicy(BlockPolicy.ALLOW)
        awaitSettings(first) { it.protectionEnabled && it.unknownUserPolicy == BlockPolicy.ALLOW }

        // A fresh ViewModel over the same store reads the persisted values back.
        val second = viewModel()
        val reloaded = awaitSettings(second) { it.protectionEnabled }

        assertEquals(true, reloaded.protectionEnabled)
        assertEquals(BlockPolicy.ALLOW, reloaded.unknownUserPolicy)
    }
}
