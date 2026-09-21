package uz.faceguard.app.feature.policy

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.data.repository.ChildAppPolicyRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.testing.FakeProtectedAppsRepository

/**
 * Group 4 integration tests for [ChildPolicyViewModel].
 *
 * Real Room database + real account/child/child-app-policy repositories, so the
 * production write→persist→observe path is exercised end to end. Only the
 * installed-app catalog is a deterministic in-memory double:
 * `ProtectedAppsRepositoryImpl.refreshFromDevice()` queries the live
 * PackageManager and prunes rows that the device does not report, which cannot
 * be made deterministic in a test.
 */
@RunWith(AndroidJUnit4::class)
class ChildPolicyViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FaceGuardDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var accountRepository: AccountRepositoryImpl
    private lateinit var childRepository: ChildProfileRepositoryImpl
    private lateinit var appPolicyRepository: ChildAppPolicyRepositoryImpl
    private lateinit var protectedApps: FakeProtectedAppsRepository
    private var accountId = 0L

    private val youtube = "com.example.youtube"
    private val chrome = "com.example.chrome"

    @Before
    fun setUp() = runBlocking {
        sessionManager = SessionManager(context)
        sessionManager.clearSession()
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepository = AccountRepositoryImpl(db.userAccountDao(), sessionManager)
        childRepository = ChildProfileRepositoryImpl(db.childProfileDao())
        appPolicyRepository = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        protectedApps = FakeProtectedAppsRepository()

        val result = accountRepository.register("Parent", "901234567", "1234")
        accountId = (result as AuthResult.Success).account.id
    }

    @After
    fun tearDown() = runBlocking {
        db.close()
        sessionManager.clearSession()
    }

    private fun viewModel(childId: Long): ChildPolicyViewModel = ChildPolicyViewModel(
        accountRepository = accountRepository,
        childRepository = childRepository,
        childAppPolicyRepository = appPolicyRepository,
        protectedAppsRepository = protectedApps,
        savedStateHandle = SavedStateHandle(mapOf(ChildPolicyArgs.CHILD_ID to childId)),
    )

    private suspend fun awaitUi(
        viewModel: ChildPolicyViewModel,
        predicate: (ChildPolicyUiState) -> Boolean,
    ): ChildPolicyUiState = withTimeout(5_000) { viewModel.ui.first(predicate) }

    @Test
    fun initialState_loadsChildrenAndSelectsTheNavChild() = runBlocking {
        val childA = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        childRepository.addChild(accountId, "Child B", RestrictionLevel.LOW)
        protectedApps.seed(
            listOf(ProtectedApp(youtube, "YouTube"), ProtectedApp(chrome, "Chrome")),
        )

        val ui = awaitUi(viewModel(childA)) {
            it.state is UiState.Success &&
                it.selectedChildId == childA &&
                it.apps.any { app -> app.packageName == youtube }
        }

        assertEquals(2, ui.children.size)
        assertEquals("Child A", ui.selectedChild?.childName)
        assertTrue(ui.apps.any { it.packageName == youtube })
        assertTrue("no explicit policy yet", ui.policies.isEmpty())
    }

    @Test
    fun selectingAnotherChild_switchesToThatChildsPolicies() = runBlocking {
        val childA = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        val childB = childRepository.addChild(accountId, "Child B", RestrictionLevel.LOW)
        appPolicyRepository.upsert(
            accountId,
            childB,
            AppPolicy(youtube, AppPolicyMode.BLOCK, childId = childB),
        )

        val viewModel = viewModel(childA)
        awaitUi(viewModel) { it.selectedChildId == childA }

        viewModel.selectChild(childB)
        val ui = awaitUi(viewModel) { it.selectedChildId == childB && it.policies.isNotEmpty() }

        assertEquals(AppPolicyMode.BLOCK, ui.policyFor(youtube)?.mode)
    }

    @Test
    fun allowSave_persistsAnExplicitAllow() = runBlocking {
        val child = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        val viewModel = viewModel(child)
        awaitUi(viewModel) { it.selectedChildId == child }

        viewModel.setPolicy(youtube, AppPolicyMode.ALLOW)
        val ui = awaitUi(viewModel) { it.policyFor(youtube)?.mode == AppPolicyMode.ALLOW }

        assertEquals(ProtectionAction.ALLOW, ui.policyFor(youtube)?.action)
        assertNull(ui.policyFor(youtube)?.dailyLimitMinutes)
        assertEquals(AppPolicyMode.ALLOW, appPolicyRepository.policyFor(accountId, child, youtube)?.mode)
    }

    @Test
    fun limitSave_persistsTheDailyLimitAndSoftBlocksWhenExceeded() = runBlocking {
        val child = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        val viewModel = viewModel(child)
        awaitUi(viewModel) { it.selectedChildId == child }

        viewModel.setPolicy(youtube, AppPolicyMode.LIMIT, dailyLimitMinutes = 60)
        val ui = awaitUi(viewModel) { it.policyFor(youtube)?.mode == AppPolicyMode.LIMIT }

        assertEquals(60, ui.policyFor(youtube)?.dailyLimitMinutes ?: -1)
        assertEquals(ProtectionAction.SOFT_BLOCK, ui.policyFor(youtube)?.action)

        val stored = appPolicyRepository.policyFor(accountId, child, youtube)
        assertNotNull(stored)
        assertEquals(60, stored!!.dailyLimitMinutes ?: -1)
    }

    @Test
    fun blockSave_persistsAHardBlock() = runBlocking {
        val child = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        val viewModel = viewModel(child)
        awaitUi(viewModel) { it.selectedChildId == child }

        viewModel.setPolicy(youtube, AppPolicyMode.BLOCK)
        val ui = awaitUi(viewModel) { it.policyFor(youtube)?.mode == AppPolicyMode.BLOCK }

        assertEquals(ProtectionAction.HARD_BLOCK, ui.policyFor(youtube)?.action)
        assertEquals(
            AppPolicyMode.BLOCK,
            appPolicyRepository.policyFor(accountId, child, youtube)?.mode,
        )
    }

    @Test
    fun resetPolicy_removesTheOverrideAndRestoresTheDefault() = runBlocking {
        val child = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        appPolicyRepository.upsert(
            accountId,
            child,
            AppPolicy(youtube, AppPolicyMode.BLOCK, childId = child),
        )
        val viewModel = viewModel(child)
        awaitUi(viewModel) { it.policyFor(youtube)?.mode == AppPolicyMode.BLOCK }

        viewModel.resetPolicy(youtube)
        val ui = awaitUi(viewModel) { it.policyFor(youtube) == null }

        assertNull(appPolicyRepository.policyFor(accountId, child, youtube))
        assertEquals(PolicyDisplay.DEFAULT_ALLOW, ui.displayFor(youtube))
    }

    @Test
    fun childPolicies_doNotLeakBetweenChildren() = runBlocking {
        val childA = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        val childB = childRepository.addChild(accountId, "Child B", RestrictionLevel.LOW)
        val viewModel = viewModel(childA)
        awaitUi(viewModel) { it.selectedChildId == childA }

        viewModel.setPolicy(youtube, AppPolicyMode.BLOCK)
        awaitUi(viewModel) { it.policyFor(youtube)?.mode == AppPolicyMode.BLOCK }

        viewModel.selectChild(childB)
        val uiB = awaitUi(viewModel) { it.selectedChildId == childB && it.policies.isEmpty() }

        assertNull(uiB.policyFor(youtube))
        assertEquals(PolicyDisplay.DEFAULT_ALLOW, uiB.displayFor(youtube))
        assertEquals(
            AppPolicyMode.BLOCK,
            appPolicyRepository.policyFor(accountId, childA, youtube)?.mode,
        )
    }

    @Test
    fun explicitPolicy_overridesTheInheritedGlobalProtection() = runBlocking {
        val child = childRepository.addChild(accountId, "Child A", RestrictionLevel.MEDIUM)
        protectedApps.seed(listOf(ProtectedApp(youtube, "YouTube", isProtected = true), ProtectedApp(chrome, "Chrome")))

        val viewModel = viewModel(child)
        val inherited = awaitUi(viewModel) { it.selectedChildId == child && it.globalProtected.isNotEmpty() }

        assertEquals(PolicyDisplay.DEFAULT_BLOCK, inherited.displayFor(youtube))
        assertEquals(PolicyDisplay.DEFAULT_ALLOW, inherited.displayFor(chrome))

        viewModel.setPolicy(youtube, AppPolicyMode.ALLOW)
        val overridden = awaitUi(viewModel) { it.policyFor(youtube)?.mode == AppPolicyMode.ALLOW }

        assertEquals(PolicyDisplay.EXPLICIT_ALLOW, overridden.displayFor(youtube))
    }

    @Test
    fun signedOut_showsAnErrorState() = runBlocking {
        accountRepository.logout()
        val ui = awaitUi(viewModel(1L)) { it.state is UiState.Error }

        assertTrue(ui.state is UiState.Error)
    }
}
