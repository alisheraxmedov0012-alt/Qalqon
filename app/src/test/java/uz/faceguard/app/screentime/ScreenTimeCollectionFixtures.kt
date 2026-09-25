package uz.faceguard.app.screentime

import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.PersistentUsageSnapshotRecorder
import uz.faceguard.app.domain.screentime.ScreenTimeActiveChildRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.ScreenTimeUsageCollectionCoordinator
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageAccountingTransaction
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpoint
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpointRepository
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-7 test fixtures: deterministic stand-ins for the device and the database.
 *
 * Every collaborator below is a *domain* interface, so the real
 * [PersistentUsageSnapshotRecorder] and the real
 * [ScreenTimeUsageCollectionCoordinator] run unchanged against them — the orchestration
 * under test is production code, and no Android usage-statistics behaviour is fabricated.
 * `UsageStatsManager` itself is not exercised (it needs a device and Usage Access).
 */

/** Device usage, controllable per test. Counts queries so a loop's cadence is observable. */
internal class FakeAppUsageSource(
    var available: Boolean = true,
) : AppUsageSource {

    var samples: List<AppUsageSample> = emptyList()

    /** How many times the device was read; the observable evidence a loop is ticking. */
    var queryCount: Int = 0
        private set

    /** When set, a query fails the way a platform refusal would. */
    var failWith: Throwable? = null

    fun report(vararg reported: Pair<String, Long>) {
        samples = reported.map { (pkg, ms) -> AppUsageSample(pkg, ms) }
    }

    override fun usageAccess(): UsageAccessState =
        if (available) UsageAccessState.AVAILABLE else UsageAccessState.UNAVAILABLE

    override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult {
        queryCount++
        failWith?.let { throw it }
        return if (available) {
            AppUsageQueryResult.Available(samples)
        } else {
            AppUsageQueryResult.Unavailable
        }
    }
}

/** In-memory checkpoints, keyed exactly like the real table. */
internal class FakeCheckpointStore : UsageSnapshotCheckpointRepository {

    val rows = LinkedHashMap<String, UsageSnapshotCheckpoint>()

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
        checkpoints.forEach { rows[keyOf(it.accountId, it.childId, it.source, it.range, it.packageName)] = it }
    }

    override suspend fun delete(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
        packageName: String,
    ) {
        rows.remove(keyOf(accountId, childId, source, range, packageName))
    }

    override suspend fun deleteForChild(accountId: Long, childId: Long) {
        rows.values.removeAll { it.accountId == accountId && it.childId == childId }
    }

    override suspend fun deleteForAccount(accountId: Long) {
        rows.values.removeAll { it.accountId == accountId }
    }

    /** Reads the stored counter straight from the map, so assertions stay non-suspend. */
    fun cumulativeMs(accountId: Long, childId: Long, range: UsageRange, pkg: String): Long? =
        rows[keyOf(accountId, childId, UsageSourceId.USAGE_STATS, range, pkg)]?.cumulativeForegroundMs
}

/** Usage rows, recorded as written. */
internal class FakeUsageRepository : ScreenTimeUsageRepository {

    data class Write(
        val accountId: Long,
        val childId: Long,
        val dateKey: String,
        val packageName: String,
        val deltaMs: Long,
    )

    val writes = mutableListOf<Write>()

    var failAddUsageWith: Throwable? = null

    override suspend fun addUsage(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String,
        category: AppCategory,
        deltaMs: Long,
    ) {
        require(deltaMs > 0L) { "a non-positive delta must never reach the repository, got $deltaMs" }
        failAddUsageWith?.let { throw it }
        writes += Write(accountId, childId, dateKey, packageName, deltaMs)
    }

    fun totalFor(childId: Long): Long = writes.filter { it.childId == childId }.sumOf { it.deltaMs }

    override suspend fun getAppUsageMs(accountId: Long, childId: Long, dateKey: String, packageName: String) = 0L
    override suspend fun getCategoryUsageMs(accountId: Long, childId: Long, dateKey: String, category: AppCategory) = 0L
    override suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String) = 0L
    override suspend fun getDayUsage(accountId: Long, childId: Long, dateKey: String) = emptyList<AppUsage>()
    override fun observeDayUsage(accountId: Long, childId: Long, dateKey: String): Flow<List<AppUsage>> =
        flowOf(emptyList())
}

/** Pass-through transaction; rolls the checkpoint store back when the block fails. */
internal class FakeTransaction(
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

/** Accounts, controllable per test. */
internal class FakeAccounts(accountId: Long? = 1L) : AccountRepository {

    private val state = MutableStateFlow(accountId)

    override val currentAccountId: Flow<Long?> = state

    fun signIn(id: Long) {
        state.value = id
    }

    fun signOut() {
        state.value = null
    }

    override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
        AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)

    override suspend fun login(phoneNumber: String, pin: String): AuthResult =
        AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)

    override suspend fun getCurrentAccount(): UserAccount? = null
    override suspend fun logout() = signOut()
    override suspend fun verifyPin(pin: String): Boolean = false
}

/** The screen-time target, per account — exactly like the persisted store. */
internal class FakeActiveChild : ScreenTimeActiveChildRepository {

    private val byAccount = mutableMapOf<Long, Long>()

    override suspend fun activeChildId(accountId: Long): Long? = byAccount[accountId]

    override suspend fun setActiveChildId(accountId: Long, childId: Long) {
        byAccount[accountId] = childId
    }

    override suspend fun clearActiveChildId(accountId: Long) {
        byAccount.remove(accountId)
    }
}

/** Children, per account. Mutating the list models a child being added or deleted. */
internal class FakeChildren(initial: Map<Long, List<Long>> = emptyMap()) : ChildProfileRepository {

    private val byAccount = initial.mapValues { (_, ids) -> ids.toMutableList() }.toMutableMap()

    fun removeChild(accountId: Long, childId: Long) {
        byAccount[accountId]?.remove(childId)
    }

    private fun profiles(accountId: Long): List<ChildProfile> =
        (byAccount[accountId] ?: emptyList()).map {
            ChildProfile(id = it, accountId = accountId, childName = "child-$it", restrictionLevel = RestrictionLevel.MEDIUM)
        }

    override fun observeChildren(accountId: Long): Flow<List<ChildProfile>> = flowOf(profiles(accountId))

    override suspend fun addChild(accountId: Long, childName: String, level: RestrictionLevel): Long = 0L
    override suspend fun updateChild(accountId: Long, childId: Long, childName: String, level: RestrictionLevel) = Unit
    override suspend fun deleteChild(accountId: Long, childId: Long) = removeChild(accountId, childId)
    override suspend fun saveFaceEnrollment(accountId: Long, childId: Long, templateRef: String) = Unit
    override suspend fun deleteFaceData(accountId: Long, childId: Long) = Unit
}

/**
 * A real coordinator wired to fakes: the production orchestration runs unchanged, with the
 * device and the database replaced. [zone] and [now] are fixed so the day window is
 * deterministic.
 */
internal class CoordinatorFixture(
    val accounts: FakeAccounts = FakeAccounts(),
    val activeChild: FakeActiveChild = FakeActiveChild(),
    val children: FakeChildren = FakeChildren(),
    val source: FakeAppUsageSource = FakeAppUsageSource(),
    val checkpoints: FakeCheckpointStore = FakeCheckpointStore(),
    val usage: FakeUsageRepository = FakeUsageRepository(),
    val zone: java.time.ZoneId = ZoneOffset.UTC,
    val now: Long = DAY_START + 60_000L,
) {
    val recorder = PersistentUsageSnapshotRecorder(
        source = source,
        engine = UsageSnapshotDeltaEngine(zone),
        checkpointRepository = checkpoints,
        accounting = ScreenTimeUsageAccounting(usage, zone),
        transaction = FakeTransaction(checkpoints),
        sourceId = UsageSourceId.USAGE_STATS,
        clock = { now },
    )

    val coordinator = ScreenTimeUsageCollectionCoordinator(
        accountRepository = accounts,
        activeChildRepository = activeChild,
        childProfileRepository = children,
        recorder = recorder,
        zone = zone,
        clock = { now },
    )

    /** The local day window [now] falls in, for assertions. */
    val dayRange: UsageRange = UsageRange(DAY_START, DAY_END)

    companion object {
        /** 2026-09-25T00:00:00Z and the following midnight. */
        const val DAY_START = 1_790_294_400_000L
        const val DAY_END = 1_790_380_800_000L
        const val DATE_KEY = "2026-09-25"
    }
}
