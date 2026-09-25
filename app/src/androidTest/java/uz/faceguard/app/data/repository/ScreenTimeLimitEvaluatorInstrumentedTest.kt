package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.ChildScreenTimeLimitEntity
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimit
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageInterval

/**
 * Phase 4 Step 1C: the evaluator over the **real** v8 database.
 *
 * Every source of truth is the shipped one — the usage table, the limit table (through the
 * new read-only repository), the child app policies and the real usage repository — so this
 * proves the evaluation reads what is actually configured, keyed by account, child and day.
 *
 * The limits are written through the existing DAO/entity exactly as a future configuration
 * UI would, so no test-only storage convention is introduced.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeLimitEvaluatorInstrumentedTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var usage: ScreenTimeUsageRepository
    private lateinit var limits: ScreenTimeLimitRepository
    private lateinit var policies: ChildAppPolicyRepositoryImpl
    private lateinit var evaluator: ScreenTimeLimitEvaluator

    private val day = "2026-09-25"
    private val otherDay = "2026-09-26"
    private val minute = 60_000L
    private val youtube = "com.google.android.youtube"
    private val duolingo = "com.duolingo"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()

        usage = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        limits = ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao())
        policies = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        evaluator = ScreenTimeLimitEvaluator(usage, limits, policies)
    }

    @After
    fun tearDown() = db.close()

    /**
     * Writes usage through the real repository, so rows land in the real table.
     *
     * The category is resolved with the same classifier production uses
     * ([AppCategories.categoryFor]) because the stored `category` column is authoritative:
     * the category is fixed when the row is written, not re-derived when it is read.
     */
    private suspend fun use(accountId: Long, childId: Long, dateKey: String, packageName: String, usedMs: Long) {
        usage.addUsage(
            accountId,
            childId,
            dateKey,
            packageName,
            AppCategories.categoryFor(packageName),
            usedMs,
        )
    }

    /** Writes limits exactly as the storage convention expects (""-category for TOTAL). */
    private suspend fun setLimit(accountId: Long, childId: Long, scope: LimitScope, category: AppCategory?, minutes: Int?) {
        db.childScreenTimeLimitDao().upsert(
            ChildScreenTimeLimitEntity(
                accountId = accountId,
                childId = childId,
                scope = scope.name,
                category = category?.name ?: "",
                limitMinutes = minutes,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun setAppLimit(accountId: Long, childId: Long, packageName: String, minutes: Int?) {
        policies.upsert(
            accountId,
            childId,
            AppPolicy(packageName = packageName, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = minutes),
        )
    }

    // ---- TOTAL over the real table ------------------------------------------

    @Test
    fun totalLimitIsReadFromTheRealTableAndEvaluated() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, 120)
        use(1L, 10L, day, youtube, 70 * minute)

        val result = evaluator.evaluateTotal(1L, 10L, day)

        assertEquals(120, result.limitMinutes)
        assertEquals(70 * minute, result.usedMs)
        assertEquals(50 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun withNoStoredLimitTheRealEvaluationIsUnlimited() = runBlocking {
        use(1L, 10L, day, youtube, 500 * minute)

        val result = evaluator.evaluateTotal(1L, 10L, day)

        assertFalse(result.hasLimit)
        assertNull(result.remainingMs)
        assertFalse("a missing row is never read as exceeded", result.exceeded)
    }

    @Test
    fun theTotalLimitIsScopedPerAccountAndChildInTheDatabase() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, 60)
        setLimit(1L, 11L, LimitScope.TOTAL, null, 600)
        setLimit(2L, 10L, LimitScope.TOTAL, null, 30)
        use(1L, 10L, day, youtube, 50 * minute)
        use(1L, 11L, day, youtube, 50 * minute)
        use(2L, 10L, day, youtube, 50 * minute)

        assertEquals(60, evaluator.evaluateTotal(1L, 10L, day).limitMinutes)
        assertEquals(600, evaluator.evaluateTotal(1L, 11L, day).limitMinutes)
        assertEquals(30, evaluator.evaluateTotal(2L, 10L, day).limitMinutes)

        assertFalse("1/10 is under 60", evaluator.evaluateTotal(1L, 10L, day).exceeded)
        assertFalse("1/11 is well under 600", evaluator.evaluateTotal(1L, 11L, day).exceeded)
        assertTrue("2/10 is over 30", evaluator.evaluateTotal(2L, 10L, day).exceeded)
    }

    @Test
    fun evaluationDoesNotMixDays() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, 120)
        use(1L, 10L, day, youtube, 60 * minute)
        use(1L, 10L, otherDay, youtube, 200 * minute)

        assertEquals(60 * minute, evaluator.evaluateTotal(1L, 10L, day).usedMs)
        assertFalse(evaluator.evaluateTotal(1L, 10L, day).exceeded)
        assertTrue(evaluator.evaluateTotal(1L, 10L, otherDay).exceeded)
    }

    // ---- APP over the real policies -----------------------------------------

    @Test
    fun appLimitIsReadFromTheRealPolicyAndEvaluated() = runBlocking {
        setAppLimit(1L, 10L, youtube, 30)
        use(1L, 10L, day, youtube, 25 * minute)

        val result = evaluator.evaluateApp(1L, 10L, day, youtube)

        assertEquals(30, result.limitMinutes)
        assertEquals(5 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun appWithoutAPolicyIsUnlimited() = runBlocking {
        use(1L, 10L, day, youtube, 500 * minute)

        assertFalse(evaluator.evaluateApp(1L, 10L, day, youtube).hasLimit)
    }

    @Test
    fun appLimitIsIsolatedBetweenChildren() = runBlocking {
        setAppLimit(1L, 10L, youtube, 30)
        use(1L, 10L, day, youtube, 40 * minute)
        use(1L, 11L, day, youtube, 40 * minute)

        assertTrue("child 10 has the 30m limit and is over it", evaluator.evaluateApp(1L, 10L, day, youtube).exceeded)
        assertFalse("child 11 has no limit of its own", evaluator.evaluateApp(1L, 11L, day, youtube).hasLimit)
    }

    // ---- CATEGORY over the real table ---------------------------------------

    @Test
    fun categoryLimitIsReadFromTheRealTableAndAggregatesThatCategory() = runBlocking {
        setLimit(1L, 10L, LimitScope.CATEGORY, AppCategory.EDUCATION, 45)
        use(1L, 10L, day, duolingo, 40 * minute)          // EDUCATION
        use(1L, 10L, day, youtube, 100 * minute)          // VIDEO, must not count

        val result = evaluator.evaluateCategory(1L, 10L, day, AppCategory.EDUCATION)

        assertEquals("only EDUCATION counts", 40 * minute, result.usedMs)
        assertEquals(5 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun aCategoryWithoutALimitIsUnlimited() = runBlocking {
        use(1L, 10L, day, duolingo, 500 * minute)

        assertFalse(evaluator.evaluateCategory(1L, 10L, day, AppCategory.EDUCATION).hasLimit)
    }

    // ---- independent scopes over the real sources ---------------------------

    @Test
    fun allScopesAreReportedIndependentlyFromTheRealData() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, 120)
        setLimit(1L, 10L, LimitScope.CATEGORY, AppCategory.EDUCATION, 10)
        setAppLimit(1L, 10L, youtube, 30)
        use(1L, 10L, day, youtube, 25 * minute)
        use(1L, 10L, day, duolingo, 50 * minute)

        val all = evaluator.evaluateAll(1L, 10L, day, packages = setOf(youtube, duolingo))

        val total = all.single { it.scope == LimitScope.TOTAL }
        val app = all.single { it.scope == LimitScope.APP && it.packageName == youtube }
        val education = all.single { it.category == AppCategory.EDUCATION }

        assertEquals(75 * minute, total.usedMs)
        assertFalse("total is under its limit", total.exceeded)
        assertEquals(5 * minute, app.remainingMs)
        assertFalse("youtube is under its limit", app.exceeded)
        assertTrue("education is over its limit", education.exceeded)
    }

    // ---- the repository view of the limits ----------------------------------

    @Test
    fun theLimitRepositoryExposesOnlyTotalAndCategoryLimits() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, 120)
        setLimit(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)

        val all = limits.limits(1L, 10L)

        assertEquals(2, all.size)
        assertTrue(all.any { it.scope == LimitScope.TOTAL && it.category == null })
        assertTrue(all.any { it.scope == LimitScope.CATEGORY && it.category == AppCategory.GAMES })
        assertEquals("and nothing leaks from another child", emptyList<ScreenTimeLimit>(), limits.limits(1L, 11L))
    }

    @Test
    fun theLimitRepositoryFindsTheTotalLimitStoredWithAnEmptyCategory() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, 90)

        val total = limits.limit(1L, 10L, LimitScope.TOTAL)

        assertEquals(90, total?.limitMinutes)
        assertNull("per-app limits are not part of this store", limits.limit(1L, 10L, LimitScope.APP))
    }

    @Test
    fun anUnreadableCategoryRowIsTreatedAsNoLimit() = runBlocking {
        // A row whose category cannot be interpreted must not become a limit nobody can explain.
        db.childScreenTimeLimitDao().upsert(
            ChildScreenTimeLimitEntity(
                accountId = 1L,
                childId = 10L,
                scope = LimitScope.CATEGORY.name,
                category = "NOT_A_CATEGORY",
                limitMinutes = 10,
                updatedAt = 1L,
            ),
        )

        assertEquals(emptyList<ScreenTimeLimit>(), limits.limits(1L, 10L))
        assertFalse(evaluator.evaluateCategory(1L, 10L, day, AppCategory.GAMES).hasLimit)
    }

    @Test
    fun aStoredNegativeLimitIsReportedInvalidRatherThanUnlimited() = runBlocking {
        setLimit(1L, 10L, LimitScope.TOTAL, null, -30)
        use(1L, 10L, day, youtube, 10 * minute)

        val result = evaluator.evaluateTotal(1L, 10L, day)

        assertEquals(-30, result.invalidLimitMinutes)
        assertFalse(result.hasLimit)
        assertFalse(result.exceeded)
    }

    // ---- integration with the accounting pipeline ---------------------------

    @Test
    fun evaluatedUsageIsTheUsageTheAccountingPipelineActuallyWrote() = runBlocking {
        val accounting = ScreenTimeUsageAccounting(usage, ZoneOffset.UTC)
        accounting.recordInterval(
            1L,
            10L,
            UsageInterval.of(
                youtube,
                AppCategory.VIDEO,
                startTimeMs = 1_790_294_400_000L,
                elapsedMs = 45 * minute,
            ),
        )
        setLimit(1L, 10L, LimitScope.TOTAL, null, 60)

        val result = evaluator.evaluateTotal(1L, 10L, day)

        assertEquals("the accounted 45 minutes are what is evaluated", 45 * minute, result.usedMs)
        assertEquals(15 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun theDailyAppUsageTableIsStillTheUsageSourceOfTruth() = runBlocking {
        use(1L, 10L, day, youtube, 5 * minute)

        val rows = db.dailyAppUsageDao().dayUsage(1L, 10L, day)

        assertEquals(1, rows.size)
        assertEquals(5 * minute, rows.single().usedMs)
        assertEquals("the evaluator read the same row", 5 * minute, evaluator.evaluateApp(1L, 10L, day, youtube).usedMs)
    }
}
