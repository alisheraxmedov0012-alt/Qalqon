package uz.faceguard.app.screentime

import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimit
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.feature.home.ScreenTimeSummaryLoader
import uz.faceguard.app.feature.home.ScreenTimeSummaryStatus
import uz.faceguard.app.feature.home.ScreenTimeSummaryUiState
import uz.faceguard.app.feature.home.ScreenTimeTargetStatus
import uz.faceguard.app.feature.home.ScreenTimeTargetUiState

/**
 * Phase 13: the screen-time summary **loader**, at the JVM level.
 *
 * The loader is the boundary between the domain's evaluator and the Home screen. Its
 * only job is to pick the right status for the current (account, target, access) triple
 * and, when data is readable, to copy the evaluator's facts verbatim into rows. The
 * critical product invariant is that "usage cannot be read" and "usage is zero" must
 * never collapse: a missing reading is [ScreenTimeSummaryStatus.USAGE_UNAVAILABLE], never
 * a fake `0 min`.
 *
 * Previously this behaviour was only covered by an instrumented test (which needs a
 * device). The loader itself is Android-free (Flow + the existing evaluator), so this
 * test drives it with fakes and a **real** [ScreenTimeLimitEvaluator], keeping the
 * evaluation logic under test rather than stubbed out. No sleeps decide an assertion:
 * every wait polls for the expected state with a bounded timeout, and the fake sources
 * are deterministic.
 */
class ScreenTimeSummaryLoaderJvmTest {

    private val accountId = 1L
    private val childId = 5L
    private val childName = "Vali"
    private val zone = ZoneId.of("UTC")

    /** Fixed clock so the loader's "today" is deterministic. */
    private val fixedNow = 1_790_294_460_000L

    private fun child(id: Long = childId) = ChildProfile(
        id = id,
        accountId = accountId,
        childName = childName,
        restrictionLevel = RestrictionLevel.HIGH,
        isFaceEnrolled = true,
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    // ---- fakes ---------------------------------------------------------------

    /** Usage with real, controllable values (the evaluator reads these). */
    private class FakeUsage : ScreenTimeUsageRepository {
        var totalMs = 0L
        var categoryMs = 0L
        var appMs = 0L
        val dayUsage = MutableStateFlow<List<AppUsage>>(emptyList())
        var failObserve: Throwable? = null

        override suspend fun addUsage(
            accountId: Long,
            childId: Long,
            dateKey: String,
            packageName: String,
            category: AppCategory,
            deltaMs: Long,
        ) = Unit

        override suspend fun getAppUsageMs(
            accountId: Long,
            childId: Long,
            dateKey: String,
            packageName: String,
        ): Long = appMs

        override suspend fun getCategoryUsageMs(
            accountId: Long,
            childId: Long,
            dateKey: String,
            category: AppCategory,
        ): Long = categoryMs

        override suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String): Long = totalMs

        override suspend fun getDayUsage(accountId: Long, childId: Long, dateKey: String): List<AppUsage> =
            dayUsage.value

        override fun observeDayUsage(accountId: Long, childId: Long, dateKey: String): Flow<List<AppUsage>> =
            failObserve?.let { error -> flow<List<AppUsage>> { throw error } } ?: dayUsage
    }

    private class FakeLimits : ScreenTimeLimitRepository {
        var totalLimitMinutes: Int? = null

        /** Emits when the configured limits change; the loader re-reads on each emission. */
        val changes = MutableStateFlow<List<ScreenTimeLimit>>(emptyList())

        override suspend fun limits(accountId: Long, childId: Long): List<ScreenTimeLimit> = emptyList()

        override fun observeLimits(accountId: Long, childId: Long): Flow<List<ScreenTimeLimit>> = changes

        override suspend fun limit(
            accountId: Long,
            childId: Long,
            scope: LimitScope,
            category: AppCategory?,
        ): ScreenTimeLimit? = totalLimitMinutes?.takeIf { scope == LimitScope.TOTAL }?.let {
            ScreenTimeLimit(accountId, childId, LimitScope.TOTAL, null, it)
        }

        override suspend fun upsert(
            accountId: Long,
            childId: Long,
            scope: LimitScope,
            category: AppCategory?,
            limitMinutes: Int,
        ) = Unit

        override suspend fun delete(
            accountId: Long,
            childId: Long,
            scope: LimitScope,
            category: AppCategory?,
        ) = Unit
    }

    private class FakePolicies : ChildAppPolicyRepository {
        override fun observePolicies(accountId: Long, childId: Long): Flow<List<AppPolicy>> = flowOf(emptyList())
        override suspend fun policyFor(accountId: Long, childId: Long, packageName: String): AppPolicy? = null
        override suspend fun upsert(accountId: Long, childId: Long, policy: AppPolicy) = Unit
        override suspend fun delete(accountId: Long, childId: Long, packageName: String) = Unit
        override suspend fun deleteAllForChild(accountId: Long, childId: Long) = Unit
    }

    private val usage = FakeUsage()
    private val limits = FakeLimits()

    private fun loader() = ScreenTimeSummaryLoader(
        usageRepository = usage,
        limitRepository = limits,
        // A real evaluator over the fakes: the arithmetic stays under test.
        evaluator = ScreenTimeLimitEvaluator(usage, limits, FakePolicies()),
        zone = zone,
        clock = { fixedNow },
    )

    /** Drives the loader on a background scope and waits for a matching state. */
    private fun observe(
        accountIdFlow: Flow<Long?>,
        target: Flow<ScreenTimeTargetUiState>,
        granted: Flow<Boolean>,
        predicate: (ScreenTimeSummaryUiState) -> Boolean,
    ): ScreenTimeSummaryUiState = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val state = loader().observe(accountIdFlow, target, granted, scope)
            val subscription = scope.launch { state.collect {} }
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                if (predicate(state.value)) return@runBlocking state.value
                delay(10)
            }
            state.value
        } finally {
            scope.cancel()
        }
    }

    private fun targetWith(child: ChildProfile?) = ScreenTimeTargetUiState(
        status = ScreenTimeTargetStatus.READY,
        children = listOfNotNull(child),
        activeChildId = child?.id,
    )

    // ---- status selection ----------------------------------------------------

    @Test
    fun noAccountReadsAsNoAccountNeverAsZeroUsage() {
        val result = observe(flowOf(null), flowOf(targetWith(child())), flowOf(true)) {
            it.status == ScreenTimeSummaryStatus.NO_ACCOUNT
        }

        assertEquals(ScreenTimeSummaryStatus.NO_ACCOUNT, result.status)
        assertNull("no account must not fabricate a total", result.total)
    }

    @Test
    fun noSelectedTargetReadsAsNoTarget() {
        val result = observe(
            flowOf(accountId),
            flowOf(ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.READY, children = listOf(child()), activeChildId = null)),
            flowOf(true),
        ) { it.status == ScreenTimeSummaryStatus.NO_TARGET }

        assertEquals(ScreenTimeSummaryStatus.NO_TARGET, result.status)
        assertNull(result.total)
    }

    @Test
    fun missingUsageAccessIsItsOwnStatusAndNeverAZeroReading() {
        // Usage Access is not granted: usage genuinely cannot be read. The loader must
        // say so, and must not present `0 min` (which would claim the child used none).
        val result = observe(flowOf(accountId), flowOf(targetWith(child())), flowOf(false)) {
            it.status == ScreenTimeSummaryStatus.USAGE_UNAVAILABLE
        }

        assertEquals(ScreenTimeSummaryStatus.USAGE_UNAVAILABLE, result.status)
        assertNull("unreadable usage must never be rendered as zero", result.total)
        assertEquals(childId, result.childId)
        assertEquals(childName, result.childName)
    }

    @Test
    fun readableUsageProducesAReadySummaryWithTheEvaluatorsFactsVerbatim() {
        usage.totalMs = 90L * 60_000L // 90 whole minutes
        limits.totalLimitMinutes = 120

        val result = observe(flowOf(accountId), flowOf(targetWith(child())), flowOf(true)) {
            it.status == ScreenTimeSummaryStatus.READY
        }

        assertEquals(ScreenTimeSummaryStatus.READY, result.status)
        assertNull(result.errorMessageRes)
        val total = result.total!!
        assertEquals(90L * 60_000L, total.usedMs)
        assertEquals(120, total.limitMinutes)
        // The evaluator owns `remaining = limit - used`; the loader copies it.
        assertEquals(30L * 60_000L, total.remainingMs)
        assertEquals(false, total.exceeded)
        assertTrue("the day's date key is carried", result.dateKey != null)
    }

    @Test
    fun aFailedReadIsReportedAsErrorNotAsNoLimitOrDefaultZero() {
        usage.failObserve = IllegalStateException("usage store unavailable")

        val result = observe(flowOf(accountId), flowOf(targetWith(child())), flowOf(true)) {
            it.status == ScreenTimeSummaryStatus.ERROR
        }

        assertEquals(ScreenTimeSummaryStatus.ERROR, result.status)
        assertTrue("an error must be reported to the UI", result.errorMessageRes != null)
        assertNull("a failed read must not masquerade as a zero reading", result.total)
    }

    // ---- reactivity ----------------------------------------------------------

    @Test
    fun theSummaryFollowsTheUsageAndLimitSources() = runBlocking {
        usage.totalMs = 10L * 60_000L
        limits.totalLimitMinutes = 60

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val state = loader().observe(
                flowOf(accountId),
                flowOf(targetWith(child())),
                flowOf(true),
                scope,
            )
            val subscription = scope.launch { state.collect {} }
            await(state) { it.status == ScreenTimeSummaryStatus.READY }
            assertEquals(10L * 60_000L, state.value.total!!.usedMs)

            // Usage grows: the summary must re-read (the repository change is the trigger).
            usage.totalMs = 60L * 60_000L
            usage.dayUsage.value = listOf(AppUsage("com.example.youtube", AppCategory.OTHER, 60L * 60_000L))
            await(state) { it.total?.usedMs == 60L * 60_000L }
            assertEquals("at the limit the day is exceeded", true, state.value.total!!.exceeded)

            subscription.cancel()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(
        state: kotlinx.coroutines.flow.StateFlow<ScreenTimeSummaryUiState>,
        predicate: (ScreenTimeSummaryUiState) -> Boolean,
    ): ScreenTimeSummaryUiState {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return state.value
            delay(10)
        }
        throw AssertionError("condition not reached; last state = ${state.value}")
    }
}
