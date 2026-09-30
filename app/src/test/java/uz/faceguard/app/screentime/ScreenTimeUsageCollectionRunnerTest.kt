package uz.faceguard.app.screentime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.screentime.CollectionInterval
import uz.faceguard.app.domain.screentime.ElapsedTimeSource
import uz.faceguard.app.core.screentime.ScreenTimeUsageCollectionRunner

/**
 * Phase 4 Step 1B-7 (pure JVM): the collection loop's own behaviour.
 *
 * A short cadence drives the loop instead of waiting ten minutes; the action behind it is
 * the real coordinator over fakes, so a tick is a production collection. Nothing here
 * fabricates `UsageStatsManager` behaviour.
 */
class ScreenTimeUsageCollectionRunnerTest {

    private val youtube = "com.google.android.youtube"

    /** Fast enough to observe several ticks, slow enough not to race the assertions. */
    private val tickMs = 25L

    private fun fixture(): CoordinatorFixture = CoordinatorFixture(
        children = FakeChildren(mapOf(1L to listOf(10L, 11L))),
    ).also { runBlocking { it.activeChild.setActiveChildId(1L, 10L) } }

    /** A monotonic clock that never moves: tick timing is irrelevant to these assertions. */
    private class FixedElapsed : ElapsedTimeSource {
        override fun elapsedRealtimeMs(): Long = 0L
    }

    private fun runnerFor(fixture: CoordinatorFixture) =
        ScreenTimeUsageCollectionRunner(fixture.coordinator, FixedElapsed(), tickMs)

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5L)
        }
    }

    // ---- the loop -----------------------------------------------------------

    @Test
    fun collectionNeverRunsBeforeItIsStarted() {
        val fixture = fixture()
        fixture.source.report(youtube to 10_000L)
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            assertFalse("a runner starts stopped", runner.running)
            assertEquals("and reads nothing until started", 0, fixture.source.queryCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun startingTheCollectorBeginsCollectingImmediately() = runBlocking {
        val fixture = fixture()
        fixture.source.report(youtube to 10_000L)
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            runner.start(scope)

            assertTrue(runner.running)
            // The first tick runs at once rather than after a full interval, so a fresh
            // process establishes its baseline straight away.
            awaitUntil { fixture.source.queryCount >= 1 }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedStartKeepsExactlyOneLoop() = runBlocking {
        val fixture = fixture()
        fixture.source.report(youtube to 10_000L)
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            runner.start(scope)
            runner.start(scope)
            runner.start(scope)

            assertEquals("the idempotency guard must hold", 1, runner.loopsStarted)
            assertTrue(runner.running)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stoppingTheCollectorEndsCollection() = runBlocking {
        val fixture = fixture()
        fixture.source.report(youtube to 10_000L)
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            runner.start(scope)
            awaitUntil { fixture.source.queryCount >= 1 }

            runner.stop()

            assertFalse("a stopped runner is not running", runner.running)
            val queriesAtStop = fixture.source.queryCount
            delay(tickMs * 4)
            assertEquals("no tick may happen after stop", queriesAtStop, fixture.source.queryCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedStopIsSafe() {
        val fixture = fixture()
        val runner = runnerFor(fixture)

        runner.stop()
        runner.stop()

        assertFalse(runner.running)
        assertEquals(0, runner.loopsStarted)
    }

    @Test
    fun restartingResumesWithASingleNewLoop() = runBlocking {
        val fixture = fixture()
        fixture.source.report(youtube to 10_000L)
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            runner.start(scope)
            awaitUntil { fixture.source.queryCount >= 1 }
            runner.stop()

            fixture.source.report(youtube to 15_000L)
            runner.start(scope)

            assertEquals("one loop per start", 2, runner.loopsStarted)
            awaitUntil { fixture.usage.writes.isNotEmpty() }
            assertEquals("and the resumed loop accounts normally", 5_000L, fixture.usage.totalFor(10L))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingTheScopeStopsTheLoopAndLeaksNothing() = runBlocking {
        val fixture = fixture()
        fixture.source.report(youtube to 10_000L)
        val runner = runnerFor(fixture)
        val scope = scope()

        runner.start(scope)
        awaitUntil { fixture.source.queryCount >= 1 }

        scope.cancel()

        assertFalse("a cancelled scope leaves no running loop", runner.running)
        val queriesAtCancel = fixture.source.queryCount
        delay(tickMs * 4)
        assertEquals("no work may continue after cancellation", queriesAtCancel, fixture.source.queryCount)
        // And an explicit stop afterwards is still safe.
        runner.stop()
    }

    // ---- failure does not end collection ------------------------------------

    @Test
    fun aFailedTickDoesNotStopLaterTicks() = runBlocking {
        val fixture = fixture()
        fixture.source.failWith = RuntimeException("platform refused")
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            runner.start(scope)
            awaitUntil { fixture.source.queryCount >= 1 }

            // The failure is contained, so the loop keeps ticking.
            awaitUntil { fixture.source.queryCount >= 2 }
            assertTrue(runner.running)
            assertEquals("a failure must not account anything", 0, fixture.usage.writes.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTickWithoutATargetKeepsTheLoopAliveAndWritesNothing() = runBlocking {
        val fixture = CoordinatorFixture(children = FakeChildren(mapOf(1L to listOf(10L))))
        // No active child is ever selected.
        val runner = runnerFor(fixture)
        val scope = scope()

        try {
            runner.start(scope)
            delay(tickMs * 4)

            assertTrue("a missing target is not a failure", runner.running)
            assertEquals(0, fixture.usage.writes.size)
            assertEquals("and nothing is read from the device", 0, fixture.source.queryCount)
        } finally {
            scope.cancel()
        }
    }

    // ---- interval -----------------------------------------------------------

    @Test
    fun theDefaultIntervalIsTenMinutes() {
        val runner = ScreenTimeUsageCollectionRunner(fixture().coordinator, FixedElapsed())

        assertEquals(600_000L, runner.interval)
        assertEquals(600_000L, CollectionInterval.SCREEN_TIME_COLLECTION_INTERVAL_MS)
    }

    @Test
    fun theIntervalIsNeverZeroOrNegative() {
        assertTrue(CollectionInterval.SCREEN_TIME_COLLECTION_INTERVAL_MS > 0L)
        assertEquals(10L * 60L * 1_000L, CollectionInterval.SCREEN_TIME_COLLECTION_INTERVAL_MS)
    }
}
