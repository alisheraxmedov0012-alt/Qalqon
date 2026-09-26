package uz.faceguard.app.feature.policy

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneOffset
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
import uz.faceguard.app.data.repository.ScreenTimeUsageRepositoryImpl
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.security.PassthroughTemplateCipher
import uz.faceguard.app.testing.FakeAppUsageSource

/**
 * Phase 4 Step 3: per-app screen-time information through the real ViewModel, the real v8
 * database and the real evaluator.
 *
 * The screen's own child id is authoritative here (not the Home "screen-time child"), which is
 * what these exercise: the rows belong to the child the policy screen was opened for.
 */
@RunWith(AndroidJUnit4::class)
class ChildPolicyAppScreenTimeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FaceGuardDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var accountRepository: AccountRepositoryImpl
    private lateinit var childRepository: ChildProfileRepositoryImpl
    private lateinit var policies: ChildAppPolicyRepositoryImpl
    private lateinit var usage: ScreenTimeUsageRepository

    private var accountId = 0L
    private val minute = 60_000L
    private val day = "2026-09-25"
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    @Before
    fun setUp() = runBlocking {
        sessionManager = SessionManager(context)
        sessionManager.clearSession()
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries().build()
        accountRepository = AccountRepositoryImpl(
            db.userAccountDao(),
            sessionManager,
            PinAttemptStore(context),
        ) { System.currentTimeMillis() }
        childRepository = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher)
        policies = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        usage = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())

        accountId = (accountRepository.register("Parent", "901234567", "1234") as AuthResult.Success).account.id
    }

    @After
    fun tearDown() = runBlocking {
        // The ViewModel's Room flows outlive the test; the process-scoped in-memory database is
        // intentionally left open (closing it would crash those collectors).
        sessionManager.clearSession()
    }

    private fun viewModel(
        childId: Long,
        usageAvailable: Boolean = true,
    ): ChildPolicyViewModel = ChildPolicyViewModel(
        accountRepository = accountRepository,
        childRepository = childRepository,
        childAppPolicyRepository = policies,
        protectedAppsRepository = uz.faceguard.app.testing.FakeProtectedAppsRepository(),
        screenTimeLimitRepository = ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao()) { 1L },
        screenTimeUsageRepository = usage,
        screenTimeLimitEvaluator = ScreenTimeLimitEvaluator(
            usage,
            ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao()) { 1L },
            policies,
        ),
        appUsageSource = FakeAppUsageSource(
            access = if (usageAvailable) {
                uz.faceguard.app.domain.screentime.UsageAccessState.AVAILABLE
            } else {
                uz.faceguard.app.domain.screentime.UsageAccessState.UNAVAILABLE
            },
        ),
        zone = ZoneOffset.UTC,
        clock = { 1_790_294_460_000L },
        savedStateHandle = SavedStateHandle(mapOf(ChildPolicyArgs.CHILD_ID to childId)),
    )

    private suspend fun awaitApps(
        viewModel: ChildPolicyViewModel,
        predicate: (ScreenTimeAppsUiState) -> Boolean,
    ): ScreenTimeAppsUiState = withTimeout(10_000) { viewModel.screenTimeApps.first(predicate) }

    private suspend fun addChild(name: String, account: Long = accountId): Long =
        childRepository.addChild(account, name, RestrictionLevel.MEDIUM)

    private suspend fun use(childId: Long, packageName: String, usedMs: Long) {
        usage.addUsage(accountId, childId, day, packageName, AppCategories.categoryFor(packageName), usedMs)
    }

    private suspend fun setPolicy(childId: Long, packageName: String, mode: AppPolicyMode, minutes: Int? = null) {
        policies.upsert(
            accountId,
            childId,
            AppPolicy(packageName = packageName, mode = mode, dailyLimitMinutes = minutes),
        )
    }

    // ---- the row facts ------------------------------------------------------

    @Test
    fun aLimitedAppReportsUsedLimitAndRemaining() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        use(child, youtube, 10 * minute)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertEquals(10 * minute, row.usedMs)
        assertEquals(30, row.limitMinutes)
        assertEquals(20 * minute, row.remainingMs)
        assertFalse(row.exceeded)
    }

    @Test
    fun anAppExactlyAtItsLimitIsReached() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        use(child, youtube, 30 * minute)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertTrue(row.exceeded)
        assertEquals(0L, row.remainingMs)
    }

    @Test
    fun anAppBeyondItsLimitIsReachedWithZeroRemaining() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        use(child, youtube, 35 * minute)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertTrue(row.exceeded)
        assertEquals("never negative", 0L, row.remainingMs)
    }

    @Test
    fun limitModeWithoutMinutesIsUnlimited() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, null)
        use(child, youtube, 200 * minute)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertFalse("null minutes is unlimited, not zero", row.hasLimit)
        assertNull(row.remainingMs)
        assertFalse(row.exceeded)
    }

    @Test
    fun anAllowedAppInventsNoTimeLimit() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.ALLOW)
        use(child, youtube, 25 * minute)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertFalse("ALLOW does not imply a limit", row.hasLimit)
        assertNull(row.remainingMs)
        assertFalse(row.exceeded)
        assertEquals("usage is still reported", 25 * minute, row.usedMs)
    }

    @Test
    fun aBlockedAppIsNotPresentedAsAZeroMinuteLimit() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.BLOCK)
        use(child, youtube, 10 * minute)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertFalse("BLOCK is not a screen-time limit", row.hasLimit)
        assertNull(row.limitMinutes)
        assertFalse("and it is not 'reached' as a limit", row.exceeded)
        assertEquals(10 * minute, row.usedMs)
    }

    @Test
    fun zeroUsageForALimitedAppIsARealZero() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        val viewModel = viewModel(child)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertEquals(0L, row.usedMs)
        assertEquals(30 * minute, row.remainingMs)
        assertFalse(row.exceeded)
    }

    // ---- multiple apps ------------------------------------------------------

    @Test
    fun multipleAppsStayIsolated() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        setPolicy(child, tiktok, AppPolicyMode.LIMIT, 60)
        use(child, youtube, 10 * minute)
        use(child, tiktok, 50 * minute)
        val viewModel = viewModel(child)

        val state = awaitApps(viewModel) { it.rows.size == 2 }

        assertEquals(20 * minute, state.rowFor(youtube)!!.remainingMs)
        assertEquals(10 * minute, state.rowFor(tiktok)!!.remainingMs)
        assertEquals(youtube, (state.rowFor(youtube)!!.label as uz.faceguard.app.feature.home.ScreenTimeInfoLabel.App).packageName)
    }

    // ---- child isolation ----------------------------------------------------

    @Test
    fun anotherChildsUsageAndLimitsAreNotShown() = runBlocking {
        val ali = addChild("Ali")
        val bek = addChild("Bek")
        setPolicy(ali, youtube, AppPolicyMode.LIMIT, 30)
        use(ali, youtube, 10 * minute)
        use(bek, youtube, 25 * minute)
        val viewModel = viewModel(ali)

        val row = awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!

        assertEquals("only Ali's usage", 10 * minute, row.usedMs)
        assertEquals(20 * minute, row.remainingMs)
    }

    @Test
    fun switchingChildRebuildsTheAppRows() = runBlocking {
        val ali = addChild("Ali")
        val bek = addChild("Bek")
        setPolicy(ali, youtube, AppPolicyMode.LIMIT, 30)
        use(ali, youtube, 10 * minute)
        setPolicy(bek, youtube, AppPolicyMode.LIMIT, 60)
        use(bek, youtube, 50 * minute)
        val viewModel = viewModel(ali)
        awaitApps(viewModel) { it.rowFor(youtube)?.usedMs == 10 * minute }

        viewModel.selectChild(bek)

        val state = awaitApps(viewModel) { it.rowFor(youtube)?.usedMs == 50 * minute }
        assertEquals(10 * minute, state.rowFor(youtube)!!.remainingMs)
    }

    // ---- unavailable usage --------------------------------------------------

    @Test
    fun unavailableUsageAccessIsNotShownAsZeroUsage() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        use(child, youtube, 10 * minute)
        val viewModel = viewModel(child, usageAvailable = false)

        val state = awaitApps(viewModel) { !it.usageAvailable }

        assertFalse(state.usageAvailable)
        assertNull("no usage may be claimed", state.rowFor(youtube))
    }

    // ---- policy changes and live updates ------------------------------------

    @Test
    fun changingTheLimitUpdatesThePresentation() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 60)
        use(child, youtube, 50 * minute)
        val viewModel = viewModel(child)
        assertEquals(10 * minute, awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!.remainingMs)

        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)

        val updated = awaitApps(viewModel) { it.rowFor(youtube)?.exceeded == true }
        assertTrue(updated.rowFor(youtube)!!.exceeded)
        assertEquals(0L, updated.rowFor(youtube)!!.remainingMs)
    }

    @Test
    fun newUsageUpdatesThePresentation() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        val viewModel = viewModel(child)
        assertEquals(30 * minute, awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!.remainingMs)

        use(child, youtube, 25 * minute)

        assertEquals(5 * minute, awaitApps(viewModel) { it.rowFor(youtube)?.usedMs == 25 * minute }.rowFor(youtube)!!.remainingMs)
    }

    @Test
    fun resettingThePolicyReturnsTheAppToNoLimit() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)
        use(child, youtube, 200 * minute)
        val viewModel = viewModel(child)
        assertTrue(awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!.exceeded)

        policies.delete(accountId, child, youtube)

        val after = awaitApps(viewModel) { it.rowFor(youtube)?.hasLimit == false }
        assertFalse(after.rowFor(youtube)!!.exceeded)
        // The usage row still exists and is still reported.
        assertEquals(200 * minute, after.rowFor(youtube)!!.usedMs)
    }

    @Test
    fun switchingModeDoesNotCorruptUsage() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.ALLOW)
        use(child, youtube, 25 * minute)
        val viewModel = viewModel(child)
        assertEquals(25 * minute, awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!.usedMs)

        setPolicy(child, youtube, AppPolicyMode.BLOCK)

        val blocked = awaitApps(viewModel) { it.rowFor(youtube) != null }
        assertEquals("changing the policy must not touch usage", 25 * minute, blocked.rowFor(youtube)!!.usedMs)
        assertFalse("and BLOCK is not a time limit", blocked.rowFor(youtube)!!.hasLimit)
    }

    @Test
    fun aBlockedAppBecomesLimitedWhenTheParentConfiguresALimit() = runBlocking {
        val child = addChild("Ali")
        setPolicy(child, youtube, AppPolicyMode.BLOCK)
        val viewModel = viewModel(child)
        assertFalse(awaitApps(viewModel) { it.rowFor(youtube) != null }.rowFor(youtube)!!.hasLimit)

        setPolicy(child, youtube, AppPolicyMode.LIMIT, 30)

        val limited = awaitApps(viewModel) { it.rowFor(youtube)?.hasLimit == true }
        assertEquals(30, limited.rowFor(youtube)!!.limitMinutes)
    }

    @Test
    fun theScreensOwnChildIdRemainsAuthoritative() = runBlocking {
        val ali = addChild("Ali")
        val bek = addChild("Bek")
        // Bek has the only usage; the screen was opened for Ali, so Ali's rows must not include it.
        use(bek, youtube, 90 * minute)
        setPolicy(bek, youtube, AppPolicyMode.LIMIT, 30)
        val viewModel = viewModel(ali)

        val state = awaitApps(viewModel) { !it.loading }

        assertNull("the screen's child has no usage of Bek's", state.rowFor(youtube))
    }

    @Test
    fun anAppWithNeitherUsageNorPolicyHasNoRow() = runBlocking {
        val child = addChild("Ali")
        val viewModel = viewModel(child)

        val state = awaitApps(viewModel) { !it.loading }

        assertNull(state.rowFor(youtube))
        assertTrue(state.rows.isEmpty())
        assertNotNull("and the screen is usable", viewModel.ui.value)
    }
}
