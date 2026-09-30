package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.screentime.AppCategories
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
 * Phase 4 Step 1B-6: the checkpoint-backed ingestion path against the real v8 database and a
 * real Room transaction.
 *
 * The pure semantics are covered on the JVM (`PersistentUsageSnapshotRecorderTest`); this
 * proves the persisted behaviour end to end — a baseline surviving a "restart", the delta
 * landing in `daily_app_usage`, the identity rules holding in SQL, and the transaction
 * rolling both writes back together.
 *
 * `UsageStatsManager` is not involved: the source is a deterministic fake, so this does not
 * depend on the Step 1B-4 Usage Access limitation.
 */
@RunWith(AndroidJUnit4::class)
class PersistentUsageSnapshotRecorderTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var usage: ScreenTimeUsageRepository
    private lateinit var checkpoints: UsageSnapshotCheckpointRepository
    private lateinit var transaction: UsageAccountingTransaction

    private val zone = ZoneOffset.UTC
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val dateKey = "2026-09-25"
    private val dayStart = 1_790_294_400_000L
    private val dayEnd = 1_790_380_800_000L

    /** Reports whatever the test last told it, exactly like the platform would. */
    private class FakeSource : AppUsageSource {
        var available = true
        var samples: List<AppUsageSample> = emptyList()

        fun report(vararg reported: Pair<String, Long>) {
            samples = reported.map { (pkg, ms) -> AppUsageSample(pkg, ms) }
        }

        override fun usageAccess(): UsageAccessState =
            if (available) UsageAccessState.AVAILABLE else UsageAccessState.UNAVAILABLE

        override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult =
            if (available) AppUsageQueryResult.Available(samples) else AppUsageQueryResult.Unavailable
    }

    private val source = FakeSource()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        usage = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        checkpoints = UsageSnapshotCheckpointRepositoryImpl(db.usageSnapshotCheckpointDao())
        transaction = RoomUsageAccountingTransaction(db)
    }

    @After
    fun tearDown() = db.close()

    private fun recorder(observedAtMs: Long = 1_790_295_000_000L) =
        PersistentUsageSnapshotRecorder(
            source = source,
            engine = UsageSnapshotDeltaEngine(zone),
            checkpointRepository = checkpoints,
            accounting = ScreenTimeUsageAccounting(usage, zone),
            transaction = transaction,
            sourceId = UsageSourceId.USAGE_STATS,
            clock = { observedAtMs },
        )

    private fun window(start: Long = dayStart, end: Long = dayEnd) = UsageRange(start, end)

    private fun ingest(accountId: Long = 1L, childId: Long = 10L, range: UsageRange = window()) = runBlocking {
        recorder().ingest(accountId, childId, range, AppCategories::categoryFor)
    }

    private suspend fun storedCheckpoint(accountId: Long = 1L, childId: Long = 10L, pkg: String = youtube) =
        checkpoints.checkpoint(accountId, childId, UsageSourceId.USAGE_STATS, window(), pkg)

    // ---- the core semantics, persisted --------------------------------------

    @Test
    fun firstObservationEstablishesABaselineAndWritesNoUsage() = runBlocking {
        source.report(youtube to 50_000L)

        val outcome = ingest()

        assertTrue(outcome is SnapshotAccountingOutcome.Accounted)
        assertEquals("a platform counter is not screen time", 0L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(0L, usage.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals(50_000L, storedCheckpoint()!!.cumulativeForegroundMs)
    }

    @Test
    fun normalUpdatePersistsTheDifferenceAndAdvancesTheBaseline() = runBlocking {
        source.report(youtube to 10_000L)
        ingest()
        source.report(youtube to 15_000L)

        val outcome = ingest() as SnapshotAccountingOutcome.Accounted

        assertEquals(5_000L, outcome.deltas.single().elapsedMs)
        assertEquals(5_000L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(15_000L, storedCheckpoint()!!.cumulativeForegroundMs)
    }

    @Test
    fun anUnchangedCounterWritesNothingAndKeepsTheBaseline() = runBlocking {
        source.report(youtube to 15_000L)
        ingest()
        val outcome = ingest()

        assertTrue((outcome as SnapshotAccountingOutcome.Accounted).deltas.isEmpty())
        assertEquals(0L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(15_000L, storedCheckpoint()!!.cumulativeForegroundMs)
    }

    @Test
    fun aResetPersistsTheObservedValueAndNeverNegativeUsage() = runBlocking {
        source.report(youtube to 100_000L)
        ingest()
        source.report(youtube to 20_000L)
        ingest()

        val stored = usage.getAppUsageMs(1L, 10L, dateKey, youtube)
        assertEquals(20_000L, stored)
        assertTrue("usage can never be negative", stored >= 0L)
        assertEquals(20_000L, storedCheckpoint()!!.cumulativeForegroundMs)
    }

    // ---- restart ------------------------------------------------------------

    @Test
    fun processRestartKeepsAccountingFromThePersistedBaseline() = runBlocking {
        source.report(youtube to 10_000L)
        ingest()

        // A freshly built recorder stands in for a new process: the only state it can use is
        // what was written to the database.
        source.report(youtube to 15_000L)
        runBlocking {
            recorder(observedAtMs = 1_790_299_800_000L)
                .ingest(1L, 10L, window(), AppCategories::categoryFor)
        }

        assertEquals("the delta survived the restart", 5_000L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertEquals(15_000L, storedCheckpoint()!!.cumulativeForegroundMs)
    }

    @Test
    fun repeatedIngestionsAccumulateOnlyWhatAccrued() = runBlocking {
        source.report(youtube to 1_000L)
        ingest()
        source.report(youtube to 4_000L)
        ingest()
        source.report(youtube to 4_000L)
        ingest()
        source.report(youtube to 10_000L)
        ingest()

        assertEquals("only the two real increases", 9_000L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun accountAndChildStayIsolatedThroughTheWholePipeline() = runBlocking {
        source.report(youtube to 10_000L)
        ingest(accountId = 1L, childId = 10L)
        source.report(youtube to 90_000L)
        ingest(accountId = 1L, childId = 11L)
        source.report(youtube to 500_000L)
        ingest(accountId = 2L, childId = 10L)

        assertEquals("each scope saw only a baseline", 0L, usage.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals(10_000L, storedCheckpoint(1L, 10L)!!.cumulativeForegroundMs)
        assertEquals(90_000L, storedCheckpoint(1L, 11L)!!.cumulativeForegroundMs)
        assertEquals(500_000L, storedCheckpoint(2L, 10L)!!.cumulativeForegroundMs)
    }

    @Test
    fun aDifferentWindowGetsItsOwnBaselineWithoutTouchingTheOldOne() = runBlocking {
        source.report(youtube to 30_000L)
        ingest()
        ingest(range = window(1_790_380_800_000L, 1_790_467_200_000L))

        assertEquals(0L, usage.getTotalUsageMs(1L, 10L, dateKey))
        assertEquals(30_000L, storedCheckpoint()!!.cumulativeForegroundMs)
        val otherWindow = checkpoints.checkpoint(
            accountId = 1L,
            childId = 10L,
            source = UsageSourceId.USAGE_STATS,
            range = window(1_790_380_800_000L, 1_790_467_200_000L),
            packageName = youtube,
        )
        assertEquals(30_000L, otherWindow!!.cumulativeForegroundMs)
    }

    @Test
    fun aMissingPackageKeepsItsStoredBaseline() = runBlocking {
        source.report(youtube to 10_000L, tiktok to 5_000L)
        ingest()
        source.report(youtube to 15_000L)
        ingest()

        assertEquals(5_000L, storedCheckpoint(pkg = tiktok)!!.cumulativeForegroundMs)
        assertEquals(5_000L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
        assertNull("a package with no observed increase has no usage row", usage.getAppUsageMs(1L, 10L, dateKey, tiktok).takeIf { it > 0L })
    }

    // ---- failure semantics --------------------------------------------------

    @Test
    fun withoutUsageAccessNothingIsWrittenAndNoBaselineIsCreated() = runBlocking {
        source.available = false
        source.report(youtube to 50_000L)

        val outcome = ingest()

        assertEquals(SnapshotAccountingOutcome.UsageAccessUnavailable, outcome)
        assertEquals(0L, usage.getTotalUsageMs(1L, 10L, dateKey))
        assertNull(storedCheckpoint())
    }

    @Test
    fun anUnattributableWindowWritesNothingAndDoesNotAdvanceABaseline() = runBlocking {
        source.report(youtube to 50_000L)
        // 23:00 -> 01:00: a total cannot be split across two days, so this has no day.
        val crossing = UsageRange(1_790_377_200_000L, 1_790_384_400_000L)

        val outcome = ingest(range = crossing)

        assertEquals(SnapshotAccountingOutcome.WindowNotAttributable, outcome)
        assertNull("a delta-calculation failure must not overwrite a checkpoint", storedCheckpoint())
        assertEquals(0L, usage.getTotalUsageMs(1L, 10L, dateKey))
    }

    @Test
    fun theTransactionRollsBackBothWritesTogether() = runBlocking {
        // The property that makes the pair safe: if anything inside fails, neither the usage
        // nor the advanced baseline survives, so the next run can neither double-count nor
        // lose the interval.
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                transaction.inTransaction {
                    checkpoints.save(
                        listOf(
                            UsageSnapshotCheckpoint(
                                accountId = 1L,
                                childId = 10L,
                                source = UsageSourceId.USAGE_STATS,
                                range = window(),
                                packageName = youtube,
                                cumulativeForegroundMs = 42_000L,
                                observedAtMs = 1_790_295_000_000L,
                            ),
                        ),
                    )
                    usage.addUsage(1L, 10L, dateKey, youtube, AppCategories.categoryFor(youtube), 7_000L)
                    throw IllegalStateException("failure after both writes")
                }
            }
        }

        assertNull("the checkpoint write must be rolled back", storedCheckpoint())
        assertEquals("the usage write must be rolled back", 0L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
    }

    @Test
    fun theTransactionCommitsBothWritesTogether() = runBlocking {
        transaction.inTransaction {
            checkpoints.save(
                listOf(
                    UsageSnapshotCheckpoint(
                        accountId = 1L,
                        childId = 10L,
                        source = UsageSourceId.USAGE_STATS,
                        range = window(),
                        packageName = youtube,
                        cumulativeForegroundMs = 42_000L,
                        observedAtMs = 1_790_295_000_000L,
                    ),
                ),
            )
            usage.addUsage(1L, 10L, dateKey, youtube, AppCategories.categoryFor(youtube), 7_000L)
        }

        assertNotNull(storedCheckpoint())
        assertEquals(42_000L, storedCheckpoint()!!.cumulativeForegroundMs)
        assertEquals(7_000L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
    }

    @Test
    fun aFailingDeltaCalculationDoesNotAdvanceTheBaseline() = runBlocking {
        source.report(youtube to 10_000L)
        ingest()

        // An unattributable window is the delta-calculation failure this layer can hit; the
        // stored baseline must remain exactly what it was.
        source.report(youtube to 99_000L)
        ingest(range = UsageRange(1_790_377_200_000L, 1_790_384_400_000L))

        assertEquals(10_000L, storedCheckpoint()!!.cumulativeForegroundMs)
        assertEquals(0L, usage.getAppUsageMs(1L, 10L, dateKey, youtube))
    }
}
