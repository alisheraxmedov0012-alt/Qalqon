package uz.faceguard.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.PersistentUsageSnapshotRecorder
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageCollectionCoordinator
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpointRepository
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-7: the production collector over the **real** v8 database, the real
 * persisted target and the real transaction.
 *
 * Only the two things a test cannot have are replaced: the account session (so the
 * collector resolves a known account) and the device's usage statistics (Usage Access is a
 * user-granted app-op, so a fake source stands in — the same seam step 1B-4 introduced).
 * Usage rows, baselines, transaction and the account-scoped target are the shipped
 * implementations.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeUsageCollectionInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var sessionManager: SessionManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var db: FaceGuardDatabase

    private lateinit var usage: ScreenTimeUsageRepository
    private lateinit var checkpoints: UsageSnapshotCheckpointRepository
    private lateinit var children: ChildProfileRepositoryImpl
    private lateinit var activeChild: ScreenTimeActiveChildRepositoryImpl
    private lateinit var coordinator: ScreenTimeUsageCollectionCoordinator

    private val source = FakeSource()
    private val accounts = FakeAccounts()

    private val zone = ZoneOffset.UTC
    private val dayStart = 1_790_294_400_000L
    private val dayEnd = 1_790_380_800_000L
    private val dateKey = "2026-09-25"
    private val now = dayStart + 60_000L
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    private class FakeSource : AppUsageSource {
        var available = true
        var samples: List<AppUsageSample> = emptyList()
        val queries = CopyOnWriteArrayList<UsageRange>()

        fun report(vararg reported: Pair<String, Long>) {
            samples = reported.map { (pkg, ms) -> AppUsageSample(pkg, ms) }
        }

        override fun usageAccess(): UsageAccessState =
            if (available) UsageAccessState.AVAILABLE else UsageAccessState.UNAVAILABLE

        override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult {
            queries += range
            return if (available) AppUsageQueryResult.Available(samples) else AppUsageQueryResult.Unavailable
        }
    }

    private class FakeAccounts : AccountRepository {
        private val state = kotlinx.coroutines.flow.MutableStateFlow<Long?>(1L)
        override val currentAccountId: kotlinx.coroutines.flow.Flow<Long?> = state
        override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
            AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        override suspend fun login(phoneNumber: String, pin: String): AuthResult =
            AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        override suspend fun getCurrentAccount(): UserAccount? = null
        override suspend fun logout() = Unit
        override suspend fun verifyPin(pin: String): Boolean = false
    }

    @Before
    fun setUp() {
        sessionManager = SessionManager(context)
        runBlocking { sessionManager.clearSession() }
        file = File(context.cacheDir, "collection-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        settingsStore = SettingsStore(dataStore, sessionManager)

        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        usage = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
        checkpoints = UsageSnapshotCheckpointRepositoryImpl(db.usageSnapshotCheckpointDao())
        children = ChildProfileRepositoryImpl(db.childProfileDao(), uz.faceguard.app.security.PassthroughTemplateCipher)
        activeChild = ScreenTimeActiveChildRepositoryImpl(settingsStore)

        val recorder = PersistentUsageSnapshotRecorder(
            source = source,
            engine = UsageSnapshotDeltaEngine(zone),
            checkpointRepository = checkpoints,
            accounting = ScreenTimeUsageAccounting(usage, zone),
            transaction = RoomUsageAccountingTransaction(db),
            sourceId = UsageSourceId.USAGE_STATS,
            clock = { now },
        )
        coordinator = ScreenTimeUsageCollectionCoordinator(
            accountRepository = accounts,
            activeChildRepository = activeChild,
            childProfileRepository = children,
            recorder = recorder,
            zone = zone,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        file.delete()
        runBlocking { sessionManager.clearSession() }
    }

    private suspend fun addChild(accountId: Long, childName: String): Long =
        children.addChild(accountId, childName, RestrictionLevel.MEDIUM)

    private suspend fun collect() = coordinator.collect()

    // ---- end to end ---------------------------------------------------------

    @Test
    fun theCollectedDeltaIsPersistedForTheStoredTarget() = runBlocking {
        val childId = addChild(1L, "Vali")
        activeChild.setActiveChildId(1L, childId)

        source.report(youtube to 600_000L)
        collect()
        source.report(youtube to 1_500_000L)

        val outcome = collect() as ScreenTimeUsageCollectionCoordinator.Outcome.Collected

        assertEquals(childId, outcome.childId)
        assertEquals(dateKey, outcome.dateKey)
        assertEquals(900_000L, outcome.accountedMs)
        assertEquals(900_000L, usage.getAppUsageMs(1L, childId, dateKey, youtube))
    }

    @Test
    fun theBaselineSurvivesAndTheNextCollectionContinuesFromIt() = runBlocking {
        val childId = addChild(1L, "Vali")
        activeChild.setActiveChildId(1L, childId)
        source.report(youtube to 10_000L)
        collect()

        // A brand-new coordinator over the same database stands in for a restarted process.
        val restarted = coordinatorFor()
        source.report(youtube to 15_000L)
        restarted.collect()

        assertEquals("the delta survived the restart", 5_000L, usage.getAppUsageMs(1L, childId, dateKey, youtube))
        assertEquals(15_000L, checkpoints.checkpoint(1L, childId, UsageSourceId.USAGE_STATS, dayRange(), youtube)!!.cumulativeForegroundMs)
    }

    /** A fresh coordinator over the same database, as a new process would build. */
    private fun coordinatorFor(): ScreenTimeUsageCollectionCoordinator {
        val recorder = PersistentUsageSnapshotRecorder(
            source = source,
            engine = UsageSnapshotDeltaEngine(zone),
            checkpointRepository = checkpoints,
            accounting = ScreenTimeUsageAccounting(usage, zone),
            transaction = RoomUsageAccountingTransaction(db),
            sourceId = UsageSourceId.USAGE_STATS,
            clock = { now },
        )
        return ScreenTimeUsageCollectionCoordinator(
            accountRepository = accounts,
            activeChildRepository = activeChild,
            childProfileRepository = children,
            recorder = recorder,
            zone = zone,
            clock = { now },
        )
    }

    private fun dayRange() = UsageRange(dayStart, dayEnd)

    // ---- the target ---------------------------------------------------------

    @Test
    fun withNoStoredTargetNothingIsCollectedOrQueried() = runBlocking {
        val childId = addChild(1L, "Vali")
        source.report(youtube to 10_000L)

        val outcome = collect()

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.NoActiveChild, outcome)
        assertEquals("the device must not even be read", 0, source.queries.size)
        assertEquals(0, db.dailyAppUsageDao().dayUsage(1L, childId, dateKey).size)
    }

    @Test
    fun deletingTheTargetChildStopsCollectionInsteadOfBlamingASibling() = runBlocking {
        val first = addChild(1L, "Vali")
        val second = addChild(1L, "Ali")
        activeChild.setActiveChildId(1L, first)
        source.report(youtube to 10_000L)
        collect()

        children.deleteChild(1L, first)
        source.report(youtube to 99_000L)
        val outcome = collect()

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.ActiveChildNotOwned(first), outcome)
        assertNull(
            "no usage may be attributed to the surviving sibling",
            db.dailyAppUsageDao().packageUsage(1L, second, dateKey, youtube),
        )
    }

    @Test
    fun oneTargetPerAccountAndNothingLeaksBetweenThem() = runBlocking {
        val childOfOne = addChild(1L, "Vali")
        val childOfTwo = addChild(2L, "Ali")
        activeChild.setActiveChildId(1L, childOfOne)
        activeChild.setActiveChildId(2L, childOfTwo)

        source.report(youtube to 10_000L)
        collect()
        source.report(youtube to 40_000L)
        collect()

        assertEquals(30_000L, usage.getTotalUsageMs(1L, childOfOne, dateKey))
        assertEquals("the other account's child is untouched", 0L, usage.getTotalUsageMs(2L, childOfTwo, dateKey))
    }

    // ---- access / failure ---------------------------------------------------

    @Test
    fun withoutUsageAccessNothingIsWrittenAndNoBaselineIsCreated() = runBlocking {
        val childId = addChild(1L, "Vali")
        activeChild.setActiveChildId(1L, childId)
        source.available = false
        source.report(youtube to 50_000L)

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.UsageAccessUnavailable, collect())

        assertEquals(0L, usage.getTotalUsageMs(1L, childId, dateKey))
        assertNull(checkpoints.checkpoint(1L, childId, UsageSourceId.USAGE_STATS, dayRange(), youtube))
    }

    @Test
    fun theWindowIsTheLocalDayAndNeverCrossesMidnight() = runBlocking {
        val childId = addChild(1L, "Vali")
        activeChild.setActiveChildId(1L, childId)
        source.report(youtube to 10_000L)

        collect()

        val queried = source.queries.single()
        assertEquals(dayStart, queried.startTimeMs)
        assertEquals(dayEnd, queried.endTimeMs)
        assertEquals(dateKey, UsageSnapshotDeltaEngine(zone).attributableDateKey(queried))
    }

    // ---- concurrency --------------------------------------------------------

    @Test
    fun concurrentCollectionsDoNotDoubleCount() = runBlocking {
        val childId = addChild(1L, "Vali")
        activeChild.setActiveChildId(1L, childId)
        source.report(youtube to 10_000L)
        collect()
        source.report(youtube to 25_000L)

        coroutineScope {
            (1..8).map { async { coordinator.collect() } }.awaitAll()
        }

        assertEquals(
            "the 15s delta must be counted exactly once",
            15_000L,
            usage.getAppUsageMs(1L, childId, dateKey, youtube),
        )
        assertTrue(
            "and exactly one row per package",
            db.dailyAppUsageDao().dayUsage(1L, childId, dateKey).size == 1,
        )
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun childrenAndPackagesStayIsolatedInTheRealDatabase() = runBlocking {
        val first = addChild(1L, "Vali")
        val second = addChild(1L, "Ali")

        source.report(youtube to 10_000L, tiktok to 1_000L)
        activeChild.setActiveChildId(1L, first)
        collect()
        source.report(youtube to 30_000L, tiktok to 5_000L)
        collect()

        // Switch the target: the second child starts from its own baseline.
        activeChild.setActiveChildId(1L, second)
        collect()

        // The targeted child accrued two per-package deltas: youtube 10000->30000 = +20000
        // and tiktok 1000->5000 = +4000, so its day total is their sum.
        assertEquals(20_000L, usage.getAppUsageMs(1L, first, dateKey, youtube))
        assertEquals(4_000L, usage.getAppUsageMs(1L, first, dateKey, tiktok))
        assertEquals(24_000L, usage.getTotalUsageMs(1L, first, dateKey))
        assertEquals("the newly targeted child sees no history", 0L, usage.getTotalUsageMs(1L, second, dateKey))
    }
}
