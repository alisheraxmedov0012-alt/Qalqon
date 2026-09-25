package uz.faceguard.app.feature.home

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.FaceGuardApp
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.repository.ActivityLogRepositoryImpl
import uz.faceguard.app.data.repository.ChildAppPolicyRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentRequestRepositoryImpl
import uz.faceguard.app.data.repository.ProtectedAppsRepositoryImpl
import uz.faceguard.app.data.repository.ScreenTimeActiveChildRepositoryImpl
import uz.faceguard.app.data.repository.SettingsRepositoryImpl
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.security.PassthroughTemplateCipher

/**
 * Phase 4 Step 1B-8: the screen-time target section's real behaviour.
 *
 * The ViewModel, the child list, the settings store and the persisted target are the
 * shipped implementations (only the account session and the runtime's device side are
 * supplied), so these assert what a parent actually experiences: choosing a child is
 * persisted, restored, isolated per account, ownership-checked, and never defaulted.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeTargetViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as FaceGuardApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var sessionManager: SessionManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var db: FaceGuardDatabase
    private lateinit var children: ChildProfileRepositoryImpl
    private lateinit var activeChild: ScreenTimeActiveChildRepositoryImpl
    private val accounts = FakeAccountSession()

    @Before
    fun setUp() {
        sessionManager = SessionManager(context)
        runBlocking { sessionManager.clearSession() }
        file = File(context.cacheDir, "target-vm-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        settingsStore = SettingsStore(dataStore, sessionManager)

        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        children = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher)
        activeChild = ScreenTimeActiveChildRepositoryImpl(settingsStore)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        file.delete()
        runBlocking { sessionManager.clearSession() }
    }

    /** The signed-in account, controllable like a real session switch. */
    private class FakeAccountSession : AccountRepository {
        private val state = MutableStateFlow<Long?>(1L)
        override val currentAccountId: Flow<Long?> = state

        fun signIn(id: Long) {
            state.value = id
        }

        override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
            AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)

        override suspend fun login(phoneNumber: String, pin: String): AuthResult =
            AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)

        override suspend fun getCurrentAccount(): UserAccount? = state.value?.let {
            UserAccount(id = it, fullName = "Parent", phoneNumber = "998901234567", pinHash = "h")
        }

        override suspend fun logout() = Unit
        override suspend fun verifyPin(pin: String): Boolean = false
    }

    private fun viewModel() = HomeViewModel(
        accountRepository = accounts,
        parentProfileRepository = ParentProfileRepositoryImpl(db.parentProfileDao(), PassthroughTemplateCipher),
        childRepository = children,
        policyRepository = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao()),
        protectedAppsRepository = ProtectedAppsRepositoryImpl(context, db.protectedAppDao()),
        activityLogRepository = ActivityLogRepositoryImpl(db.activityEventDao()),
        settingsRepository = SettingsRepositoryImpl(settingsStore),
        requestRepository = ParentRequestRepositoryImpl(db.parentRequestDao(), children),
        screenTimeActiveChildRepository = activeChild,
        runtime = app.protectionRuntime,
    )

    private suspend fun addChild(accountId: Long, name: String): Long =
        children.addChild(accountId, name, RestrictionLevel.MEDIUM)

    /** Polls the ViewModel's published state; no fixed sleeps decide an assertion. */
    private suspend fun awaitTarget(
        viewModel: HomeViewModel,
        timeoutMs: Long = 10_000L,
        predicate: (ScreenTimeTargetUiState) -> Boolean,
    ): ScreenTimeTargetUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.screenTimeTarget.value
            if (predicate(state)) return state
            delay(20)
        }
        error("target state never satisfied the condition; last=${viewModel.screenTimeTarget.value}")
    }

    // ---- the states a parent sees -------------------------------------------

    @Test
    fun noChildIsSelectedUntilTheParentChoosesOne() = runBlocking {
        addChild(1L, "Ali")
        addChild(1L, "Vali")
        val viewModel = viewModel()

        val state = awaitTarget(viewModel) { it.status == ScreenTimeTargetStatus.READY && it.children.size == 2 }

        assertTrue("the parent must be asked to choose", state.needsSelection)
        assertNull("and nothing may be pre-selected", state.activeChildId)
        assertNull("not the first child, not any child", state.activeChild)
    }

    @Test
    fun choosingAChildIsPersistedAndReflected() = runBlocking {
        val ali = addChild(1L, "Ali")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.isNotEmpty() }

        viewModel.selectScreenTimeChild(ali)

        val state = awaitTarget(viewModel) { it.activeChildId == ali }
        assertEquals("Ali", state.activeChild?.childName)
        assertFalse(state.needsSelection)
        assertEquals("the selection is persisted", ali, activeChild.activeChildId(1L))
    }

    @Test
    fun changingTheSelectionStoresTheNewChild() = runBlocking {
        val ali = addChild(1L, "Ali")
        val vali = addChild(1L, "Vali")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.size == 2 }
        viewModel.selectScreenTimeChild(ali)
        awaitTarget(viewModel) { it.activeChildId == ali }

        viewModel.selectScreenTimeChild(vali)

        awaitTarget(viewModel) { it.activeChildId == vali }
        assertEquals("the stored target follows the choice", vali, activeChild.activeChildId(1L))
    }

    @Test
    fun theSelectionIsRestoredByAFreshViewModel() = runBlocking {
        val vali = addChild(1L, "Vali")
        val first = viewModel()
        awaitTarget(first) { it.children.isNotEmpty() }
        first.selectScreenTimeChild(vali)
        awaitTarget(first) { it.activeChildId == vali }

        // A new ViewModel stands in for leaving and reopening the screen, and for a restart.
        val reopened = viewModel()

        val state = awaitTarget(reopened) { it.activeChildId != null }
        assertEquals(vali, state.activeChild?.id)
        assertEquals("Vali", state.activeChild?.childName)
    }

    @Test
    fun noChildrenShowsTheEmptyState() = runBlocking {
        val viewModel = viewModel()

        val state = awaitTarget(viewModel) { it.status == ScreenTimeTargetStatus.READY }

        assertTrue("there is nothing to choose yet", state.noChildren)
        assertNull(state.activeChildId)
    }

    @Test
    fun aDeletedTargetReadsAsMissingAndIsNeverSwappedForAnotherChild() = runBlocking {
        val ali = addChild(1L, "Ali")
        addChild(1L, "Vali")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.size == 2 }
        viewModel.selectScreenTimeChild(ali)
        awaitTarget(viewModel) { it.activeChildId == ali }

        // Delete the target through the real deletion path.
        children.deleteChild(1L, ali)
        activeChild.clearActiveChildId(1L)

        val state = awaitTarget(viewModel) { it.children.size == 1 }
        assertNull("no other child may inherit the target", state.activeChildId)
        assertFalse("Vali must not become the target", state.activeChild?.childName == "Vali")
        assertTrue("and the parent is asked to choose again", state.needsSelection)
    }

    @Test
    fun choosingAChildFromAnotherAccountIsRejected() = runBlocking {
        addChild(1L, "Ali")
        val foreign = addChild(2L, "Other")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.isNotEmpty() }

        viewModel.selectScreenTimeChild(foreign)

        // Give a rejected write every chance to land before asserting it did not.
        delay(300)
        assertNull("a child of another account must never be stored", activeChild.activeChildId(1L))
        assertNull(viewModel.screenTimeTarget.value.activeChildId)
    }

    // ---- account isolation --------------------------------------------------

    @Test
    fun eachAccountKeepsItsOwnTargetAndNeverInheritsOne() = runBlocking {
        val ali = addChild(1L, "Ali")
        addChild(2L, "Bek")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.isNotEmpty() }
        viewModel.selectScreenTimeChild(ali)
        awaitTarget(viewModel) { it.activeChildId == ali }

        // Switch to account 2, which has its own child and no stored target.
        accounts.signIn(2L)

        val switched = awaitTarget(viewModel) { it.children.any { child -> child.childName == "Bek" } }
        assertNull("account 2 must not inherit account 1's target", switched.activeChildId)
        assertTrue("and it must be asked to choose", switched.needsSelection)
        assertEquals("account 1's stored value is untouched", ali, activeChild.activeChildId(1L))

        // Back to account 1: its own choice is still there.
        accounts.signIn(1L)
        val restored = awaitTarget(viewModel) { it.children.any { child -> child.childName == "Ali" } }
        assertEquals(ali, restored.activeChildId)
    }

    @Test
    fun aTargetFromThePreviousAccountIsNeverShownAsTheNewOnes() = runBlocking {
        val ali = addChild(1L, "Ali")
        addChild(2L, "Bek")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.isNotEmpty() }
        viewModel.selectScreenTimeChild(ali)
        awaitTarget(viewModel) { it.activeChildId == ali }

        accounts.signIn(2L)

        val switched = awaitTarget(viewModel) { it.children.any { child -> child.childName == "Bek" } }
        assertNull(switched.activeChildId)
        assertNull("no leaked selection", switched.activeChild)
    }

    // ---- the collector reads the same value ---------------------------------

    @Test
    fun thePersistedTargetIsTheOneTheCollectorWouldRead() = runBlocking {
        val vali = addChild(1L, "Vali")
        val viewModel = viewModel()
        awaitTarget(viewModel) { it.children.isNotEmpty() }

        viewModel.selectScreenTimeChild(vali)
        awaitTarget(viewModel) { it.activeChildId == vali }

        assertEquals(vali, activeChild.activeChildId(1L))
        assertEquals(vali, activeChild.observeActiveChildId(1L).first())
    }
}
