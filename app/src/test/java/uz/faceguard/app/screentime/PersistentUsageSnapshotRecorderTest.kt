package uz.faceguard.app.screentime

import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.PersistentUsageSnapshotRecorder
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.SnapshotAccountingOutcome
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageAccountingTransaction
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpoint
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpointRepository
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-6 (pure JVM): snapshot ingestion backed by a checkpoint.
 *
 * The device, the database and the transaction are all fakes here — `AppUsageSource`,
 * `UsageSnapshotCheckpointRepository` and `UsageAccountingTransaction` are *domain*
 * interfaces, so this exercises the real orchestration with no Android and no Room. The
 * real database path is covered on a device by `PersistentUsageSnapshotRecorderTest`
 * (instrumented).
 */
class PersistentUsageSnapshotRecorderTest {

    private val zone = ZoneOffset.UTC
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    /** 2026-09-25 UTC in milliseconds. */
    private val dayStart = 1_790_294_400_000L
    private val dayEnd = 1_790_380_800_000L
    private val nextDayStart = 1_790_380_800_000L
    private val nextDayEnd = 1_790_467_200_000L

    private fun window(start: Long = dayStart, end: Long = dayEnd) = UsageRange(start, end)

    // ---- fakes --------------------------------------------------------------

    private class FakeSource(
        private val access: UsageAccessState,
        private var samples: List<AppUsageSample>,
    ) : AppUsageSource {
        override fun usageAccess(): UsageAccessState = access

        fun report(vararg samples: Pair<String, Long>) {
            this.samples = samples.map { (pkg, ms) -> AppUsageSample(pkg, ms) }
        }

        override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult =
            if (access == UsageAccessState.AVAILABLE) {
                AppUsageQueryResult.Available(samples)
            } else {
                AppUsageQueryResult.Unavailable
            }
    }

    /** A keyed store, which is what makes the identity rules observable. */
    private class FakeCheckpointStore : UsageSnapshotCheckpointRepository {
        val rows = LinkedHashMap<String, UsageSnapshotCheckpoint>()

        /** Invoked before every write, so a test can prove where the write happened. */
        var onWrite: (() -> Unit)? = null

        private fun keyOf(accountId: Long, childId: Long, source: UsageSourceId, range: UsageRange, pkg: String) =
            "$accountId|$childId|${source.name}|${range.startTimeMs}|${range.endTimeMs}|$pkg"

        override suspend fun checkpoint(
            accountId: Long,
            childId: Long,
            source: UsageSourceId,
            range: UsageRange,
            packageName: String,
        ): UsageSnapshotCheckpoint? = rows[keyOf(accountId, childId, source, range, packageName)]

        override suspend fun checkpointsForWindow(
            accountId: Long,
            childId: Long,
            source: UsageSourceId,
            range: UsageRange,
        ): List<UsageSnapshotCheckpoint> = rows.values.filter {
            it.accountId == accountId && it.childId == childId && it.source == source && it.range == range
        }

        override suspend fun save(checkpoints: List<UsageSnapshotCheckpoint>) {
            onWrite?.invoke()
            checkpoints.forEach { rows[keyOf(it.accountId, it.childId, it.source, it.range, it.packageName)] = it }
        }

        override suspend fun delete(
            accountId: Long,
            childId: Long,
            source: UsageSourceId,
            range: UsageRange,
            packageName: String,
        ) {
            onWrite?.invoke()
            rows.remove(keyOf(accountId, childId, source, range, packageName))
        }

        override suspend fun deleteForChild(accountId: Long, childId: Long) {
            onWrite?.invoke()
            rows.values.removeAll { it.accountId == accountId && it.childId == childId }
        }

        override suspend fun deleteForAccount(accountId: Long) {
            onWrite?.invoke()
            rows.values.removeAll { it.accountId == accountId }
        }

        fun stored(
            accountId: Long,
            childId: Long,
            pkg: String,
            range: UsageRange = UsageRange(DAY_START, DAY_END),
        ): UsageSnapshotCheckpoint? = rows[keyOf(accountId, childId, UsageSourceId.USAGE_STATS, range, pkg)]

        companion object {
            const val DAY_START = 1_790_294_400_000L
            const val DAY_END = 1_790_380_800_000L
        }
    }

    private class RecordingUsageRepository : ScreenTimeUsageRepository {
        data class Write(
            val accountId: Long,
            val childId: Long,
            val dateKey: String,
            val packageName: String,
            val deltaMs: Long,
        )

        val writes = mutableListOf<Write>()

        /** Invoked before every write, so a test can prove where the write happened. */
        var onWrite: (() -> Unit)? = null

        override suspend fun addUsage(
            accountId: Long,
            childId: Long,
            dateKey: String,
            packageName: String,
            category: AppCategory,
            deltaMs: Long,
        ) {
            require(deltaMs > 0L) { "a non-positive delta must never reach the repository, got $deltaMs" }
            onWrite?.invoke()
            writes += Write(accountId, childId, dateKey, packageName, deltaMs)
        }

        override suspend fun getAppUsageMs(accountId: Long, childId: Long, dateKey: String, packageName: String) = 0L
        override suspend fun getCategoryUsageMs(accountId: Long, childId: Long, dateKey: String, category: AppCategory) = 0L
        override suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String) = 0L
        override suspend fun getDayUsage(accountId: Long, childId: Long, dateKey: String) = emptyList<AppUsage>()
        override fun observeDayUsage(accountId: Long, childId: Long, dateKey: String): Flow<List<AppUsage>> =
            flowOf(emptyList())
    }

    /**
     * A pass-through transaction that exposes its depth, so the "both writes are one unit"
     * property is observable on the JVM: the fakes can report whether a write happened inside.
     */
    private class TrackingTransaction : UsageAccountingTransaction {
        var depth = 0
            private set
        var entered = 0
            private set

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            depth++
            entered++
            try {
                return block()
            } finally {
                depth--
            }
        }
    }

    private val usageRepository = RecordingUsageRepository()
    private val store = FakeCheckpointStore()
    private val transaction = TrackingTransaction()

    private fun recorder(source: FakeSource, observedAtMs: Long = 1_790_295_000_000L) =
        PersistentUsageSnapshotRecorder(
            source = source,
            engine = UsageSnapshotDeltaEngine(zone),
            checkpointRepository = store,
            accounting = ScreenTimeUsageAccounting(usageRepository, zone),
            transaction = transaction,
            sourceId = UsageSourceId.USAGE_STATS,
            clock = { observedAtMs },
        )

    private fun ingest(source: FakeSource, accountId: Long = 1L, childId: Long = 10L, recorder: PersistentUsageSnapshotRecorder) =
        runBlocking { recorder.ingest(accountId, childId, window(), AppCategories::categoryFor) }

    // ---- the core semantics -------------------------------------------------

    @Test
    fun firstObservationEstablishesABaselineAndAccountsForNothing() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList()).apply { report(youtube to 50_000L) }

        val outcome = ingest(source, recorder = recorder(source))

        assertEquals(
            SnapshotAccountingOutcome.Accounted("2026-09-25", packagesObserved = 1, deltas = emptyList()),
            outcome,
        )
        assertEquals("a platform counter is not screen time", 0, usageRepository.writes.size)
        assertEquals(50_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
    }

    @Test
    fun normalUpdateAccountsTheDifferenceAndAdvancesTheBaseline() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 10_000L)
        ingest(source, recorder = recorder)
        source.report(youtube to 15_000L)
        val outcome = ingest(source, recorder = recorder)

        val accounted = outcome as SnapshotAccountingOutcome.Accounted
        assertEquals("2026-09-25", accounted.dateKey)
        assertEquals(youtube, accounted.deltas.single().packageName)
        assertEquals("the delta is the increase, not the counter", 5_000L, accounted.deltas.single().elapsedMs)
        assertEquals("only the increase is usage", 5_000L, usageRepository.writes.single().deltaMs)
        assertEquals(
            "the baseline advances to what was observed",
            15_000L,
            store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs,
        )
    }

    @Test
    fun anUnchangedCounterAccountsForNothingAndKeepsTheBaseline() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 15_000L)
        ingest(source, recorder = recorder)
        val outcome = ingest(source, recorder = recorder)

        assertTrue((outcome as SnapshotAccountingOutcome.Accounted).deltas.isEmpty())
        assertEquals(0, usageRepository.writes.size)
        assertEquals(15_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
    }

    @Test
    fun aResetAccountsTheObservedValueAndNeverNegativeUsage() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 100_000L)
        ingest(source, recorder = recorder)
        source.report(youtube to 20_000L)
        ingest(source, recorder = recorder)

        assertTrue("no write may ever be non-positive", usageRepository.writes.all { it.deltaMs > 0L })
        assertEquals(20_000L, usageRepository.writes.single().deltaMs)
        assertEquals(20_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
    }

    @Test
    fun aMissingPackageIsNotObservedAndItsBaselineSurvives() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 10_000L, tiktok to 5_000L)
        ingest(source, recorder = recorder)
        source.report(youtube to 15_000L)
        ingest(source, recorder = recorder)

        assertEquals(listOf(youtube), usageRepository.writes.map { it.packageName })
        assertNotNull("a package that was not observed keeps its baseline", store.stored(1L, 10L, tiktok))
        assertEquals(5_000L, store.stored(1L, 10L, tiktok)!!.cumulativeForegroundMs)
    }

    @Test
    fun aReappearingPackageContinuesFromItsRetainedBaseline() {
        // T0 = 10000, T1 missing, T2 = 15000. The counter is cumulative *within one window*,
        // so the retained baseline is still a valid comparison point and the difference is
        // usage that really happened in that window - it is not subtracted blindly.
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 10_000L)
        ingest(source, recorder = recorder)
        source.report(tiktok to 1_000L)
        ingest(source, recorder = recorder)
        source.report(youtube to 15_000L)
        ingest(source, recorder = recorder)

        assertEquals(5_000L, usageRepository.writes.last().deltaMs)
        assertEquals(youtube, usageRepository.writes.last().packageName)
        assertEquals(15_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
    }

    // ---- restart ------------------------------------------------------------

    @Test
    fun processRestartKeepsAccountingFromTheStoredBaseline() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())

        source.report(youtube to 10_000L)
        ingest(source, recorder = recorder(source))

        // A brand-new recorder stands in for a restarted process: the only state it can use
        // is what was persisted.
        val afterRestart = recorder(source, observedAtMs = 1_790_299_800_000L)
        source.report(youtube to 15_000L)
        ingest(source, recorder = afterRestart)

        assertEquals("the delta survived the restart", 5_000L, usageRepository.writes.single().deltaMs)
        assertEquals(15_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun accountIsolation() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 10_000L)
        ingest(source, accountId = 1L, recorder = recorder)
        source.report(youtube to 90_000L)
        ingest(source, accountId = 2L, recorder = recorder)

        assertEquals("a different account is a different baseline", 10_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
        assertEquals(90_000L, store.stored(2L, 10L, youtube)!!.cumulativeForegroundMs)
        assertEquals("no usage accrued: each account saw only a baseline", 0, usageRepository.writes.size)
    }

    @Test
    fun childIsolation() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 10_000L)
        ingest(source, childId = 10L, recorder = recorder)
        source.report(youtube to 90_000L)
        ingest(source, childId = 11L, recorder = recorder)

        assertEquals(10_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
        assertEquals(90_000L, store.stored(1L, 11L, youtube)!!.cumulativeForegroundMs)
        assertEquals(0, usageRepository.writes.size)
    }

    @Test
    fun packageIsolation() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 10_000L, tiktok to 1_000L)
        ingest(source, recorder = recorder)
        source.report(youtube to 10_000L, tiktok to 4_000L)
        ingest(source, recorder = recorder)

        assertEquals(tiktok, usageRepository.writes.single().packageName)
        assertEquals(3_000L, usageRepository.writes.single().deltaMs)
        assertEquals(10_000L, store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs)
        assertEquals(4_000L, store.stored(1L, 10L, tiktok)!!.cumulativeForegroundMs)
    }

    @Test
    fun aDifferentWindowGetsItsOwnBaselineAndLeavesTheOldOneAlone() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        source.report(youtube to 30_000L)
        ingest(source, recorder = recorder)

        // Yesterday's window was never observed, so it is a fresh baseline - not a blind
        // subtraction against today's counter.
        runBlocking {
            recorder.ingest(1L, 10L, window(nextDayStart, nextDayEnd), AppCategories::categoryFor)
        }

        assertEquals(0, usageRepository.writes.size)
        assertEquals(30_000L, store.stored(1L, 10L, youtube, window())!!.cumulativeForegroundMs)
        assertEquals(30_000L, store.stored(1L, 10L, youtube, window(nextDayStart, nextDayEnd))!!.cumulativeForegroundMs)
    }

    // ---- failure semantics --------------------------------------------------

    @Test
    fun withoutUsageAccessNothingIsWrittenAndNoBaselineIsCreated() {
        val source = FakeSource(UsageAccessState.UNAVAILABLE, emptyList())

        val outcome = ingest(source, recorder = recorder(source))

        assertEquals(SnapshotAccountingOutcome.UsageAccessUnavailable, outcome)
        assertEquals(0, usageRepository.writes.size)
        assertEquals(0, store.rows.size)
        assertEquals("nothing may be written outside a transaction", 0, transaction.entered)
    }

    @Test
    fun anUnattributableWindowWritesNothingAndDoesNotAdvanceAnyBaseline() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList()).apply { report(youtube to 50_000L) }
        // 23:00 -> 01:00: a total cannot be split across two days, so this window has no day.
        val crossing = UsageRange(1_790_377_200_000L, 1_790_384_400_000L)

        val outcome = runBlocking {
            recorder(source).ingest(1L, 10L, crossing, AppCategories::categoryFor)
        }

        assertEquals(SnapshotAccountingOutcome.WindowNotAttributable, outcome)
        assertEquals("a delta-calculation failure must not overwrite a checkpoint", 0, store.rows.size)
        assertEquals(0, usageRepository.writes.size)
    }

    @Test
    fun bothWritesHappenInsideOneTransaction() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val recorder = recorder(source)

        var writesOutsideATransaction = 0
        store.onWrite = { if (transaction.depth == 0) writesOutsideATransaction++ }
        usageRepository.onWrite = { if (transaction.depth == 0) writesOutsideATransaction++ }

        source.report(youtube to 10_000L)
        ingest(source, recorder = recorder)
        source.report(youtube to 15_000L)
        ingest(source, recorder = recorder)

        assertTrue("the pair must run inside a transaction", transaction.entered >= 2)
        assertEquals("no write may happen outside the transaction boundary", 0, writesOutsideATransaction)
        assertEquals("and the boundary is always left again", 0, transaction.depth)
        assertEquals("the baseline and the usage were both written", 1, usageRepository.writes.size)
        assertNotNull(store.stored(1L, 10L, youtube))
    }

    @Test
    fun aFailingUsageWriteLeavesTheBaselineUnchanged() {
        val source = FakeSource(UsageAccessState.AVAILABLE, emptyList())
        val failing = object : ScreenTimeUsageRepository by usageRepository {
            override suspend fun addUsage(
                accountId: Long,
                childId: Long,
                dateKey: String,
                packageName: String,
                category: AppCategory,
                deltaMs: Long,
            ) = throw IllegalStateException("disk full")
        }
        val recorder = PersistentUsageSnapshotRecorder(
            source = source,
            engine = UsageSnapshotDeltaEngine(zone),
            checkpointRepository = store,
            accounting = ScreenTimeUsageAccounting(failing, zone),
            transaction = RollingBackTransaction(store),
            sourceId = UsageSourceId.USAGE_STATS,
            clock = { 1_790_295_000_000L },
        )

        source.report(youtube to 10_000L)
        ingest(source, recorder = recorder) // baseline
        source.report(youtube to 15_000L)

        assertThrows(IllegalStateException::class.java) { ingest(source, recorder = recorder) }

        assertEquals(
            "a failed usage write must not silently advance the baseline",
            10_000L,
            store.stored(1L, 10L, youtube)!!.cumulativeForegroundMs,
        )
    }

    /** Rolls the checkpoint store back when the block fails, like a real transaction would. */
    private class RollingBackTransaction(
        private val store: FakeCheckpointStore,
    ) : UsageAccountingTransaction {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            val before = LinkedHashMap(store.rows)
            return try {
                block()
            } catch (error: Throwable) {
                store.rows.clear()
                store.rows.putAll(before)
                throw error
            }
        }
    }
}
