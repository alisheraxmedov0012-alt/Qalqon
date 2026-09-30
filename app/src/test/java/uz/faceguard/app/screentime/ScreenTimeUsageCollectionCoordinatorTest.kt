package uz.faceguard.app.screentime

import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.ScreenTimeUsageCollectionCoordinator
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-7 (pure JVM): what the production collector decides.
 *
 * The recording pipeline underneath is the *real* one (`PersistentUsageSnapshotRecorder`
 * over its domain seams), so these exercise production orchestration; only the device and
 * the database are fakes. The point of most cases is what the collector *refuses* to do —
 * invent a child, substitute a sibling, or advance a baseline it could not read.
 */
class ScreenTimeUsageCollectionCoordinatorTest {

    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    /** A fixture whose account already has the given target stored. */
    private fun configured(
        accountId: Long? = 1L,
        activeChildId: Long? = 10L,
        childrenByAccount: Map<Long, List<Long>> = mapOf(1L to listOf(10L, 11L)),
    ): CoordinatorFixture {
        val fixture = CoordinatorFixture(
            accounts = FakeAccounts(accountId),
            children = FakeChildren(childrenByAccount),
        )
        if (accountId != null && activeChildId != null) {
            runBlocking { fixture.activeChild.setActiveChildId(accountId, activeChildId) }
        }
        return fixture
    }

    private fun collect(fixture: CoordinatorFixture) = runBlocking { fixture.coordinator.collect() }

    // ---- target resolution --------------------------------------------------

    @Test
    fun noAccountCollectsNothing() {
        val fixture = configured(accountId = null)

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.NoAccount, collect(fixture))
        assertEquals(0, fixture.usage.writes.size)
    }

    @Test
    fun noActiveChildCollectsNothing() {
        val fixture = configured(activeChildId = null)

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.NoActiveChild, collect(fixture))
        assertEquals("no child may be invented", 0, fixture.usage.writes.size)
        assertEquals("and the device must not even be read", 0, fixture.source.queryCount)
    }

    @Test
    fun anUnknownActiveChildIsRejectedAndNothingIsCollected() {
        // Stored target 99 is not a child of this account.
        val fixture = configured(activeChildId = 99L)

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.ActiveChildNotOwned(99L), collect(fixture))
        assertEquals(0, fixture.usage.writes.size)
    }

    @Test
    fun anActiveChildOfAnotherAccountIsRejected() {
        val fixture = coordinatorForTwoAccounts()
        // Account 1's target is set to a child that belongs to account 2.
        runBlocking { fixture.activeChild.setActiveChildId(1L, 20L) }

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.ActiveChildNotOwned(20L), collect(fixture))
        assertEquals(0, fixture.usage.writes.size)
    }

    @Test
    fun aDeletedActiveChildIsNotReplacedByASibling() {
        val fixture = configured(activeChildId = 10L)
        fixture.children.removeChild(1L, 10L)

        val outcome = collect(fixture)

        assertEquals("the leftover id must not divert usage to child 11", ScreenTimeUsageCollectionCoordinator.Outcome.ActiveChildNotOwned(10L), outcome)
        assertNull("no usage for the deleted child", fixture.usage.writes.firstOrNull { it.childId == 10L })
        assertNull("and none for its sibling either", fixture.usage.writes.firstOrNull { it.childId == 11L })
    }

    @Test
    fun theCollectorUsesTheCallersStoredChildAndNeverInfersOne() {
        // child 10 is active even though child 11 sorts first in the list.
        val fixture = configured(activeChildId = 10L)
        fixture.source.report(youtube to 10_000L)
        collect(fixture)
        fixture.source.report(youtube to 15_000L)

        val outcome = collect(fixture)

        assertEquals(10L, (outcome as ScreenTimeUsageCollectionCoordinator.Outcome.Collected).childId)
        assertEquals(listOf(10L), fixture.usage.writes.map { it.childId }.distinct())
    }

    private fun coordinatorForTwoAccounts(): CoordinatorFixture = CoordinatorFixture(
        accounts = FakeAccounts(1L),
        children = FakeChildren(mapOf(1L to listOf(10L), 2L to listOf(20L))),
    )

    // ---- accounting semantics (delegated, not re-implemented) ---------------

    @Test
    fun firstObservationIsABaselineOnly() {
        val fixture = configured()
        fixture.source.report(youtube to 50_000L)

        val outcome = collect(fixture) as ScreenTimeUsageCollectionCoordinator.Outcome.Collected

        assertTrue(outcome.baselineOnly)
        assertEquals(0L, outcome.accountedMs)
        assertEquals(CoordinatorFixture.DATE_KEY, outcome.dateKey)
        assertEquals("a platform counter is not screen time", 0, fixture.usage.writes.size)
        assertEquals(50_000L, fixture.checkpoints.cumulativeMs(1L, 10L, fixture.dayRange, youtube))
    }

    @Test
    fun aPositiveDeltaAfterTheBaselineIsAccountedOnce() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)
        collect(fixture)
        fixture.source.report(youtube to 15_000L)

        collect(fixture)

        assertEquals(5_000L, fixture.usage.totalFor(10L))
        assertEquals(15_000L, fixture.checkpoints.cumulativeMs(1L, 10L, fixture.dayRange, youtube))
    }

    @Test
    fun anEqualObservationAddsZero() {
        val fixture = configured()
        fixture.source.report(youtube to 15_000L)
        collect(fixture)

        val outcome = collect(fixture) as ScreenTimeUsageCollectionCoordinator.Outcome.Collected

        assertTrue(outcome.baselineOnly)
        assertEquals(0L, fixture.usage.totalFor(10L))
    }

    @Test
    fun anEmptySuccessfulObservationKeepsTheCheckpoint() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)
        collect(fixture)
        // A successful query that reports nothing: no observation, not "everything vanished".
        fixture.source.report()

        collect(fixture)

        assertEquals(
            "an empty success must not erase the baseline",
            10_000L,
            fixture.checkpoints.cumulativeMs(1L, 10L, fixture.dayRange, youtube),
        )
        assertEquals(0L, fixture.usage.totalFor(10L))
    }

    // ---- failure / access ---------------------------------------------------

    @Test
    fun unavailableUsageAccessWritesNothingAndDoesNotAdvanceTheBaseline() {
        val fixture = configured()
        fixture.source.available = false
        fixture.source.report(youtube to 50_000L)

        assertEquals(
            ScreenTimeUsageCollectionCoordinator.Outcome.UsageAccessUnavailable,
            collect(fixture),
        )
        assertEquals(0, fixture.usage.writes.size)
        assertNull(fixture.checkpoints.cumulativeMs(1L, 10L, fixture.dayRange, youtube))
    }

    @Test
    fun aQueryFailureIsReportedAndPreservesTheBaseline() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)
        collect(fixture)

        fixture.source.failWith = IllegalStateException("platform refused")
        val outcome = collect(fixture)

        assertEquals(ScreenTimeUsageCollectionCoordinator.Outcome.Failed("IllegalStateException"), outcome)
        assertEquals("the baseline must survive a failure", 10_000L, fixture.checkpoints.cumulativeMs(1L, 10L, fixture.dayRange, youtube))
        assertEquals(0L, fixture.usage.totalFor(10L))
    }

    @Test
    fun aFailedUsageWriteDoesNotAdvanceTheBaseline() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)
        collect(fixture)

        fixture.source.report(youtube to 15_000L)
        fixture.usage.failAddUsageWith = IllegalStateException("disk full")
        val outcome = collect(fixture)

        assertTrue(outcome is ScreenTimeUsageCollectionCoordinator.Outcome.Failed)
        assertEquals(
            "a failed accounting write must not silently advance the baseline",
            10_000L,
            fixture.checkpoints.cumulativeMs(1L, 10L, fixture.dayRange, youtube),
        )
    }

    @Test
    fun noFailureEscapesTheCoordinator() {
        val fixture = configured()
        fixture.source.failWith = RuntimeException("unexpected")
        // A throw must be contained: it must never take down the runtime that drives this.
        val outcome = collect(fixture)

        assertTrue("a throw must surface as Failed, not escape", outcome is ScreenTimeUsageCollectionCoordinator.Outcome.Failed)
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun differentChildrenRemainIsolated() {
        val fixture = CoordinatorFixture(children = FakeChildren(mapOf(1L to listOf(10L, 11L))))
        fixture.source.report(youtube to 10_000L)

        runBlocking { fixture.activeChild.setActiveChildId(1L, 10L) }
        collect(fixture)
        fixture.source.report(youtube to 30_000L)
        collect(fixture)

        fixture.source.report(youtube to 10_000L)
        runBlocking { fixture.activeChild.setActiveChildId(1L, 11L) }
        collect(fixture)

        assertEquals("only the active child receives usage", 20_000L, fixture.usage.totalFor(10L))
        assertEquals("the other child saw only its own baseline", 0L, fixture.usage.totalFor(11L))
    }

    @Test
    fun switchingTheActiveChildTargetsTheNewChildWithoutRewritingHistory() {
        val fixture = CoordinatorFixture(children = FakeChildren(mapOf(1L to listOf(10L, 11L))))
        fixture.source.report(youtube to 10_000L)
        runBlocking { fixture.activeChild.setActiveChildId(1L, 10L) }
        collect(fixture)
        fixture.source.report(youtube to 15_000L)
        collect(fixture)

        // The parent switches the target to child 11.
        runBlocking { fixture.activeChild.setActiveChildId(1L, 11L) }
        val outcome = collect(fixture)

        assertEquals(11L, (outcome as ScreenTimeUsageCollectionCoordinator.Outcome.Collected).childId)
        assertEquals("earlier rows for child 10 are untouched", 5_000L, fixture.usage.totalFor(10L))
        assertEquals("child 11 starts from a baseline", 0L, fixture.usage.totalFor(11L))
    }

    @Test
    fun differentAccountsRemainIsolated() {
        val fixture = CoordinatorFixture(
            accounts = FakeAccounts(1L),
            children = FakeChildren(mapOf(1L to listOf(10L), 2L to listOf(20L))),
        )
        fixture.source.report(youtube to 10_000L)
        runBlocking {
            fixture.activeChild.setActiveChildId(1L, 10L)
            fixture.activeChild.setActiveChildId(2L, 20L)
        }
        collect(fixture)
        fixture.source.report(youtube to 15_000L)
        collect(fixture)

        // Switch accounts: account 2 has its own target and its own untouched baseline.
        fixture.accounts.signIn(2L)
        val outcome = collect(fixture)

        assertEquals(20L, (outcome as ScreenTimeUsageCollectionCoordinator.Outcome.Collected).childId)
        assertEquals("account 1's rows are untouched", 5_000L, fixture.usage.totalFor(10L))
        assertEquals("account 2's child starts fresh", 0L, fixture.usage.totalFor(20L))
        assertTrue("no row crossed accounts", fixture.usage.writes.all { it.accountId == 1L || it.childId == 20L })
    }

    @Test
    fun differentPackagesRemainIsolated() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L, tiktok to 1_000L)
        collect(fixture)
        fixture.source.report(youtube to 15_000L, tiktok to 3_000L)

        collect(fixture)

        assertEquals(listOf(youtube, tiktok), fixture.usage.writes.map { it.packageName }.sorted())
        assertEquals(2_000L, fixture.usage.writes.first { it.packageName == tiktok }.deltaMs)
        assertEquals(5_000L, fixture.usage.writes.first { it.packageName == youtube }.deltaMs)
    }

    // ---- concurrency --------------------------------------------------------

    @Test
    fun concurrentCollectionsDoNotDoubleCount() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)

        // Ten overlapping calls; the coordinator serializes them, so exactly one establishes
        // the baseline and the rest see an unchanged counter.
        val rows = runBlocking {
            coroutineScope {
                (1..10).map { async { fixture.coordinator.collect() } }.awaitAll()
            }
            fixture.checkpoints.checkpointsForWindow(1L, 10L, UsageSourceId.USAGE_STATS, fixture.dayRange).size
        }

        assertEquals("no duplicate accounting", 0L, fixture.usage.totalFor(10L))
        assertEquals("and exactly one stored baseline", 1, rows)
    }

    @Test
    fun concurrentCollectionsAfterABaselineAccountTheDeltaOnce() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)
        collect(fixture)
        fixture.source.report(youtube to 25_000L)

        runBlocking {
            coroutineScope {
                (1..10).map { async { fixture.coordinator.collect() } }.awaitAll()
            }
        }

        assertEquals("the 15s delta must be counted exactly once", 15_000L, fixture.usage.totalFor(10L))
    }

    // ---- window -------------------------------------------------------------

    @Test
    fun theWindowIsTheLocalDayAndNeverCrossesMidnight() {
        val fixture = configured()
        fixture.source.report(youtube to 10_000L)

        collect(fixture)

        val expected = uz.faceguard.app.domain.screentime.UsageDateKey.dayRange(fixture.now, ZoneOffset.UTC)
        assertEquals(CoordinatorFixture.DAY_START, expected.startTimeMs)
        assertEquals(CoordinatorFixture.DAY_END, expected.endTimeMs)
        assertEquals(CoordinatorFixture.DATE_KEY, (collect(fixture) as ScreenTimeUsageCollectionCoordinator.Outcome.Collected).dateKey)
    }
}
