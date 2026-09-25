package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageInterval

/**
 * Phase 4 Step 1B-3: the accounting service against the real v7 database.
 *
 * The pure maths is covered on the JVM (`UsageAccountingTest`); this proves the
 * service actually wires that maths to Room: what it plans is what gets persisted, a
 * re-reported/overlapping batch is not double counted, and nothing leaks across
 * accounts, children or days.
 *
 * A fixed [ZoneOffset.UTC] is injected so the midnight cases are deterministic.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeUsageAccountingTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ScreenTimeUsageRepository
    private lateinit var accounting: ScreenTimeUsageAccounting

    private val zone = ZoneOffset.UTC
    private val minute = 60_000L
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val otherDay = "2026-09-26"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        accounting = ScreenTimeUsageAccounting(repository, zone)
    }

    @After
    fun tearDown() = db.close()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun interval(
        startIso: String,
        elapsedMs: Long,
        pkg: String = youtube,
        category: AppCategory = AppCategory.VIDEO,
    ) = UsageInterval.of(pkg, category, at(startIso), elapsedMs)

    // ---- writes -------------------------------------------------------------

    @Test
    fun accountingWritesExpectedDelta() = runBlocking {
        val span = interval("2026-09-25T10:00:00Z", 5 * minute)

        val written = accounting.recordIntervals(1L, 10L, listOf(span))

        assertEquals(1, accounting.planDeltas(listOf(span)).size)
        assertEquals(5 * minute, repository.getAppUsageMs(1L, 10L, "2026-09-25", youtube))
        assertEquals(5 * minute, repository.getTotalUsageMs(1L, 10L, "2026-09-25"))
        assertEquals("2026-09-25", accounting.planDeltas(listOf(span)).single().dateKey)
        assertEquals("accounting wrote exactly the planned delta", 1, written.size)
    }

    @Test
    fun recordIntervalAccountsForASingleSpan() = runBlocking {
        accounting.recordInterval(1L, 10L, interval("2026-09-25T10:00:00Z", 2 * minute))

        assertEquals(2 * minute, accounting.usageFor(1L, 10L, "2026-09-25").totalMs)
    }

    @Test
    fun planDeltasDoesNotWriteAnything() = runBlocking {
        accounting.planDeltas(listOf(interval("2026-09-25T10:00:00Z", 5 * minute)))

        assertEquals(0L, repository.getTotalUsageMs(1L, 10L, "2026-09-25"))
    }

    @Test
    fun repeatedIdenticalIntervalsInOneBatchDoNotDoubleCount() = runBlocking {
        val span = interval("2026-09-25T10:00:00Z", 5 * minute)

        accounting.recordIntervals(1L, 10L, listOf(span, span, span))

        assertEquals(5 * minute, repository.getAppUsageMs(1L, 10L, "2026-09-25", youtube))
    }

    @Test
    fun overlappingIntervalsInOneBatchAreCountedOnce() = runBlocking {
        // 10:00-10:10 and 10:05-10:15 -> 15 minutes of real usage, not 20.
        accounting.recordIntervals(
            1L,
            10L,
            listOf(
                interval("2026-09-25T10:00:00Z", 10 * minute),
                interval("2026-09-25T10:05:00Z", 10 * minute),
            ),
        )

        assertEquals(15 * minute, repository.getTotalUsageMs(1L, 10L, "2026-09-25"))
    }

    @Test
    fun multipleIntervalsAccumulateCorrectly() = runBlocking {
        // Disjoint spans for two apps: 10 + 5 minutes.
        val written = accounting.recordIntervals(
            1L,
            10L,
            listOf(
                interval("2026-09-25T10:00:00Z", 10 * minute),
                interval("2026-09-25T11:00:00Z", 5 * minute, pkg = tiktok, category = AppCategory.SOCIAL),
            ),
        )

        assertEquals(2, written.size)
        assertEquals(10 * minute, repository.getAppUsageMs(1L, 10L, "2026-09-25", youtube))
        assertEquals(5 * minute, repository.getAppUsageMs(1L, 10L, "2026-09-25", tiktok))
        assertEquals(15 * minute, repository.getTotalUsageMs(1L, 10L, "2026-09-25"))
        assertEquals(
            10 * minute,
            repository.getCategoryUsageMs(1L, 10L, "2026-09-25", AppCategory.VIDEO),
        )
    }

    @Test
    fun invalidIntervalNeverWritesUsage() = runBlocking {
        val start = at("2026-09-25T10:00:00Z")

        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of(youtube, AppCategory.VIDEO, start, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of(youtube, AppCategory.VIDEO, start, -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of(
                youtube,
                AppCategory.VIDEO,
                start,
                ScreenTimeUsageRepository.MAX_DELTA_MS + 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval(youtube, AppCategory.VIDEO, start, start - 1L)
        }

        assertEquals("nothing invalid may have reached the database", 0L, repository.getTotalUsageMs(1L, 10L, "2026-09-25"))
    }

    // ---- date boundary ------------------------------------------------------

    @Test
    fun crossMidnightIntervalWritesTwoDays() = runBlocking {
        // 23:59:30 -> 00:00:30 UTC: 30s on each day.
        val written = accounting.recordIntervals(1L, 10L, listOf(interval("2026-09-25T23:59:30Z", minute)))

        assertEquals(2, written.size)
        assertEquals(30_000L, repository.getAppUsageMs(1L, 10L, "2026-09-25", youtube))
        assertEquals(30_000L, repository.getAppUsageMs(1L, 10L, otherDay, youtube))
        assertEquals("the two days add up to the whole span", minute, written.sumOf { it.elapsedMs })
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun accountIsolationThroughTheService() = runBlocking {
        accounting.recordInterval(1L, 10L, interval("2026-09-25T10:00:00Z", minute))
        accounting.recordInterval(2L, 10L, interval("2026-09-25T10:00:00Z", 2 * minute))

        assertEquals(minute, repository.getTotalUsageMs(1L, 10L, "2026-09-25"))
        assertEquals(2 * minute, repository.getTotalUsageMs(2L, 10L, "2026-09-25"))
        assertEquals(1, accounting.usageFor(1L, 10L, "2026-09-25").apps.size)
    }

    @Test
    fun childIsolationThroughTheService() = runBlocking {
        accounting.recordInterval(1L, 10L, interval("2026-09-25T10:00:00Z", minute))
        accounting.recordInterval(1L, 11L, interval("2026-09-25T10:00:00Z", 2 * minute))

        assertEquals(minute, accounting.usageFor(1L, 10L, "2026-09-25").totalMs)
        assertEquals(2 * minute, accounting.usageFor(1L, 11L, "2026-09-25").totalMs)
    }

    @Test
    fun dateIsolationThroughTheService() = runBlocking {
        accounting.recordInterval(1L, 10L, interval("2026-09-25T10:00:00Z", minute))
        accounting.recordInterval(1L, 10L, interval("2026-09-26T10:00:00Z", 2 * minute))

        assertEquals(minute, accounting.usageFor(1L, 10L, "2026-09-25").totalMs)
        assertEquals(2 * minute, accounting.usageFor(1L, 10L, otherDay).totalMs)
        assertEquals(0L, accounting.usageFor(1L, 10L, "2026-09-27").totalMs)
    }

    // ---- read back ----------------------------------------------------------

    @Test
    fun usageForReturnsThePersistedBreakdown() = runBlocking {
        accounting.recordIntervals(
            1L,
            10L,
            listOf(
                interval("2026-09-25T10:00:00Z", 10 * minute),
                interval("2026-09-25T11:00:00Z", 20 * minute, pkg = tiktok, category = AppCategory.SOCIAL),
            ),
        )

        val usage = accounting.usageFor(1L, 10L, "2026-09-25")

        assertEquals(10L, usage.childId)
        assertEquals("2026-09-25", usage.dateKey)
        assertEquals(30 * minute, usage.totalMs)
        assertEquals(10 * minute, usage.usedMsFor(youtube))
        assertEquals(20 * minute, usage.usedMsFor(tiktok))
        assertEquals(20 * minute, usage.usedMsFor(AppCategory.SOCIAL))
        assertEquals(listOf(youtube, tiktok), usage.apps.map { it.packageName })
    }

    @Test
    fun anEmptyDayIsZeroUsageNotAnError() = runBlocking {
        val usage = accounting.usageFor(1L, 10L, "2026-09-25")

        assertEquals(0L, usage.totalMs)
        assertEquals(emptyList<String>(), usage.apps.map { it.packageName })
    }
}
