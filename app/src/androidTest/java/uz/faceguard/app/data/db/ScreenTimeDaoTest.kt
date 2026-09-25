package uz.faceguard.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 Step 1A/1B-1: real Room tests for the screen-time entities and DAOs.
 *
 * These run against the production [FaceGuardDatabase] (now v7 with the screen-time
 * tables registered), so the DAOs are exercised on the same schema that ships.
 */

@RunWith(AndroidJUnit4::class)
class ScreenTimeDaoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FaceGuardDatabase
    private lateinit var usage: DailyAppUsageDao
    private lateinit var limits: ChildScreenTimeLimitDao

    private val day = "2026-09-24"
    private val otherDay = "2026-09-25"
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries().build()
        usage = db.dailyAppUsageDao()
        limits = db.childScreenTimeLimitDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(
        accountId: Long = 1L,
        childId: Long = 10L,
        dateKey: String = day,
        pkg: String = youtube,
        usedMs: Long = 1_000L,
        category: String = "VIDEO",
    ) = usage.insertIfAbsent(
        DailyAppUsageEntity(accountId, childId, dateKey, pkg, usedMs, category, 1L),
    )

    // ---- usage --------------------------------------------------------------

    @Test
    fun aRowIsStoredAndReadBackScopedByOwnerAndDay() = runBlocking {
        insert()

        val loaded = usage.packageUsage(1L, 10L, day, youtube)

        assertNotNull(loaded)
        assertEquals(1_000L, loaded!!.usedMs)
        assertEquals("VIDEO", loaded.category)
        assertEquals(1, usage.dayUsage(1L, 10L, day).size)
        assertEquals(1_000L, usage.totalUsedMs(1L, 10L, day))
    }

    @Test
    fun anAbsentRowIsNullAndZeroNotAnError() = runBlocking {
        assertNull(usage.packageUsage(1L, 10L, day, youtube))
        // SQL SUM() over no rows is NULL, not 0: the DAO mirrors SQL honestly and the
        // repository layer (Step 1B) coalesces this to 0 for the domain.
        assertNull(usage.totalUsedMs(1L, 10L, day))
        assertTrue(usage.dayUsage(1L, 10L, day).isEmpty())
    }

    @Test
    fun theCompositeKeyMakesInsertIfAbsentIdempotent() = runBlocking {
        insert(usedMs = 1_000L)

        val secondInsert = insert(usedMs = 9_999L)

        assertEquals("the duplicate insert must be ignored", -1L, secondInsert)
        assertEquals(1_000L, usage.packageUsage(1L, 10L, day, youtube)!!.usedMs)
        assertEquals(1, usage.dayUsage(1L, 10L, day).size)
    }

    @Test
    fun theIncrementIsAtomicAndAccumulates() = runBlocking {
        insert(usedMs = 1_000L)

        usage.incrementUsage(1L, 10L, day, youtube, 500L, 2L)
        usage.incrementUsage(1L, 10L, day, youtube, 700L, 3L)

        assertEquals("1000 + 500 + 700", 2_200L, usage.packageUsage(1L, 10L, day, youtube)!!.usedMs)
        assertEquals(1, usage.dayUsage(1L, 10L, day).size)
    }

    @Test
    fun repeatedIncrementsNeverLoseUsage() = runBlocking {
        insert(usedMs = 0L)

        repeat(100) { usage.incrementUsage(1L, 10L, day, youtube, 1_000L, it.toLong()) }

        assertEquals(100_000L, usage.packageUsage(1L, 10L, day, youtube)!!.usedMs)
    }

    @Test
    fun aNegativeDeltaCannotReduceUsageBelowZero() = runBlocking {
        insert(usedMs = 1_000L)

        val notReduced = usage.incrementUsage(1L, 10L, day, youtube, -5_000L, 2L)

        assertEquals("the guarded UPDATE must affect no row", 0, notReduced)
        assertEquals(1_000L, usage.packageUsage(1L, 10L, day, youtube)!!.usedMs)
    }

    @Test
    fun incrementsForAnAbsentRowAreReportedAsZeroRowsUpdated() = runBlocking {
        assertEquals(0, usage.incrementUsage(1L, 10L, day, youtube, 1_000L, 1L))
        assertNull(usage.packageUsage(1L, 10L, day, youtube))
    }

    @Test
    fun usageIsIsolatedByAccountAndChild() = runBlocking {
        insert(accountId = 1L, childId = 10L, usedMs = 60_000L)
        insert(accountId = 1L, childId = 11L, usedMs = 20_000L)
        insert(accountId = 2L, childId = 10L, usedMs = 5_000L)

        assertEquals(60_000L, usage.packageUsage(1L, 10L, day, youtube)!!.usedMs)
        assertEquals(20_000L, usage.packageUsage(1L, 11L, day, youtube)!!.usedMs)
        assertEquals(5_000L, usage.packageUsage(2L, 10L, day, youtube)!!.usedMs)
        assertNull("account 2 must not see account 1's child", usage.packageUsage(2L, 11L, day, youtube))
        assertEquals(1, usage.dayUsage(1L, 10L, day).size)
    }

    @Test
    fun usageIsIsolatedByDate() = runBlocking {
        insert(dateKey = day, usedMs = 60_000L)
        insert(dateKey = otherDay, usedMs = 5_000L)

        assertEquals(60_000L, usage.totalUsedMs(1L, 10L, day))
        assertEquals(5_000L, usage.totalUsedMs(1L, 10L, otherDay))
        assertEquals(1, usage.dayUsage(1L, 10L, day).size)
    }

    @Test
    fun usageIsIsolatedByPackage() = runBlocking {
        insert(pkg = youtube, usedMs = 60_000L)
        insert(pkg = tiktok, usedMs = 30_000L, category = "SOCIAL")

        assertEquals(60_000L, usage.packageUsage(1L, 10L, day, youtube)!!.usedMs)
        assertEquals(30_000L, usage.packageUsage(1L, 10L, day, tiktok)!!.usedMs)
        assertEquals(2, usage.dayUsage(1L, 10L, day).size)
        assertEquals(90_000L, usage.totalUsedMs(1L, 10L, day))
    }

    @Test
    fun categoryAggregationOnlySumsTheMatchingCategory() = runBlocking {
        insert(pkg = youtube, usedMs = 60_000L, category = "VIDEO")
        insert(pkg = tiktok, usedMs = 30_000L, category = "SOCIAL")
        insert(pkg = "com.example.mygame", usedMs = 20_000L, category = "GAMES")

        assertEquals(60_000L, usage.categoryUsedMs(1L, 10L, day, "VIDEO"))
        assertEquals(20_000L, usage.categoryUsedMs(1L, 10L, day, "GAMES"))
        assertNull("a category with no rows aggregates to null", usage.categoryUsedMs(1L, 10L, day, "EDUCATION"))
    }

    @Test
    fun theObservedDayStreamIsScopedAndReflectsIncrements() = runBlocking {
        insert(childId = 10L, usedMs = 1_000L)
        insert(childId = 11L, usedMs = 9_999L)

        assertEquals(1_000L, usage.observeDay(1L, 10L, day).first().single().usedMs)

        usage.incrementUsage(1L, 10L, day, youtube, 500L, 2L)

        assertEquals(1_500L, usage.observeDay(1L, 10L, day).first().single().usedMs)
    }

    @Test
    fun deletingASpecificDayLeavesOtherDaysIntact() = runBlocking {
        insert(dateKey = day, usedMs = 60_000L)
        insert(dateKey = otherDay, usedMs = 5_000L)

        usage.deleteDay(1L, 10L, day)

        assertNull(usage.packageUsage(1L, 10L, day, youtube))
        assertEquals(5_000L, usage.totalUsedMs(1L, 10L, otherDay))
    }

    @Test
    fun deletingOneChildOrAccountLeavesTheRestIntact() = runBlocking {
        insert(childId = 10L, usedMs = 60_000L)
        insert(childId = 11L, usedMs = 20_000L)
        insert(accountId = 2L, childId = 10L, usedMs = 5_000L)

        usage.deleteChildUsage(1L, 10L)
        assertNull(usage.packageUsage(1L, 10L, day, youtube))
        assertEquals(20_000L, usage.packageUsage(1L, 11L, day, youtube)!!.usedMs)

        usage.deleteAccountUsage(1L)
        assertNull(usage.packageUsage(1L, 11L, day, youtube))
        assertEquals(5_000L, usage.packageUsage(2L, 10L, day, youtube)!!.usedMs)
    }

    // ---- limits -------------------------------------------------------------

    private suspend fun totalLimit(accountId: Long = 1L, childId: Long = 10L, minutes: Int? = 120) =
        limits.upsert(ChildScreenTimeLimitEntity(accountId, childId, "TOTAL", "", minutes, 1L))

    private suspend fun categoryLimit(
        accountId: Long = 1L,
        childId: Long = 10L,
        category: String = "EDUCATION",
        minutes: Int? = 60,
    ) = limits.upsert(ChildScreenTimeLimitEntity(accountId, childId, "CATEGORY", category, minutes, 1L))

    @Test
    fun totalAndCategoryLimitsCoexistAndStayDistinguishable() = runBlocking {
        totalLimit(minutes = 120)
        categoryLimit(minutes = 60)

        assertEquals(120, limits.limit(1L, 10L, "TOTAL", "")!!.limitMinutes)
        assertEquals(60, limits.limit(1L, 10L, "CATEGORY", "EDUCATION")!!.limitMinutes)
        assertEquals(2, limits.limits(1L, 10L).size)
        assertNull("TOTAL is not a category row", limits.limit(1L, 10L, "TOTAL", "EDUCATION"))
        assertNull("a category limit is not TOTAL", limits.limit(1L, 10L, "CATEGORY", ""))
    }

    @Test
    fun anExistingLimitIsReplacedNotDuplicated() = runBlocking {
        totalLimit(minutes = 120)
        totalLimit(minutes = 90)
        totalLimit(minutes = null)

        assertNull(limits.limit(1L, 10L, "TOTAL", "")!!.limitMinutes)
        assertEquals("TOTAL is a single row per child", 1, limits.limits(1L, 10L).size)
    }

    @Test
    fun limitsAreIsolatedByAccountAndChild() = runBlocking {
        totalLimit(accountId = 1L, childId = 10L, minutes = 120)

        assertNull(limits.limit(1L, 11L, "TOTAL", ""))
        assertNull(limits.limit(2L, 10L, "TOTAL", ""))
        assertTrue(limits.limits(1L, 11L).isEmpty())
        assertTrue(limits.limits(2L, 10L).isEmpty())
    }

    @Test
    fun deletingALimitOnlyAffectsTheIntendedScope() = runBlocking {
        totalLimit(minutes = 120)
        categoryLimit(category = "GAMES", minutes = 60)
        categoryLimit(category = "EDUCATION", minutes = 30)

        limits.delete(1L, 10L, "CATEGORY", "GAMES")

        assertNull(limits.limit(1L, 10L, "CATEGORY", "GAMES"))
        assertNotNull(limits.limit(1L, 10L, "CATEGORY", "EDUCATION"))
        assertNotNull(limits.limit(1L, 10L, "TOTAL", ""))
    }

    @Test
    fun limitsCanBeObservedAndReset() = runBlocking {
        totalLimit(minutes = 120)

        assertEquals(1, limits.observeLimits(1L, 10L).first().size)

        limits.deleteChildLimits(1L, 10L)

        assertTrue(limits.observeLimits(1L, 10L).first().isEmpty())
    }
}
