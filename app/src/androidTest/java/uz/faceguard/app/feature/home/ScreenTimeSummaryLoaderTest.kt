package uz.faceguard.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.ZoneOffset
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
import uz.faceguard.app.data.prefs.PinAttemptStore
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.data.repository.ChildAppPolicyRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ScreenTimeLimitRepositoryImpl
import uz.faceguard.app.data.repository.ScreenTimeUsageRepositoryImpl
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageDateKey
import uz.faceguard.app.security.PassthroughTemplateCipher

/**
 * Phase 4 Step 2: the presentation loader over the real v8 database and the real evaluator.
 *
 * The loader is production code; the account is a controllable fake (a real session would make
 * the account-switching cases fight the instrumented suite's shared SessionManager) and usage
 * access is an explicit input, because it is a user-granted app-op. Everything else — the
 * usage table, the limit table, the policy store and the evaluator — is the shipped
 * implementation.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeSummaryLoaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as FaceGuardApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var db: FaceGuardDatabase
    private lateinit var usage: ScreenTimeUsageRepository
    private lateinit var limits: ScreenTimeLimitRepository
    private lateinit var policies: ChildAppPolicyRepositoryImpl
    private lateinit var children: ChildProfileRepositoryImpl
    private lateinit var evaluator: ScreenTimeLimitEvaluator
    private lateinit var loader: ScreenTimeSummaryLoader

    private val accounts = FakeAccounts(1L)
    private val access = MutableStateFlow(true)
    private val target = MutableStateFlow(ScreenTimeTargetUiState())

    private val minute = 60_000L
    private val day = "2026-09-25"
    private val youtube = "com.google.android.youtube"
    private val duolingo = "com.duolingo"
    private val game = "com.example.mygame"

    /** The day the loader computes from its clock; must match [day]. */
    private val now = UsageDateKey.let {
        java.time.Instant.parse("2026-09-25T10:00:00Z").toEpochMilli()
    }

    private class FakeAccounts(initial: Long?) : uz.faceguard.app.domain.repository.AccountRepository {
        private val state = MutableStateFlow(initial)
        override val currentAccountId: Flow<Long?> = state
        fun switchTo(id: Long?) {
            state.value = id
        }
        override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
            AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        override suspend fun login(phoneNumber: String, pin: String): AuthResult =
            AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        override suspend fun getCurrentAccount(): uz.faceguard.app.domain.model.UserAccount? = null
        override suspend fun logout() = Unit
        override suspend fun verifyPin(pin: String): Boolean = false
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries().build()
        usage = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        limits = ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao()) { 1L }
        policies = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        children = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher)
        evaluator = ScreenTimeLimitEvaluator(usage, limits, policies)
        loader = ScreenTimeSummaryLoader(
            usageRepository = usage,
            limitRepository = limits,
            evaluator = evaluator,
            zone = ZoneOffset.UTC,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun state() = loader.observe(accounts.currentAccountId, target, access, scope)

    private suspend fun await(
        flow: Flow<ScreenTimeSummaryUiState>,
        timeoutMs: Long = 10_000L,
        predicate: (ScreenTimeSummaryUiState) -> Boolean,
    ): ScreenTimeSummaryUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = flow.first()
            if (predicate(value)) return value
            delay(20)
        }
        error("summary never satisfied the condition; last=${flow.first()}")
    }

    private suspend fun addChild(accountId: Long, name: String): Long =
        children.addChild(accountId, name, RestrictionLevel.MEDIUM)

    private suspend fun targetChild(childId: Long, childName: String = "Ali") {
        target.value = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = emptyList(),
            activeChildId = childId,
        ).copy(children = children.observeChildren(accounts.currentAccountId.first() ?: 1L).first())
    }

    private suspend fun use(accountId: Long, childId: Long, packageName: String, usedMs: Long) {
        usage.addUsage(accountId, childId, day, packageName, AppCategories.categoryFor(packageName), usedMs)
    }

    // ---- total --------------------------------------------------------------

    @Test
    fun totalUsageAndLimitProduceTheExpectedState() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child, "Ali")
        use(1L, child, youtube, 70 * minute)
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 120)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.READY }

        val total = state.total!!
        assertEquals(70 * minute, total.usedMs)
        assertEquals(120, total.limitMinutes)
        assertEquals(50 * minute, total.remainingMs)
        assertFalse(total.exceeded)
        assertEquals("Ali", state.childName)
        assertEquals(day, state.dateKey)
    }

    @Test
    fun totalUsageWithoutALimitIsUnlimited() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, youtube, 70 * minute)

        val total = await(state()) { it.status == ScreenTimeSummaryStatus.READY }.total!!

        assertFalse(total.hasLimit)
        assertNull("no limit, so no remaining either", total.remainingMs)
        assertFalse("and nothing is claimed to be exceeded", total.exceeded)
        assertEquals(70 * minute, total.usedMs)
    }

    @Test
    fun usageExactlyAtTheLimitIsExceeded() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, youtube, 30 * minute)
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 30)

        val total = await(state()) { it.status == ScreenTimeSummaryStatus.READY }.total!!

        assertTrue(total.exceeded)
        assertEquals(0L, total.remainingMs)
    }

    @Test
    fun usageAboveTheLimitIsExceededAndRemainingIsClamped() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, youtube, 45 * minute)
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 30)

        val total = await(state()) { it.status == ScreenTimeSummaryStatus.READY }.total!!

        assertTrue(total.exceeded)
        assertEquals("never negative", 0L, total.remainingMs)
    }

    @Test
    fun genuineZeroUsageReadsAsZero() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.READY }

        assertEquals("zero usage is a real value", 0L, state.total!!.usedMs)
        assertFalse(state.total!!.exceeded)
    }

    @Test
    fun aZeroLimitIsExceededNotUnlimited() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 0)

        val total = await(state()) { it.status == ScreenTimeSummaryStatus.READY }.total!!

        assertTrue(total.hasLimit)
        assertEquals(0, total.limitMinutes)
        assertTrue(total.exceeded)
        assertEquals(0L, total.remainingMs)
    }

    // ---- categories ---------------------------------------------------------

    @Test
    fun categoryUsageAndLimitAreReported() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, game, 40 * minute)
        limits.upsert(1L, child, LimitScope.CATEGORY, AppCategory.GAMES, 45)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.READY }
        val games = state.usedCategories.single {
            (it.label as ScreenTimeInfoLabel.Category).category == AppCategory.GAMES
        }

        assertEquals(40 * minute, games.usedMs)
        assertEquals(45, games.limitMinutes)
        assertEquals(5 * minute, games.remainingMs)
        assertFalse(games.exceeded)
    }

    @Test
    fun aCategoryWithoutALimitIsUnlimited() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, duolingo, 20 * minute)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.READY }
        val education = state.usedCategories.single {
            (it.label as ScreenTimeInfoLabel.Category).category == AppCategory.EDUCATION
        }

        assertEquals(20 * minute, education.usedMs)
        assertFalse(education.hasLimit)
        assertNull(education.remainingMs)
        assertFalse(education.exceeded)
    }

    @Test
    fun anExceededCategoryDoesNotHideTheTotal() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, game, 50 * minute)
        limits.upsert(1L, child, LimitScope.CATEGORY, AppCategory.GAMES, 45)
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 600)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.READY }

        val games = state.usedCategories.single {
            (it.label as ScreenTimeInfoLabel.Category).category == AppCategory.GAMES
        }
        assertTrue("the category is reached", games.exceeded)
        assertFalse("the total still reports its own result", state.total!!.exceeded)
        assertTrue(state.total!!.hasLimit)
    }

    // ---- usage availability -------------------------------------------------

    @Test
    fun unavailableUsageAccessIsNotShownAsZeroUsage() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, youtube, 90 * minute)
        access.value = false

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.USAGE_UNAVAILABLE }

        assertEquals(ScreenTimeSummaryStatus.USAGE_UNAVAILABLE, state.status)
        assertNull("no total is claimed at all", state.total)
        assertFalse(state.hasData)
    }

    @Test
    fun usageAccessBecomingAvailableProducesTheRealData() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child)
        use(1L, child, youtube, 90 * minute)
        access.value = false
        val flow = state()
        await(flow) { it.status == ScreenTimeSummaryStatus.USAGE_UNAVAILABLE }

        access.value = true

        val ready = await(flow) { it.status == ScreenTimeSummaryStatus.READY }
        assertEquals(90 * minute, ready.total!!.usedMs)
    }

    // ---- target -------------------------------------------------------------

    @Test
    fun noSelectedChildShowsTheExplicitEmptyState() = runBlocking {
        addChild(1L, "Ali")
        target.value = ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.READY, activeChildId = null)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.NO_TARGET }

        assertEquals(ScreenTimeSummaryStatus.NO_TARGET, state.status)
        assertNull(state.total)
        assertTrue("and no arbitrary child is reported", state.childId == null)
    }

    @Test
    fun aDeletedChildDoesNotFallBackToASibling() = runBlocking {
        val ali = addChild(1L, "Ali")
        val bek = addChild(1L, "Bek")
        use(1L, ali, youtube, 70 * minute)
        use(1L, bek, youtube, 20 * minute)
        targetChild(ali, "Ali")
        await(state()) { it.status == ScreenTimeSummaryStatus.READY }

        // The target is deleted: the summary must go to the empty state, not adopt Bek.
        children.deleteChild(1L, ali)
        target.value = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = children.observeChildren(1L).first(),
            activeChildId = ali,
        )

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.NO_TARGET }
        assertNull("no sibling's usage may be shown", state.total)
    }

    @Test
    fun switchingTheSelectedChildShowsThatChildsData() = runBlocking {
        val ali = addChild(1L, "Ali")
        val bek = addChild(1L, "Bek")
        use(1L, ali, youtube, 70 * minute)
        limits.upsert(1L, ali, LimitScope.TOTAL, limitMinutes = 120)
        use(1L, bek, youtube, 20 * minute)
        limits.upsert(1L, bek, LimitScope.TOTAL, limitMinutes = 60)
        targetChild(ali, "Ali")
        val flow = state()
        assertEquals(70 * minute, await(flow) { it.status == ScreenTimeSummaryStatus.READY }.total!!.usedMs)

        targetChild(bek, "Bek")

        val bekState = await(flow) { it.childName == "Bek" }
        assertEquals("Bek", bekState.childName)
        assertEquals(20 * minute, bekState.total!!.usedMs)
        assertEquals(60, bekState.total!!.limitMinutes)
    }

    // ---- account isolation --------------------------------------------------

    @Test
    fun anotherAccountsUsageAndLimitsAreNeverShown() = runBlocking {
        val childOfOne = addChild(1L, "Ali")
        val childOfTwo = addChild(2L, "Bek")
        use(1L, childOfOne, youtube, 70 * minute)
        limits.upsert(1L, childOfOne, LimitScope.TOTAL, limitMinutes = 120)
        use(2L, childOfTwo, youtube, 20 * minute)
        limits.upsert(2L, childOfTwo, LimitScope.TOTAL, limitMinutes = 60)

        targetChild(childOfOne, "Ali")
        val flow = state()
        assertEquals(70 * minute, await(flow) { it.status == ScreenTimeSummaryStatus.READY }.total!!.usedMs)

        // Switch the account: the second account has its own child and its own numbers.
        accounts.switchTo(2L)
        target.value = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = children.observeChildren(2L).first(),
            activeChildId = childOfTwo,
        )

        val state = await(flow) { it.childId == childOfTwo }
        assertEquals(20 * minute, state.total!!.usedMs)
        assertEquals(60, state.total!!.limitMinutes)
    }

    // ---- live updates -------------------------------------------------------

    @Test
    fun theSummaryFollowsAUsesAndLimitChange() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child, "Ali")
        val flow = state()
        assertEquals(0L, await(flow) { it.status == ScreenTimeSummaryStatus.READY }.total!!.usedMs)

        // A limit arrives: the state follows without any re-subscription or polling.
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 60)
        val limited = await(flow) { it.total?.hasLimit == true }
        assertEquals(60, limited.total!!.limitMinutes)

        // Usage arrives: so does that.
        use(1L, child, youtube, 30 * minute)
        val withUsage = await(flow) { it.total?.usedMs == 30 * minute }
        assertEquals(30 * minute, withUsage.total!!.remainingMs)
    }

    @Test
    fun removingALimitReturnsTheSummaryToUnlimited() = runBlocking {
        val child = addChild(1L, "Ali")
        targetChild(child, "Ali")
        use(1L, child, youtube, 90 * minute)
        limits.upsert(1L, child, LimitScope.TOTAL, limitMinutes = 30)
        val flow = state()
        assertTrue(await(flow) { it.total?.exceeded == true }.total!!.exceeded)

        limits.delete(1L, child, LimitScope.TOTAL)

        val after = await(flow) { it.total?.hasLimit == false }
        assertFalse(after.total!!.exceeded)
        assertNull(after.total!!.remainingMs)
    }

    @Test
    fun noAccountShowsTheNoAccountState() = runBlocking {
        accounts.switchTo(null)

        val state = await(state()) { it.status == ScreenTimeSummaryStatus.NO_ACCOUNT }

        assertEquals(ScreenTimeSummaryStatus.NO_ACCOUNT, state.status)
        assertNull(state.total)
    }
}
