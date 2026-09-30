package uz.faceguard.app.data.usage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.repository.ScreenTimeUsageRepositoryImpl
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshot
import uz.faceguard.app.domain.screentime.UsageSnapshotComparison
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine
import uz.faceguard.app.domain.screentime.UsageSnapshotRecorder

/**
 * Phase 4 Step 1B-5: the whole snapshot -> delta -> accounting -> Room path, with the real
 * database.
 *
 * The pure semantics are covered on the JVM (`UsageSnapshotDeltaEngineTest`,
 * `UsageSnapshotRecorderTest`); this proves the orchestration actually persists what it
 * claims through the real repository and schema, and that nothing invalid can land in
 * `daily_app_usage`.
 *
 * The zone is fixed and the snapshots are synthetic, so no Usage Access permission and no
 * device usage is involved — this test does not depend on the Step 1B-4 limitation.
 */
@RunWith(AndroidJUnit4::class)
class UsageSnapshotRecorderInstrumentedTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ScreenTimeUsageRepository
    private lateinit var recorder: UsageSnapshotRecorder

    private val zone = ZoneOffset.UTC
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val dateKey = "2026-09-25"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        recorder = UsageSnapshotRecorder(
            ScreenTimeUsageAccounting(repository, zone),
            UsageSnapshotDeltaEngine(zone),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun window(startIso: String = "2026-09-25T00:00:00Z", endIso: String = "2026-09-26T00:00:00Z") =
        UsageRange(at(startIso), at(endIso))

    private fun snapshot(vararg samples: Pair<String, Long>, range: UsageRange = window()) =
        UsageSnapshot.of(range, samples.map { (pkg, ms) -> AppUsageSample(pkg, ms) })

    private fun record(previous: UsageSnapshot?, current: UsageSnapshot, accountId: Long = 1L, childId: Long = 10L) =
        runBlocking {
            recorder.record(accountId, childId, previous, current, AppCategories::categoryFor)
        }

    // ---- the happy path -----------------------------------------------------

    @Test
    fun theAccruedDeltaIsPersistedUnderTheCallersIdentity() = runBlocking {
        record(snapshot(youtube to 600_000L), snapshot(youtube to 1_500_000L))

        assertEquals(900_000L, repository.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(900_000L, repository.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals(
            "the caller's mapping decided the category",
            AppCategories.categoryFor(youtube).name,
            repository.getDayUsage(1L, 10L, dateKey).single().category.name,
        )
    }

    @Test
    fun repeatedObservationsOnlyAccountForWhatAccrued() = runBlocking {
        record(null, snapshot(youtube to 600_000L))
        record(snapshot(youtube to 600_000L), snapshot(youtube to 900_000L))
        record(snapshot(youtube to 900_000L), snapshot(youtube to 900_000L))
        record(snapshot(youtube to 900_000L), snapshot(youtube to 1_200_000L))

        assertEquals(
            "the baseline plus the unchanged look contribute nothing",
            600_000L,
            repository.getAppUsageMs(1L, 10L, dateKey, youtube),
        )
    }

    // ---- nothing is written when it should not be ---------------------------

    @Test
    fun theFirstObservationPersistsNothing() = runBlocking {
        val outcome = record(null, snapshot(youtube to 600_000L, tiktok to 120_000L))

        assertTrue(outcome is UsageSnapshotComparison.Compared)
        assertEquals("a baseline is not usage", 0L, repository.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(0L, repository.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals("and it created no row at all", 0, repository.getDayUsage(1L, 10L, dateKey).size)
    }

    @Test
    fun anUnchangedObservationPersistsNothing() = runBlocking {
        record(null, snapshot(youtube to 600_000L))
        record(snapshot(youtube to 600_000L), snapshot(youtube to 600_000L))

        assertEquals("no phantom row for zero usage", 0L, repository.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(0, repository.getDayUsage(1L, 10L, dateKey).size)
    }

    @Test
    fun aDifferentObservationWindowPersistsNothing() = runBlocking {
        val outcome = record(
            snapshot(youtube to 600_000L, range = window(endIso = "2026-09-25T12:00:00Z")),
            snapshot(youtube to 1_800_000L, range = window()),
        )

        assertEquals(UsageSnapshotComparison.DifferentObservationWindow, outcome)
        assertEquals("an incomparable pair must not move usage", 0L, repository.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(0, repository.getDayUsage(1L, 10L, dateKey).size)
    }

    // ---- reset / isolation --------------------------------------------------

    @Test
    fun aDecreasingCounterPersistsPositiveUsageOnly() = runBlocking {
        record(snapshot(youtube to 6_000_000L), snapshot(youtube to 1_200_000L))

        val stored = repository.getAppUsageMs(1L, 10L, dateKey, youtube)
        assertEquals("the reset baseline is accounted, not subtracted", 1_200_000L, stored)
        assertTrue("usage can never be negative", stored >= 0L)
    }

    @Test
    fun accountAndChildStayIsolatedThroughTheWholePipeline() = runBlocking {
        record(snapshot(youtube to 600_000L), snapshot(youtube to 900_000L), accountId = 1L, childId = 10L)
        record(snapshot(youtube to 600_000L), snapshot(youtube to 1_500_000L), accountId = 1L, childId = 11L)
        record(snapshot(youtube to 600_000L), snapshot(youtube to 2_100_000L), accountId = 2L, childId = 10L)

        assertEquals(300_000L, repository.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals(900_000L, repository.getTotalUsageMs(1L, 11L, dateKey))
        assertEquals(1_500_000L, repository.getTotalUsageMs(2L, 10L, dateKey))
    }

    @Test
    fun eachPackageKeepsItsOwnIdentityEndToEnd() = runBlocking {
        record(
            snapshot(youtube to 600_000L, tiktok to 60_000L),
            snapshot(youtube to 900_000L, tiktok to 180_000L),
        )

        assertEquals(300_000L, repository.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(120_000L, repository.getAppUsageMs(1L, 10L, dateKey, tiktok))
        assertEquals(420_000L, repository.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals(
            "one row per package, never merged",
            listOf(youtube, tiktok),
            repository.getDayUsage(1L, 10L, dateKey).map { it.packageName },
        )
    }
}
