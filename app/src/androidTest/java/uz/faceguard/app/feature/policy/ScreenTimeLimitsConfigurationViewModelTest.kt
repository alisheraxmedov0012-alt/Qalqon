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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.prefs.PinAttemptStore
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.data.repository.ChildAppPolicyRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ScreenTimeLimitRepositoryImpl
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.security.PassthroughTemplateCipher
import uz.faceguard.app.testing.FakeProtectedAppsRepository

/**
 * Phase 4 Step 1D: configuring screen-time limits through the real ViewModel and the real
 * v8 database.
 *
 * These assert what a parent experiences: a saved value is persisted and shown, removing it
 * returns the row to "no limit", an invalid entry changes nothing, and switching children or
 * accounts shows that child's / account's own configuration.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeLimitsConfigurationViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FaceGuardDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var accountRepository: AccountRepositoryImpl
    private lateinit var childRepository: ChildProfileRepositoryImpl
    private lateinit var screenTimeLimitRepository: ScreenTimeLimitRepository

    private var accountId = 0L

    @Before
    fun setUp() = runBlocking {
        sessionManager = SessionManager(context)
        sessionManager.clearSession()
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepository = AccountRepositoryImpl(
            db.userAccountDao(),
            sessionManager,
            PinAttemptStore(context),
        ) { System.currentTimeMillis() }
        childRepository = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher)
        screenTimeLimitRepository = ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao()) { 1L }

        val result = accountRepository.register("Parent", "901234567", "1234")
        accountId = (result as AuthResult.Success).account.id
    }

    @After
    fun tearDown() = runBlocking {
        // The ViewModel's Room flows outlive the test, so the process-scoped in-memory
        // database is intentionally left open (closing it would crash those collectors).
        sessionManager.clearSession()
    }

    private fun viewModel(childId: Long): ChildPolicyViewModel = ChildPolicyViewModel(
        accountRepository = accountRepository,
        childRepository = childRepository,
        childAppPolicyRepository = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao()),
        protectedAppsRepository = FakeProtectedAppsRepository(),
        screenTimeLimitRepository = screenTimeLimitRepository,
        savedStateHandle = SavedStateHandle(mapOf(ChildPolicyArgs.CHILD_ID to childId)),
    )

    private suspend fun awaitLimits(
        viewModel: ChildPolicyViewModel,
        predicate: (ScreenTimeLimitsUiState) -> Boolean,
    ): ScreenTimeLimitsUiState =
        withTimeout(5_000) { viewModel.screenTimeLimits.first(predicate) }

    private suspend fun addChild(name: String): Long =
        childRepository.addChild(accountId, name, RestrictionLevel.MEDIUM)

    // ---- saving and showing -------------------------------------------------

    @Test
    fun aSavedTotalLimitIsPersistedAndShown() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }

        viewModel.setTotalLimit(120)

        val state = awaitLimits(viewModel) { it.totalMinutes == 120 }
        assertEquals(120, state.totalMinutes)
        assertEquals("persisted", 120, screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun aSavedCategoryLimitIsPersistedAndShown() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }

        viewModel.setCategoryLimit(AppCategory.GAMES, 45)

        val state = awaitLimits(viewModel) { it.categoryLimit(AppCategory.GAMES) == 45 }
        assertEquals(45, state.categoryLimit(AppCategory.GAMES))
        assertEquals(45, screenTimeLimitRepository.limit(accountId, child, LimitScope.CATEGORY, AppCategory.GAMES)?.limitMinutes)
    }

    @Test
    fun changingALimitReplacesTheStoredValue() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }
        viewModel.setTotalLimit(120)
        awaitLimits(viewModel) { it.totalMinutes == 120 }

        viewModel.setTotalLimit(60)

        awaitLimits(viewModel) { it.totalMinutes == 60 }
        assertEquals(60, screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun aSavedLimitSurvivesReopeningTheScreen() = runBlocking {
        val child = addChild("Ali")
        val first = viewModel(child)
        awaitLimits(first) { !it.loading }
        first.setTotalLimit(90)
        awaitLimits(first) { it.totalMinutes == 90 }

        // A fresh ViewModel stands in for leaving and reopening the screen.
        val reopened = viewModel(child)

        assertEquals(90, awaitLimits(reopened) { it.totalMinutes == 90 }.totalMinutes)
    }

    @Test
    fun everyCategoryIsOfferedSoEachCanBeConfigured() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)

        val state = awaitLimits(viewModel) { !it.loading }

        assertEquals(AppCategory.entries.toList(), state.categories)
    }

    // ---- no limit vs zero ---------------------------------------------------

    @Test
    fun aChildStartsWithNoLimitsAtAll() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)

        val state = awaitLimits(viewModel) { !it.loading }

        assertNull("no limit configured means null, not 0", state.totalMinutes)
        assertTrue(state.categoryMinutes.values.none { it != null })
    }

    @Test
    fun aZeroLimitIsStoredAsARealValueNotAsNoLimit() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }

        viewModel.setTotalLimit(0)

        val state = awaitLimits(viewModel) { it.totalMinutes == 0 }
        assertEquals("0 is a configured limit", 0, state.totalMinutes)
        assertEquals(0, screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL)?.limitMinutes)
    }

    // ---- removal ------------------------------------------------------------

    @Test
    fun removingTheTotalLimitReturnsToNoLimit() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }
        viewModel.setTotalLimit(120)
        awaitLimits(viewModel) { it.totalMinutes == 120 }

        viewModel.removeTotalLimit()

        assertNull(awaitLimits(viewModel) { it.totalMinutes == null }.totalMinutes)
        assertNull("and the row is gone", screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL))
    }

    @Test
    fun removingOneCategoryLeavesTheOthersAndTheTotal() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }
        viewModel.setTotalLimit(120)
        viewModel.setCategoryLimit(AppCategory.GAMES, 30)
        viewModel.setCategoryLimit(AppCategory.EDUCATION, 60)
        awaitLimits(viewModel) { it.categoryLimit(AppCategory.EDUCATION) == 60 && it.totalMinutes == 120 }

        viewModel.removeCategoryLimit(AppCategory.GAMES)

        val state = awaitLimits(viewModel) { it.categoryLimit(AppCategory.GAMES) == null }
        assertEquals("EDUCATION survives", 60, state.categoryLimit(AppCategory.EDUCATION))
        assertEquals("the total survives", 120, state.totalMinutes)
    }

    // ---- invalid input ------------------------------------------------------

    @Test
    fun anInvalidLimitIsRejectedAndChangesNothing() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }
        viewModel.setTotalLimit(60)
        awaitLimits(viewModel) { it.totalMinutes == 60 }

        viewModel.setTotalLimit(-5)
        viewModel.setTotalLimit(5000)

        val state = awaitLimits(viewModel) { it.errorMessageRes != null }
        assertEquals("the valid value is untouched", 60, state.totalMinutes)
        assertEquals(60, screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL)?.limitMinutes)
    }

    // ---- child switching ----------------------------------------------------

    @Test
    fun switchingChildLoadsThatChildsOwnLimits() = runBlocking {
        val ali = addChild("Ali")
        val bek = addChild("Bek")
        screenTimeLimitRepository.upsert(accountId, ali, LimitScope.TOTAL, limitMinutes = 120)
        screenTimeLimitRepository.upsert(accountId, bek, LimitScope.TOTAL, limitMinutes = 30)
        val viewModel = viewModel(ali)
        awaitLimits(viewModel) { it.totalMinutes == 120 }

        viewModel.selectChild(bek)

        assertEquals(30, awaitLimits(viewModel) { it.totalMinutes == 30 }.totalMinutes)

        viewModel.selectChild(ali)
        assertEquals(120, awaitLimits(viewModel) { it.totalMinutes == 120 }.totalMinutes)
    }

    @Test
    fun aChildWithoutLimitsShowsNoLimitsAfterSwitching() = runBlocking {
        val ali = addChild("Ali")
        val bek = addChild("Bek")
        screenTimeLimitRepository.upsert(accountId, ali, LimitScope.TOTAL, limitMinutes = 120)
        val viewModel = viewModel(ali)
        awaitLimits(viewModel) { it.totalMinutes == 120 }

        viewModel.selectChild(bek)

        assertNull(awaitLimits(viewModel) { it.totalMinutes == null }.totalMinutes)
    }

    // ---- account switching --------------------------------------------------

    @Test
    fun switchingAccountLoadsThatAccountsOwnLimits() = runBlocking {
        val firstAccountChild = addChild("Ali")
        screenTimeLimitRepository.upsert(accountId, firstAccountChild, LimitScope.TOTAL, limitMinutes = 120)

        // A second account with its own child and its own limit for the same child id space.
        val second = accountRepository.register("Other", "902345678", "5678") as AuthResult.Success
        val secondAccountId = second.account.id
        val secondChild = childRepository.addChild(secondAccountId, "Bek", RestrictionLevel.MEDIUM)
        screenTimeLimitRepository.upsert(secondAccountId, secondChild, LimitScope.TOTAL, limitMinutes = 30)

        // The screen resolves the signed-in account when it is opened, so a new ViewModel
        // after the switch is the correct way to observe the second account's values.
        assertEquals(120, awaitLimits(viewModel(firstAccountChild)) { !it.loading }.totalMinutes)

        val switched = viewModel(secondChild)
        assertEquals(30, awaitLimits(switched) { it.totalMinutes == 30 }.totalMinutes)
    }

    @Test
    fun oneAccountsLimitsAreNeverShownForAnothers() = runBlocking {
        val firstAccountChild = addChild("Ali")
        screenTimeLimitRepository.upsert(accountId, firstAccountChild, LimitScope.TOTAL, limitMinutes = 120)
        val second = accountRepository.register("Other", "902345678", "5678") as AuthResult.Success
        val secondChild = childRepository.addChild(second.account.id, "Bek", RestrictionLevel.MEDIUM)

        // The second account's child has no limit, and must not inherit the first's.
        val state = awaitLimits(viewModel(secondChild)) { !it.loading }

        assertNull(state.totalMinutes)
    }

    // ---- the saved value is the one the evaluator reads ---------------------

    @Test
    fun theSavedLimitIsTheRowTheEvaluatorWouldRead() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }

        viewModel.setTotalLimit(75)
        awaitLimits(viewModel) { it.totalMinutes == 75 }

        val stored = screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL)
        assertNotNull(stored)
        assertEquals(75, stored!!.limitMinutes)
        assertEquals(LimitScope.TOTAL, stored.scope)
        assertEquals("and it carries the child it was configured for", child, stored.childId)
    }

    @Test
    fun removingTheLimitMakesTheEvaluatorSeeUnlimited() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }
        viewModel.setTotalLimit(30)
        awaitLimits(viewModel) { it.totalMinutes == 30 }

        viewModel.removeTotalLimit()
        awaitLimits(viewModel) { it.totalMinutes == null }

        assertNull(screenTimeLimitRepository.limit(accountId, child, LimitScope.TOTAL))
        assertFalse(
            "no configured limit means the scope is unlimited",
            screenTimeLimitRepository.limits(accountId, child).any { it.scope == LimitScope.TOTAL },
        )
    }

    @Test
    fun theExistingAppPolicyBehaviourIsUnchangedByThisStep() = runBlocking {
        // The per-app limit path still works exactly as before, alongside the new section.
        val child = addChild("Ali")
        val viewModel = viewModel(child)
        awaitLimits(viewModel) { !it.loading }

        viewModel.setPolicy("com.example.youtube", AppPolicyMode.LIMIT, 30)

        // Give the asynchronous write a chance to land, then assert through the real store.
        val policies = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        withTimeout(5_000) {
            while (policies.policyFor(accountId, child, "com.example.youtube")?.dailyLimitMinutes != 30) {
                kotlinx.coroutines.delay(20)
            }
        }
        assertEquals(30, policies.policyFor(accountId, child, "com.example.youtube")?.dailyLimitMinutes)
    }
}
