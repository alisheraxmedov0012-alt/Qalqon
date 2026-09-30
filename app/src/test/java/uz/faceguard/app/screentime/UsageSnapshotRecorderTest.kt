package uz.faceguard.app.screentime

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshot
import uz.faceguard.app.domain.screentime.UsageSnapshotComparison
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine
import uz.faceguard.app.domain.screentime.UsageSnapshotRecorder

/**
 * Phase 4 Step 1B-5 (pure JVM): the orchestration seam.
 *
 * The repository is a recording fake, which is valid here because
 * [ScreenTimeUsageRepository] is a *domain* interface — this proves the orchestration
 * (what it writes, and what it refuses to write) with no Android and no Room in the way.
 * The real database path is exercised separately on a device by
 * `UsageSnapshotRecorderInstrumentedTest`.
 */
class UsageSnapshotRecorderTest {

    private val zone = ZoneOffset.UTC
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"

    /** One write, captured verbatim. */
    private data class Write(
        val accountId: Long,
        val childId: Long,
        val dateKey: String,
        val packageName: String,
        val category: AppCategory,
        val deltaMs: Long,
    )

    private class RecordingRepository : ScreenTimeUsageRepository {
        val writes = mutableListOf<Write>()

        override suspend fun addUsage(
            accountId: Long,
            childId: Long,
            dateKey: String,
            packageName: String,
            category: AppCategory,
            deltaMs: Long,
        ) {
            require(deltaMs > 0L) { "the repository must never receive a non-positive delta, got $deltaMs" }
            writes += Write(accountId, childId, dateKey, packageName, category, deltaMs)
        }

        override suspend fun getAppUsageMs(accountId: Long, childId: Long, dateKey: String, packageName: String) = 0L
        override suspend fun getCategoryUsageMs(accountId: Long, childId: Long, dateKey: String, category: AppCategory) = 0L
        override suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String) = 0L
        override suspend fun getDayUsage(accountId: Long, childId: Long, dateKey: String) = emptyList<AppUsage>()
        override fun observeDayUsage(accountId: Long, childId: Long, dateKey: String): Flow<List<AppUsage>> =
            flowOf(emptyList())
    }

    private val repository = RecordingRepository()
    private val accounting = ScreenTimeUsageAccounting(repository, zone)
    private val recorder = UsageSnapshotRecorder(accounting, UsageSnapshotDeltaEngine(zone))

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun window(startIso: String = "2026-09-25T00:00:00Z", endIso: String = "2026-09-26T00:00:00Z") =
        UsageRange(at(startIso), at(endIso))

    private fun snapshot(vararg samples: Pair<String, Long>, range: UsageRange = window()) =
        UsageSnapshot.of(range, samples.map { (pkg, ms) -> AppUsageSample(pkg, ms) })

    private fun record(previous: UsageSnapshot?, current: UsageSnapshot) = runBlocking {
        recorder.record(
            accountId = 1L,
            childId = 10L,
            previous = previous,
            current = current,
            categoryOf = AppCategories::categoryFor,
        )
    }

    // ---- writing ------------------------------------------------------------

    @Test
    fun theAccruedDeltaReachesTheRepositoryWithTheCallersIdentity() {
        record(snapshot(youtube to 10_000L), snapshot(youtube to 15_000L))

        assertEquals(
            listOf(Write(1L, 10L, "2026-09-25", youtube, AppCategory.VIDEO, 5_000L)),
            repository.writes,
        )
    }

    @Test
    fun accountAndChildComeFromTheCallerAndAreNeverInferred() {
        runBlocking {
            recorder.record(7L, 42L, snapshot(youtube to 1_000L), snapshot(youtube to 3_000L), AppCategories::categoryFor)
        }

        assertEquals(7L, repository.writes.single().accountId)
        assertEquals(42L, repository.writes.single().childId)
    }

    @Test
    fun categoryComesFromTheCallerMappingAndIsNotInventedHere() {
        runBlocking {
            recorder.record(
                accountId = 1L,
                childId = 10L,
                previous = snapshot(youtube to 1_000L),
                current = snapshot(youtube to 3_000L),
                categoryOf = { AppCategory.EDUCATION },
            )
        }

        assertEquals(AppCategory.EDUCATION, repository.writes.single().category)
    }

    @Test
    fun multiplePackagesAreEachWrittenUnderTheirOwnIdentity() {
        record(
            snapshot(youtube to 10_000L, tiktok to 1_000L),
            snapshot(youtube to 15_000L, tiktok to 4_000L),
        )

        assertEquals(
            listOf(
                Write(1L, 10L, "2026-09-25", youtube, AppCategory.VIDEO, 5_000L),
                Write(1L, 10L, "2026-09-25", tiktok, AppCategory.SOCIAL, 3_000L),
            ),
            repository.writes.sortedBy { it.packageName },
        )
    }

    // ---- nothing may be written --------------------------------------------

    @Test
    fun zeroDeltaIsANoOpWithNoWriteAtAll() {
        val outcome = record(snapshot(youtube to 10_000L), snapshot(youtube to 10_000L))

        assertTrue(outcome is UsageSnapshotComparison.Compared)
        assertEquals("an unchanged counter must not produce a phantom row", 0, repository.writes.size)
    }

    @Test
    fun theFirstObservationWritesNothing() {
        record(null, snapshot(youtube to 10_000L, tiktok to 5_000L))

        assertEquals("a baseline is not usage", 0, repository.writes.size)
    }

    @Test
    fun aNewPackageWritesNothingForItsBaseline() {
        record(snapshot(youtube to 10_000L), snapshot(youtube to 15_000L, tiktok to 9_000L))

        assertEquals(listOf(youtube), repository.writes.map { it.packageName })
    }

    @Test
    fun aDifferentObservationWindowWritesNothing() {
        val outcome = record(
            snapshot(youtube to 30_000L, range = window(endIso = "2026-09-25T12:00:00Z")),
            snapshot(youtube to 90_000L, range = window()),
        )

        assertEquals(UsageSnapshotComparison.DifferentObservationWindow, outcome)
        assertEquals("an incomparable pair must not move usage", 0, repository.writes.size)
    }

    @Test
    fun aDecreasingCounterNeverWritesNegativeUsage() {
        record(snapshot(youtube to 6_000_000L), snapshot(youtube to 1_200_000L))

        assertTrue("no write may ever be non-positive", repository.writes.all { it.deltaMs > 0L })
        assertEquals(1_200_000L, repository.writes.single().deltaMs)
    }

    // ---- repeated observation ----------------------------------------------

    @Test
    fun repeatedObservationsAccumulateOnlyWhatAccrued() {
        record(null, snapshot(youtube to 10_000L))
        record(snapshot(youtube to 10_000L), snapshot(youtube to 25_000L))
        record(snapshot(youtube to 25_000L), snapshot(youtube to 25_000L))
        record(snapshot(youtube to 25_000L), snapshot(youtube to 40_000L))

        assertEquals(listOf(15_000L, 15_000L), repository.writes.map { it.deltaMs })
        assertEquals("only the two real increases were accounted", 30_000L, repository.writes.sumOf { it.deltaMs })
    }
}
