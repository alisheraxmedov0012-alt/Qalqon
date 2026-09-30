package uz.faceguard.app.screentime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimit
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository

/**
 * Phase 4 Step 1C (pure JVM): the limit evaluation semantics.
 *
 * The evaluator is production code; only the three domain repositories are fakes, so these
 * assert the behaviour a later enforcement/UI layer will build on. Every case names its
 * account, child and day explicitly — nothing is inferred and no clock is involved.
 */
class ScreenTimeLimitEvaluatorTest {

    private val minute = 60_000L
    private val day = "2026-09-25"
    private val otherDay = "2026-09-26"
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"

    // ---- fakes --------------------------------------------------------------

    /** Usage rows keyed exactly like the real table: account + child + day (+ package). */
    private class FakeUsage : ScreenTimeUsageRepository {
        val appMs = mutableMapOf<Triple<Long, Long, String>, MutableMap<String, Long>>()

        fun set(accountId: Long, childId: Long, dateKey: String, packageName: String, usedMs: Long) {
            appMs.getOrPut(Triple(accountId, childId, dateKey)) { mutableMapOf() }[packageName] = usedMs
        }

        private fun rows(accountId: Long, childId: Long, dateKey: String) =
            appMs[Triple(accountId, childId, dateKey)] ?: emptyMap()

        override suspend fun getAppUsageMs(accountId: Long, childId: Long, dateKey: String, packageName: String) =
            rows(accountId, childId, dateKey)[packageName] ?: 0L

        override suspend fun getCategoryUsageMs(accountId: Long, childId: Long, dateKey: String, category: AppCategory) =
            rows(accountId, childId, dateKey)
                .filterKeys { AppCategories.categoryFor(it) == category }
                .values.sum()

        override suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String) =
            rows(accountId, childId, dateKey).values.sum()

        override suspend fun getDayUsage(accountId: Long, childId: Long, dateKey: String) =
            rows(accountId, childId, dateKey).map { (pkg, ms) ->
                AppUsage(pkg, AppCategories.categoryFor(pkg), ms)
            }

        override fun observeDayUsage(accountId: Long, childId: Long, dateKey: String): Flow<List<AppUsage>> =
            flowOf(emptyList())

        override suspend fun addUsage(
            accountId: Long,
            childId: Long,
            dateKey: String,
            packageName: String,
            category: AppCategory,
            deltaMs: Long,
        ) = Unit
    }

    /** Limits exactly like the real table, including the ""-category TOTAL convention. */
    private class FakeLimits : ScreenTimeLimitRepository {
        private val state = MutableStateFlow<List<ScreenTimeLimit>>(emptyList())

        fun set(accountId: Long, childId: Long, scope: LimitScope, category: AppCategory?, minutes: Int?) {
            state.update { rows ->
                rows.filterNot {
                    it.accountId == accountId && it.childId == childId &&
                        it.scope == scope && it.category == category
                } + if (minutes == null) {
                    emptyList()
                } else {
                    listOf(ScreenTimeLimit(accountId, childId, scope, category, minutes))
                }
            }
        }

        override suspend fun limits(accountId: Long, childId: Long) =
            state.value.filter { it.accountId == accountId && it.childId == childId }

        override fun observeLimits(accountId: Long, childId: Long): Flow<List<ScreenTimeLimit>> =
            state.map { rows -> rows.filter { it.accountId == accountId && it.childId == childId } }

        override suspend fun upsert(
            accountId: Long,
            childId: Long,
            scope: LimitScope,
            category: AppCategory?,
            limitMinutes: Int,
        ) = set(accountId, childId, scope, category, limitMinutes)

        override suspend fun delete(
            accountId: Long,
            childId: Long,
            scope: LimitScope,
            category: AppCategory?,
        ) = set(accountId, childId, scope, category, null)

        override suspend fun limit(accountId: Long, childId: Long, scope: LimitScope, category: AppCategory?) =
            state.value.firstOrNull {
                it.accountId == accountId && it.childId == childId &&
                    it.scope == scope && it.category == category
            }
    }

    private class FakePolicies : ChildAppPolicyRepository {
        private val rows = mutableMapOf<Triple<Long, Long, String>, AppPolicy>()

        fun set(accountId: Long, childId: Long, packageName: String, mode: AppPolicyMode, dailyLimitMinutes: Int?) {
            rows[Triple(accountId, childId, packageName)] =
                AppPolicy(packageName = packageName, mode = mode, dailyLimitMinutes = dailyLimitMinutes)
        }

        override fun observePolicies(accountId: Long, childId: Long): Flow<List<AppPolicy>> = flowOf(emptyList())

        override suspend fun policyFor(accountId: Long, childId: Long, packageName: String) =
            rows[Triple(accountId, childId, packageName)]

        override suspend fun upsert(accountId: Long, childId: Long, policy: AppPolicy) = Unit
        override suspend fun delete(accountId: Long, childId: Long, packageName: String) = Unit
        override suspend fun deleteAllForChild(accountId: Long, childId: Long) = Unit
    }

    private val usage = FakeUsage()
    private val limits = FakeLimits()
    private val policies = FakePolicies()
    private val evaluator = ScreenTimeLimitEvaluator(usage, limits, policies)

    private fun total(accountId: Long = 1L, childId: Long = 10L, dateKey: String = day) =
        runBlocking { evaluator.evaluateTotal(accountId, childId, dateKey) }

    private fun app(packageName: String = youtube, accountId: Long = 1L, childId: Long = 10L, dateKey: String = day) =
        runBlocking { evaluator.evaluateApp(accountId, childId, dateKey, packageName) }

    private fun category(category: AppCategory = AppCategory.GAMES, accountId: Long = 1L, childId: Long = 10L, dateKey: String = day) =
        runBlocking { evaluator.evaluateCategory(accountId, childId, dateKey, category) }

    // ---- TOTAL --------------------------------------------------------------

    @Test
    fun total_withNoConfiguredLimitIsUnlimitedAndNotExceeded() {
        usage.set(1L, 10L, day, youtube, 70 * minute)

        val result = total()

        assertEquals(LimitScope.TOTAL, result.scope)
        assertFalse("no configured limit means no limit", result.hasLimit)
        assertNull("and no remaining time to report", result.remainingMs)
        assertNull(result.limitMinutes)
        assertFalse("a missing limit must never read as exceeded", result.exceeded)
        assertEquals("usage is still reported", 70 * minute, result.usedMs)
        assertNull(result.invalidLimitMinutes)
    }

    @Test
    fun total_belowTheLimitLeavesRemainingTime() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 70 * minute)

        val result = total()

        assertTrue(result.hasLimit)
        assertEquals(120, result.limitMinutes)
        assertEquals(50 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun total_exactlyAtTheLimitIsExceededWithNoRemainingTime() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 120 * minute)

        val result = total()

        assertTrue("equality is reached, not still allowed", result.exceeded)
        assertEquals(0L, result.remainingMs)
        assertEquals(120 * minute, result.usedMs)
    }

    @Test
    fun total_aboveTheLimitIsExceededAndRemainingNeverGoesNegative() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 200 * minute)

        val result = total()

        assertTrue(result.exceeded)
        assertEquals("remaining clamps to zero", 0L, result.remainingMs)
    }

    @Test
    fun total_zeroUsageWithAPositiveLimitIsWithinLimit() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 30)

        val result = total()

        assertEquals(0L, result.usedMs)
        assertEquals(30 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun total_zeroLimitIsExceededImmediately() {
        // `0` is a valid configuration in this product model: "immediately exceeded".
        limits.set(1L, 10L, LimitScope.TOTAL, null, 0)
        usage.set(1L, 10L, day, youtube, 1L)

        val result = total()

        assertTrue(result.hasLimit)
        assertEquals(0, result.limitMinutes)
        assertTrue(result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    @Test
    fun total_zeroLimitIsExceededEvenWithNoUsageAtAll() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 0)

        val result = total()

        assertEquals(0L, result.usedMs)
        assertTrue("used >= 0 always holds for a zero limit", result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    // ---- APP ----------------------------------------------------------------

    @Test
    fun app_withNoPolicyIsUnlimited() {
        usage.set(1L, 10L, day, youtube, 25 * minute)

        val result = app()

        assertEquals(LimitScope.APP, result.scope)
        assertEquals(youtube, result.packageName)
        assertFalse(result.hasLimit)
        assertNull(result.remainingMs)
        assertFalse(result.exceeded)
        assertEquals(25 * minute, result.usedMs)
    }

    @Test
    fun app_belowTheLimitLeavesRemainingTime() {
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 30)
        usage.set(1L, 10L, day, youtube, 25 * minute)

        val result = app()

        assertEquals(30, result.limitMinutes)
        assertEquals(5 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun app_exactlyAtTheLimitIsExceeded() {
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 30)
        usage.set(1L, 10L, day, youtube, 30 * minute)

        val result = app()

        assertTrue(result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    @Test
    fun app_aboveTheLimitIsExceeded() {
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 30)
        usage.set(1L, 10L, day, youtube, 35 * minute)

        val result = app()

        assertTrue(result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    @Test
    fun app_allowedOrBlockedPolicyIsNotATimeLimit() {
        // ALLOW and BLOCK are enforcement modes, not daily allowances: only LIMIT carries
        // minutes, which is exactly how the policy engine reads the same model.
        policies.set(1L, 10L, youtube, AppPolicyMode.ALLOW, null)
        usage.set(1L, 10L, day, youtube, 500 * minute)

        assertFalse("an ALLOW policy is not a limit", app().hasLimit)
        assertFalse(app().exceeded)

        policies.set(1L, 10L, youtube, AppPolicyMode.BLOCK, null)
        assertFalse("a BLOCK policy is not a time limit either", app().hasLimit)
    }

    @Test
    fun app_limitPolicyWithoutMinutesIsUnlimited() {
        // The existing model's `dailyLimitMinutes == null` representation.
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, null)
        usage.set(1L, 10L, day, youtube, 500 * minute)

        val result = app()

        assertFalse(result.hasLimit)
        assertNull(result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun app_limitsAreIsolatedPerPackage() {
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 30)
        usage.set(1L, 10L, day, youtube, 35 * minute)
        usage.set(1L, 10L, day, tiktok, 35 * minute)

        assertTrue("youtube has its own limit and is over it", app(youtube).exceeded)
        assertFalse("tiktok has no limit and its usage is its own", app(tiktok).hasLimit)
        assertEquals(35 * minute, app(tiktok).usedMs)
    }

    // ---- CATEGORY -----------------------------------------------------------

    @Test
    fun category_withNoConfiguredLimitIsUnlimited() {
        usage.set(1L, 10L, day, duolingo, 40 * minute)

        val result = category(AppCategory.EDUCATION)

        assertEquals(LimitScope.CATEGORY, result.scope)
        assertEquals(AppCategory.EDUCATION, result.category)
        assertFalse(result.hasLimit)
        assertNull(result.remainingMs)
        assertFalse(result.exceeded)
        assertEquals(40 * minute, result.usedMs)
    }

    @Test
    fun category_belowTheLimitLeavesRemainingTime() {
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)
        usage.set(1L, 10L, day, "com.example.mygame", 40 * minute)

        val result = category(AppCategory.GAMES)

        assertEquals(45, result.limitMinutes)
        assertEquals(5 * minute, result.remainingMs)
        assertFalse(result.exceeded)
    }

    @Test
    fun category_exactlyAtTheLimitIsExceeded() {
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)
        usage.set(1L, 10L, day, "com.example.mygame", 45 * minute)

        val result = category(AppCategory.GAMES)

        assertTrue(result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    @Test
    fun category_aboveTheLimitIsExceeded() {
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)
        usage.set(1L, 10L, day, "com.example.mygame", 50 * minute)

        val result = category(AppCategory.GAMES)

        assertTrue(result.exceeded)
        assertEquals(0L, result.remainingMs)
    }

    @Test
    fun category_usageAggregatesOnlyThatCategory() {
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.VIDEO, 45)
        usage.set(1L, 10L, day, youtube, 20 * minute)          // VIDEO
        usage.set(1L, 10L, day, tiktok, 40 * minute)           // SOCIAL
        usage.set(1L, 10L, day, "com.netflix.mediaclient", 10 * minute) // VIDEO

        val video = category(AppCategory.VIDEO)

        assertEquals("30 min of VIDEO only", 30 * minute, video.usedMs)
        assertEquals(15 * minute, video.remainingMs)
        assertFalse(video.exceeded)
        assertEquals("SOCIAL is untouched", 40 * minute, category(AppCategory.SOCIAL).usedMs)
    }

    @Test
    fun category_limitsAreIsolatedPerCategory() {
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 10)
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.VIDEO, 100)
        usage.set(1L, 10L, day, "com.example.mygame", 50 * minute)
        usage.set(1L, 10L, day, youtube, 50 * minute)

        assertTrue("GAMES is over its small limit", category(AppCategory.GAMES).exceeded)
        assertFalse("VIDEO is under its larger limit", category(AppCategory.VIDEO).exceeded)
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun accountsAreIsolated() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        limits.set(2L, 10L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 60 * minute)
        usage.set(2L, 10L, day, youtube, 100 * minute)

        val first = total(accountId = 1L)
        val second = total(accountId = 2L)

        assertEquals(60 * minute, first.usedMs)
        assertEquals(60 * minute, first.remainingMs)
        assertEquals(100 * minute, second.usedMs)
        assertEquals(20 * minute, second.remainingMs)
        assertFalse(first.exceeded)
        assertFalse(second.exceeded)
    }

    @Test
    fun childrenWithinOneAccountAreIsolated() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        limits.set(1L, 11L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 60 * minute)
        usage.set(1L, 11L, day, youtube, 110 * minute)

        val first = total(childId = 10L)
        val second = total(childId = 11L)

        assertEquals("child 10 reads only its own usage", 60 * minute, first.usedMs)
        assertEquals(110 * minute, second.usedMs)
        assertEquals(10 * minute, second.remainingMs)
    }

    @Test
    fun datesAreIsolated() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 60 * minute)
        usage.set(1L, 10L, otherDay, youtube, 200 * minute)

        val today = total(dateKey = day)
        val tomorrow = total(dateKey = otherDay)

        assertEquals("yesterday's usage must not bleed into today", 60 * minute, today.usedMs)
        assertFalse(today.exceeded)
        assertEquals(200 * minute, tomorrow.usedMs)
        assertTrue(tomorrow.exceeded)
    }

    @Test
    fun aLimitFromOneChildDoesNotApplyToAnother() {
        limits.set(1L, 11L, LimitScope.TOTAL, null, 10)
        usage.set(1L, 10L, day, youtube, 60 * minute)

        val childWithoutALimit = total(childId = 10L)

        assertFalse("child 10 has no limit of its own", childWithoutALimit.hasLimit)
        assertFalse(childWithoutALimit.exceeded)
    }

    // ---- multiple independent scopes ---------------------------------------

    @Test
    fun totalAppAndCategoryReturnIndependentResults() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 45)
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 30)
        usage.set(1L, 10L, day, youtube, 25 * minute)             // VIDEO, under its 30m app limit
        usage.set(1L, 10L, day, "com.example.mygame", 50 * minute) // GAMES, over its 45m limit

        val all = runBlocking {
            evaluator.evaluateAll(1L, 10L, day, packages = setOf(youtube, "com.example.mygame"))
        }

        val total = all.single { it.scope == LimitScope.TOTAL }
        val app = all.single { it.scope == LimitScope.APP && it.packageName == youtube }
        val games = all.single { it.scope == LimitScope.CATEGORY && it.category == AppCategory.GAMES }

        assertEquals(75 * minute, total.usedMs)
        assertFalse("total is under its limit", total.exceeded)
        assertEquals(5 * minute, app.remainingMs)
        assertFalse("the app is under its limit", app.exceeded)
        assertTrue("only the category is exceeded", games.exceeded)
    }

    @Test
    fun anExceededCategoryDoesNotEraseANonExceededTotal() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 600)
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.GAMES, 10)
        usage.set(1L, 10L, day, "com.example.mygame", 50 * minute)

        val all = runBlocking {
            evaluator.evaluateAll(1L, 10L, day, packages = setOf("com.example.mygame"))
        }

        val total = all.single { it.scope == LimitScope.TOTAL }
        assertTrue("the total result survives", total.hasLimit)
        assertFalse(total.exceeded)
        assertTrue(all.single { it.category == AppCategory.GAMES }.exceeded)
    }

    @Test
    fun anExceededAppDoesNotEraseANonExceededCategory() {
        limits.set(1L, 10L, LimitScope.CATEGORY, AppCategory.VIDEO, 600)
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 10)
        usage.set(1L, 10L, day, youtube, 50 * minute)

        val all = runBlocking {
            evaluator.evaluateAll(1L, 10L, day, packages = setOf(youtube))
        }

        val app = all.single { it.scope == LimitScope.APP }
        val video = all.single { it.scope == LimitScope.CATEGORY && it.category == AppCategory.VIDEO }
        assertTrue(app.exceeded)
        assertFalse("the category result survives independently", video.exceeded)
        assertEquals("and it still reports its own remaining time", 550 * minute, video.remainingMs)
    }

    @Test
    fun evaluateAllKeepsOneResultPerRequestedScope() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        policies.set(1L, 10L, youtube, AppPolicyMode.LIMIT, 30)
        policies.set(1L, 10L, tiktok, AppPolicyMode.LIMIT, 30)

        val all = runBlocking {
            evaluator.evaluateAll(1L, 10L, day, packages = setOf(youtube, tiktok))
        }

        assertEquals(1, all.count { it.scope == LimitScope.TOTAL })
        assertEquals(2, all.count { it.scope == LimitScope.APP })
        assertEquals("youtube is VIDEO and tiktok is SOCIAL", 2, all.count { it.scope == LimitScope.CATEGORY })
    }

    @Test
    fun evaluateAllCanBeAskedForOnlyOneScope() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)

        val all = runBlocking {
            evaluator.evaluateAll(1L, 10L, day, packages = emptySet(), total = true, includeCategories = false)
        }

        assertEquals(listOf(LimitScope.TOTAL), all.map { it.scope })
    }

    // ---- invalid configuration ---------------------------------------------

    @Test
    fun aNegativeStoredLimitIsReportedAsInvalidNotAsUnlimited() {
        // The existing normalizer would read a negative limit as "unlimited", which would
        // silently remove a child's protection. The evaluator must not do that.
        limits.set(1L, 10L, LimitScope.TOTAL, null, -30)
        usage.set(1L, 10L, day, youtube, 10 * minute)

        val result = total()

        assertEquals("the corrupt value is surfaced", -30, result.invalidLimitMinutes)
        assertFalse("and it is not read as unlimited", result.hasLimit)
        assertFalse(result.exceeded)
        assertNull(result.remainingMs)
        assertEquals("usage is still reported honestly", 10 * minute, result.usedMs)
    }

    @Test
    fun aLimitAboveADayIsReportedAsInvalidRatherThanSilentlyClamped() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 1441)
        usage.set(1L, 10L, day, youtube, 10 * minute)

        val result = total()

        assertEquals(1441, result.invalidLimitMinutes)
        assertFalse(result.hasLimit)
    }

    @Test
    fun theLargestValidLimitIsStillValid() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 1440)
        usage.set(1L, 10L, day, youtube, 10 * minute)

        val result = total()

        assertNull(result.invalidLimitMinutes)
        assertTrue(result.hasLimit)
        assertEquals(1440, result.limitMinutes)
        assertFalse(result.exceeded)
    }

    // ---- input validation ---------------------------------------------------

    @Test
    fun anInvalidDateIsRejectedInsteadOfEvaluated() {
        assertThrows(IllegalArgumentException::class.java) { total(dateKey = "2026-9-25") }
        assertThrows(IllegalArgumentException::class.java) { total(dateKey = "") }
        assertThrows(IllegalArgumentException::class.java) { app(dateKey = "yesterday") }
        assertThrows(IllegalArgumentException::class.java) { category(dateKey = "2026-02-30") }
    }

    @Test
    fun anInvalidAccountOrChildIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { total(accountId = 0L) }
        assertThrows(IllegalArgumentException::class.java) { total(childId = -1L) }
    }

    @Test
    fun aBlankPackageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { app(packageName = "   ") }
    }

    @Test
    fun evaluationsAreDeterministic() {
        limits.set(1L, 10L, LimitScope.TOTAL, null, 120)
        usage.set(1L, 10L, day, youtube, 70 * minute)

        assertEquals(total(), total())
    }
}
