package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimit
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository

/**
 * Phase 4 Step 1D: configuring TOTAL / CATEGORY limits through the repository, over the real
 * v8 database, and the evaluator seeing exactly what was configured.
 *
 * The limits are written and read through the shipped repository (never the DAO directly), so
 * these assert the path the configuration UI depends on. Usage is written through the real
 * usage repository and classified with the same [AppCategories] classifier production uses,
 * because the stored category column is authoritative.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeLimitConfigurationTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var limits: ScreenTimeLimitRepository
    private lateinit var usage: ScreenTimeUsageRepository
    private lateinit var evaluator: ScreenTimeLimitEvaluator

    private val day = "2026-09-25"
    private val minute = 60_000L
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"
    private val game = "com.example.mygame"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        limits = ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao()) { 1L }
        usage = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        evaluator = ScreenTimeLimitEvaluator(
            usage,
            limits,
            ChildAppPolicyRepositoryImpl(db.childAppPolicyDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun use(accountId: Long, childId: Long, packageName: String, usedMs: Long) {
        usage.addUsage(accountId, childId, day, packageName, AppCategories.categoryFor(packageName), usedMs)
    }

    // ---- TOTAL create / read / update / remove ------------------------------

    @Test
    fun aTotalLimitCanBeCreatedReadUpdatedAndRemoved() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        assertEquals(120, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)

        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 90)
        assertEquals("an update replaces the value", 90, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)

        limits.delete(1L, 10L, LimitScope.TOTAL)
        assertNull("removed means absent, not zero", limits.limit(1L, 10L, LimitScope.TOTAL))
    }

    @Test
    fun aTotalLimitSurvivesReopeningTheRepository() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 45)

        val reopened = ScreenTimeLimitRepositoryImpl(db.childScreenTimeLimitDao()) { 2L }

        assertEquals(45, reopened.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun theTotalLimitIsObservableWithoutPolling() = runBlocking {
        assertEquals(null, limits.observeLimits(1L, 10L).first().firstOrNull { it.scope == LimitScope.TOTAL })

        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 60)

        assertEquals(60, limits.observeLimits(1L, 10L).first().single { it.scope == LimitScope.TOTAL }.limitMinutes)
    }

    // ---- CATEGORY create / read / update / remove ---------------------------

    @Test
    fun aCategoryLimitCanBeCreatedReadUpdatedAndRemoved() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 30)
        assertEquals(30, limits.limit(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES)?.limitMinutes)

        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 15)
        assertEquals(15, limits.limit(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES)?.limitMinutes)

        limits.delete(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES)
        assertNull(limits.limit(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES))
    }

    @Test
    fun categoryLimitsAreStoredByTheirEnumNameNotLocalizedText() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.EDUCATION, 60)

        val row = db.childScreenTimeLimitDao().limit(1L, 10L, LimitScope.CATEGORY.name, AppCategory.EDUCATION.name)

        assertEquals("EDUCATION", row?.category)
        assertEquals(60, row?.limitMinutes)
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun aNegativeLimitIsRejectedAndNothingIsStored() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = -30) }
        }
        assertNull("a rejected value must not be persisted", limits.limit(1L, 10L, LimitScope.TOTAL))
    }

    @Test
    fun aLimitAboveADayIsRejected() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 1441) }
        }
        assertNull(limits.limit(1L, 10L, LimitScope.TOTAL))
    }

    @Test
    fun theLargestAcceptedLimitIsStored() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 1440)

        assertEquals(1440, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun aRejectedValueDoesNotOverwriteAnExistingValidOne() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 60)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = -1) }
        }

        assertEquals("the saved value is untouched", 60, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun aZeroLimitIsAcceptedAndStaysDistinctFromNoLimit() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 0)

        assertEquals("0 is a real configuration, not 'no limit'", 0, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
        assertTrue(limits.limits(1L, 10L).isNotEmpty())

        // And absence is genuinely different from 0.
        limits.delete(1L, 10L, LimitScope.TOTAL)
        assertNull(limits.limit(1L, 10L, LimitScope.TOTAL))
    }

    @Test
    fun anAppLimitRowCannotBeWrittenThroughThisStore() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limits.upsert(1L, 10L, LimitScope.APP, limitMinutes = 30) }
        }
    }

    @Test
    fun anInvalidTargetIsRejected() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limits.upsert(0L, 10L, LimitScope.TOTAL, limitMinutes = 30) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limits.upsert(1L, -1L, LimitScope.TOTAL, limitMinutes = 30) }
        }
    }

    // ---- account isolation --------------------------------------------------

    @Test
    fun limitsAreIsolatedPerAccount() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(2L, 10L, LimitScope.TOTAL, limitMinutes = 60)

        assertEquals(120, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
        assertEquals(60, limits.limit(2L, 10L, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun removingOneAccountsLimitLeavesTheOtherAlone() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(2L, 10L, LimitScope.TOTAL, limitMinutes = 60)

        limits.delete(1L, 10L, LimitScope.TOTAL)

        assertNull(limits.limit(1L, 10L, LimitScope.TOTAL))
        assertEquals("the other account is untouched", 60, limits.limit(2L, 10L, LimitScope.TOTAL)?.limitMinutes)
    }

    // ---- child isolation ----------------------------------------------------

    @Test
    fun limitsAreIsolatedPerChild() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(1L, 11L, LimitScope.TOTAL, limitMinutes = 60)

        assertEquals(120, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
        assertEquals(60, limits.limit(1L, 11L, LimitScope.TOTAL)?.limitMinutes)
    }

    @Test
    fun removingOneChildsLimitLeavesTheOtherAlone() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(1L, 11L, LimitScope.TOTAL, limitMinutes = 60)

        limits.delete(1L, 10L, LimitScope.TOTAL)

        assertNull(limits.limit(1L, 10L, LimitScope.TOTAL))
        assertEquals(60, limits.limit(1L, 11L, LimitScope.TOTAL)?.limitMinutes)
    }

    // ---- category isolation -------------------------------------------------

    @Test
    fun categoriesAreIsolatedFromEachOther() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 30)
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.EDUCATION, 60)

        limits.delete(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES)

        assertNull(limits.limit(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES))
        assertEquals("EDUCATION survives", 60, limits.limit(1L, 10L, LimitScope.CATEGORY, AppCategory.EDUCATION)?.limitMinutes)
    }

    @Test
    fun removingACategoryLimitLeavesTheTotalLimitAlone() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 30)

        limits.delete(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES)

        assertEquals(120, limits.limit(1L, 10L, LimitScope.TOTAL)?.limitMinutes)
    }

    // ---- app credit: policies are a different store -------------------------

    @Test
    fun appLimitsStayInThePolicyStoreAndAreIsolated() = runBlocking {
        val policies = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        policies.upsert(1L, 10L, AppPolicy(packageName = youtube, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = 30))
        policies.upsert(1L, 10L, AppPolicy(packageName = tiktok, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = 45))

        assertEquals(30, policies.policyFor(1L, 10L, youtube)?.dailyLimitMinutes)
        assertEquals(45, policies.policyFor(1L, 10L, tiktok)?.dailyLimitMinutes)

        // Removing youtube's policy must not touch tiktok's.
        policies.delete(1L, 10L, youtube)
        assertNull(policies.policyFor(1L, 10L, youtube))
        assertEquals(45, policies.policyFor(1L, 10L, tiktok)?.dailyLimitMinutes)
    }

    // ---- evaluator integration ---------------------------------------------

    @Test
    fun aConfiguredTotalLimitIsVisibleToTheEvaluator() = runBlocking {
        use(1L, 10L, youtube, 70 * minute)
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)

        val result = evaluator.evaluateTotal(1L, 10L, day)

        assertTrue(result.hasLimit)
        assertEquals(120, result.limitMinutes)
        assertEquals(50 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun aConfiguredCategoryLimitIsVisibleToTheEvaluator() = runBlocking {
        use(1L, 10L, game, 40 * minute)
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)

        val result = evaluator.evaluateCategory(1L, 10L, day, AppCategory.GAMES)

        assertTrue(result.hasLimit)
        assertEquals(40 * minute, result.usedMs)
        assertEquals(5 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun aConfiguredAppLimitIsVisibleToTheEvaluator() = runBlocking {
        use(1L, 10L, youtube, 25 * minute)
        ChildAppPolicyRepositoryImpl(db.childAppPolicyDao()).upsert(
            1L,
            10L,
            AppPolicy(packageName = youtube, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = 30),
        )

        val result = evaluator.evaluateApp(1L, 10L, day, youtube)

        assertTrue(result.hasLimit)
        assertEquals(5 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun aConfiguredLimitBecomesExceededAtTheBoundaryAndBeyond() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 30)
        use(1L, 10L, youtube, 30 * minute)

        val atLimit = evaluator.evaluateTotal(1L, 10L, day)
        assertTrue("reaching the limit counts as exceeded", atLimit.exceeded)
        assertEquals(0L, atLimit.remainingMs)

        usage.addUsage(1L, 10L, day, youtube, AppCategories.categoryFor(youtube), 15 * minute)
        val beyond = evaluator.evaluateTotal(1L, 10L, day)
        assertTrue(beyond.exceeded)
        assertEquals("remaining never goes negative", 0L, beyond.remainingMs)
    }

    @Test
    fun aZeroLimitIsExceededImmediately() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 0)

        val result = evaluator.evaluateTotal(1L, 10L, day)

        assertTrue(result.hasLimit)
        assertTrue(result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    @Test
    fun removingTheTotalLimitMakesTheScopeUnlimitedAgain() = runBlocking {
        use(1L, 10L, youtube, 200 * minute)
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 30)
        assertTrue("configured and over the limit", evaluator.evaluateTotal(1L, 10L, day).exceeded)

        limits.delete(1L, 10L, LimitScope.TOTAL)

        val after = evaluator.evaluateTotal(1L, 10L, day)
        assertFalse("removed means unlimited", after.hasLimit)
        assertFalse("and not exceeded", after.exceeded)
        assertNull(after.remainingMs)
        assertEquals("usage is still reported", 200 * minute, after.usedMs)
    }

    @Test
    fun removingTheCategoryLimitMakesTheScopeUnlimitedAgain() = runBlocking {
        use(1L, 10L, game, 200 * minute)
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 30)
        assertTrue(evaluator.evaluateCategory(1L, 10L, day, AppCategory.GAMES).exceeded)

        limits.delete(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES)

        assertFalse(evaluator.evaluateCategory(1L, 10L, day, AppCategory.GAMES).hasLimit)
    }

    @Test
    fun removingAnAppDailyLimitLeavesTheRestOfThePolicySemanticsToThePolicyStore() = runBlocking {
        val policies = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        use(1L, 10L, youtube, 200 * minute)
        policies.upsert(1L, 10L, AppPolicy(packageName = youtube, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = 30))
        assertTrue(evaluator.evaluateApp(1L, 10L, day, youtube).exceeded)

        // Removing the app's limit is the existing policy operation: the override is dropped,
        // so the app falls back to the inherited rule and evaluates as unlimited again.
        policies.delete(1L, 10L, youtube)

        val after = evaluator.evaluateApp(1L, 10L, day, youtube)
        assertFalse(after.hasLimit)
        assertFalse(after.exceeded)
    }

    @Test
    fun aBlockPolicyIsNotMisreadAsAnUnlimitedTimeLimit() = runBlocking {
        // BLOCK is enforcement, not a daily allowance, so it carries no screen-time limit.
        ChildAppPolicyRepositoryImpl(db.childAppPolicyDao()).upsert(
            1L,
            10L,
            AppPolicy(packageName = youtube, mode = AppPolicyMode.BLOCK),
        )
        use(1L, 10L, youtube, 500 * minute)

        assertFalse(evaluator.evaluateApp(1L, 10L, day, youtube).hasLimit)
    }

    @Test
    fun aLimitModeWithNoMinutesIsUnlimited() = runBlocking {
        ChildAppPolicyRepositoryImpl(db.childAppPolicyDao()).upsert(
            1L,
            10L,
            AppPolicy(packageName = youtube, mode = AppPolicyMode.LIMIT, dailyLimitMinutes = null),
        )
        use(1L, 10L, youtube, 500 * minute)

        assertFalse(evaluator.evaluateApp(1L, 10L, day, youtube).hasLimit)
    }

    // ---- per-child configuration drives per-child evaluation ----------------

    @Test
    fun eachChildIsEvaluatedAgainstItsOwnConfiguredLimit() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(1L, 11L, LimitScope.TOTAL, limitMinutes = 30)
        use(1L, 10L, youtube, 60 * minute)
        use(1L, 11L, youtube, 60 * minute)

        assertFalse("child 10 is within 120", evaluator.evaluateTotal(1L, 10L, day).exceeded)
        assertTrue("child 11 is over 30", evaluator.evaluateTotal(1L, 11L, day).exceeded)
    }

    @Test
    fun theConfiguredLimitsAreReturnedForKeyedByScope() = runBlocking {
        limits.upsert(1L, 10L, LimitScope.TOTAL, limitMinutes = 120)
        limits.upsert(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)

        val all = limits.limits(1L, 10L)

        assertEquals(2, all.size)
        assertTrue(all.any { it.scope == LimitScope.TOTAL && it.category == null && it.limitMinutes == 120 })
        assertTrue(all.any { it.scope == LimitScope.CATEGORY && it.category == AppCategory.GAMES })
        assertTrue("and no per-app row is invented", all.none { it.scope == LimitScope.APP })
    }

    @Test
    fun noConfigurationAtAllMeansNothingIsStored() = runBlocking {
        assertEquals(emptyList<ScreenTimeLimit>(), limits.limits(1L, 10L))
        assertEquals(0, db.childScreenTimeLimitDao().limits(1L, 10L).size)
    }
}
