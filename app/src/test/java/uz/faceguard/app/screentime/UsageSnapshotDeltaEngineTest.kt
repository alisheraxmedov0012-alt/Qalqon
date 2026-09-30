package uz.faceguard.app.screentime

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.PackageUsageDelta
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshot
import uz.faceguard.app.domain.screentime.UsageSnapshotComparison
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine

/**
 * Phase 4 Step 1B-5 (pure JVM): snapshot -> delta semantics.
 *
 * Every rule here is deterministic and clock-free: the caller supplies both snapshots and
 * the zone, so nothing depends on the device, the time, or a database.
 */
class UsageSnapshotDeltaEngineTest {

    private val zone = ZoneOffset.UTC
    private val engine = UsageSnapshotDeltaEngine(zone)

    private val day = 86_400_000L
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** The whole of 2026-09-25 UTC, the caller-anchored observation window. */
    private fun window(startIso: String = "2026-09-25T00:00:00Z", endIso: String = "2026-09-26T00:00:00Z") =
        UsageRange(at(startIso), at(endIso))

    private fun snapshot(vararg samples: Pair<String, Long>, range: UsageRange = window()) =
        UsageSnapshot.of(range, samples.map { (pkg, ms) -> AppUsageSample(pkg, ms) })

    private fun compared(previous: UsageSnapshot?, current: UsageSnapshot): UsageSnapshotComparison.Compared =
        engine.compare(previous, current) as UsageSnapshotComparison.Compared

    private fun deltaOf(comparison: UsageSnapshotComparison.Compared, packageName: String): PackageUsageDelta =
        comparison.deltas.single { it.packageName == packageName }

    // ---- delta rules --------------------------------------------------------

    @Test
    fun sameSnapshot_returnsZero() {
        val snapshot = snapshot(youtube to 10_000L)

        val comparison = compared(snapshot, snapshot)

        assertEquals(PackageUsageDelta.Unchanged(youtube), deltaOf(comparison, youtube))
        assertEquals(0L, deltaOf(comparison, youtube).deltaMs)
        assertEquals("2026-09-25", comparison.dateKey)
    }

    @Test
    fun currentGreaterThanPrevious_returnsPositiveDelta() {
        val comparison = compared(snapshot(youtube to 10_000L), snapshot(youtube to 15_000L))

        assertEquals(PackageUsageDelta.Accumulated(youtube, 5_000L), deltaOf(comparison, youtube))
    }

    @Test
    fun currentEqualPrevious_returnsZero() {
        val comparison = compared(snapshot(youtube to 12_345L), snapshot(youtube to 12_345L))

        assertEquals(0L, deltaOf(comparison, youtube).deltaMs)
        assertTrue("an unchanged counter is Unchanged, not a zero Accumulated", deltaOf(comparison, youtube) is PackageUsageDelta.Unchanged)
    }

    @Test
    fun currentLowerThanPrevious_doesNotCreateNegativeUsage() {
        val comparison = compared(snapshot(youtube to 6_000_000L), snapshot(youtube to 1_200_000L))

        assertTrue(
            "no package may ever carry negative usage",
            comparison.deltas.all { it.deltaMs >= 0L },
        )
    }

    @Test
    fun currentLowerThanPrevious_usesExplicitResetSemantics() {
        // 100m -> 20m cannot be -80m. The counter restarted, so what it shows now accrued
        // after the reset, i.e. after the previous observation.
        val comparison = compared(snapshot(youtube to 6_000_000L), snapshot(youtube to 1_200_000L))

        assertEquals(PackageUsageDelta.ResetBaseline(youtube, 1_200_000L), deltaOf(comparison, youtube))
        assertEquals(1_200_000L, deltaOf(comparison, youtube).deltaMs)
    }

    @Test
    fun aCounterDroppingToNothingContributesNothing() {
        // A reset to zero is possible in principle; it must not produce a row.
        val comparison = compared(
            snapshot(youtube to 10_000L),
            UsageSnapshot.empty(window()),
        )

        assertEquals("a package absent from current is not observed", emptyList<PackageUsageDelta>(), comparison.deltas)
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun negativePreviousRejected() {
        assertThrows(IllegalArgumentException::class.java) { AppUsageSample(youtube, -1L) }
    }

    @Test
    fun negativeCurrentRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsageSnapshot.of(window(), listOf(AppUsageSample(youtube, -5L)))
        }
    }

    @Test
    fun blankPackageRejected() {
        assertThrows(IllegalArgumentException::class.java) { AppUsageSample("  ", 1_000L) }
    }

    // ---- package isolation --------------------------------------------------

    @Test
    fun samePackageOnly() {
        val comparison = compared(
            snapshot(youtube to 10_000L),
            snapshot(youtube to 15_000L),
        )

        assertEquals(listOf(youtube), comparison.deltas.map { it.packageName })
    }

    @Test
    fun differentPackagesRemainIsolated() {
        val comparison = compared(
            snapshot(youtube to 10_000L, tiktok to 4_000L),
            snapshot(youtube to 15_000L, tiktok to 1_000L),
        )

        assertEquals(PackageUsageDelta.Accumulated(youtube, 5_000L), deltaOf(comparison, youtube))
        assertEquals(PackageUsageDelta.ResetBaseline(tiktok, 1_000L), deltaOf(comparison, tiktok))
        assertEquals("one delta per package", 2, comparison.deltas.size)
    }

    @Test
    fun newPackageHasExplicitBaselineSemantics() {
        val comparison = compared(
            snapshot(youtube to 10_000L),
            snapshot(youtube to 15_000L, tiktok to 5_000L),
        )

        // No baseline existed for tiktok, so none of its value may be attributed to this
        // interval; it becomes the baseline instead.
        assertEquals(PackageUsageDelta.NewBaseline(tiktok, 5_000L), deltaOf(comparison, tiktok))
        assertEquals(0L, deltaOf(comparison, tiktok).deltaMs)
        assertEquals(5_000L, deltaOf(comparison, youtube).deltaMs)
    }

    @Test
    fun missingCurrentPackageDoesNotInventUsage() {
        val comparison = compared(
            snapshot(youtube to 10_000L, tiktok to 5_000L),
            snapshot(youtube to 15_000L),
        )

        // tiktok was not observed at all in the current snapshot: that is "no observation",
        // not "zero cumulative usage", so nothing is claimed for it either way.
        assertEquals(listOf(youtube), comparison.deltas.map { it.packageName })
        assertEquals(5_000L, deltaOf(comparison, youtube).deltaMs)
    }

    @Test
    fun theVeryFirstObservationIsAllBaseline() {
        val comparison = compared(null, snapshot(youtube to 10_000L, tiktok to 5_000L))

        assertEquals(
            listOf(
                PackageUsageDelta.NewBaseline(youtube, 10_000L),
                PackageUsageDelta.NewBaseline(tiktok, 5_000L),
            ),
            comparison.deltas,
        )
        assertEquals("the first observation accounts for nothing", 0L, comparison.deltas.sumOf { it.deltaMs })
    }

    @Test
    fun duplicateSamplesAreNormalizedBeforeDelta() {
        // Raw rows for one package are summed first, then compared.
        val current = UsageSnapshot.of(
            window(),
            listOf(AppUsageSample(youtube, 9_000L), AppUsageSample(youtube, 6_000L)),
        )

        val comparison = compared(snapshot(youtube to 10_000L), current)

        assertEquals(PackageUsageDelta.Accumulated(youtube, 5_000L), deltaOf(comparison, youtube))
    }

    @Test
    fun inputOrderDoesNotMatter() {
        val previousA = snapshot(youtube to 10_000L, tiktok to 1_000L)
        val previousB = snapshot(tiktok to 1_000L, youtube to 10_000L)
        val currentA = snapshot(youtube to 15_000L, tiktok to 3_000L)
        val currentB = snapshot(tiktok to 3_000L, youtube to 15_000L)

        val expected = compared(previousA, currentA)

        assertEquals(expected, compared(previousB, currentB))
        assertEquals(expected, compared(previousA, currentB))
        assertEquals(
            "output is ordered by package name",
            listOf(youtube, tiktok),
            expected.deltas.map { it.packageName },
        )
    }

    // ---- overflow / determinism --------------------------------------------

    @Test
    fun overflowIsRejected() {
        // Summing duplicate rows can overflow; the snapshot normalizer refuses it.
        assertThrows(IllegalArgumentException::class.java) {
            UsageSnapshot.of(
                window(),
                listOf(AppUsageSample(youtube, Long.MAX_VALUE), AppUsageSample(youtube, 1L)),
            )
        }
    }

    @Test
    fun veryLargeButValidValuesStayExact() {
        val comparison = compared(
            snapshot(youtube to Long.MAX_VALUE - 10L),
            snapshot(youtube to Long.MAX_VALUE),
        )

        assertEquals(10L, deltaOf(comparison, youtube).deltaMs)
    }

    @Test
    fun deterministicResults() {
        val previous = snapshot(youtube to 10_000L, tiktok to 2_000L)
        val current = snapshot(youtube to 15_000L, tiktok to 1_000L, duolingo to 500L)

        val first = engine.compare(previous, current)
        val second = engine.compare(previous, current)
        val third = UsageSnapshotDeltaEngine(ZoneOffset.UTC).compare(previous, current)

        assertEquals(first, second)
        assertEquals(first, third)
    }

    // ---- range identity -----------------------------------------------------

    @Test
    fun sameRangeCanBeCompared() {
        val comparison = compared(
            snapshot(youtube to 10_000L),
            snapshot(youtube to 15_000L),
        )

        assertEquals(5_000L, deltaOf(comparison, youtube).deltaMs)
    }

    @Test
    fun differentRangeCannotBeBlindlySubtracted() {
        // 00:00-12:00 vs 00:00-24:00: two different quantities. Subtracting them would be a
        // silent, wrong delta, so the comparison refuses instead.
        val morning = snapshot(youtube to 30_000L, range = window(endIso = "2026-09-25T12:00:00Z"))
        val wholeDay = snapshot(youtube to 90_000L, range = window())

        assertEquals(
            UsageSnapshotComparison.DifferentObservationWindow,
            engine.compare(morning, wholeDay),
        )
        assertEquals(
            UsageSnapshotComparison.DifferentObservationWindow,
            engine.compare(wholeDay, morning),
        )
    }

    @Test
    fun reversedRangeRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsageRange(2_000L, 1_000L) }
    }

    @Test
    fun emptyRangeRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsageRange(1_000L, 1_000L) }
    }

    @Test
    fun differentDayIdentityIsNotMixed() {
        val firstDay = snapshot(youtube to 10_000L, range = window())
        val secondDay = snapshot(
            youtube to 20_000L,
            range = window("2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z"),
        )

        assertEquals(
            "a different day is a different window",
            UsageSnapshotComparison.DifferentObservationWindow,
            engine.compare(firstDay, secondDay),
        )
    }

    @Test
    fun aWindowCrossingMidnightCannotBeAttributedToADay() {
        // A snapshot is a total per package, and a total cannot be split; only real
        // intervals can (UsageAccounting.splitAtMidnight). So this is rejected, not
        // silently filed under one day.
        val crossing = UsageRange(at("2026-09-25T23:00:00Z"), at("2026-09-26T01:00:00Z"))

        assertThrows(IllegalArgumentException::class.java) {
            engine.compare(null, snapshot(youtube to 1_000L, range = crossing))
        }
    }

    @Test
    fun aWindowEndingExactlyAtMidnightBelongsToTheDayItStartedIn() {
        val comparison = compared(
            snapshot(youtube to 1_000L),
            snapshot(youtube to 2_000L),
        )

        assertEquals("2026-09-25", comparison.dateKey)
    }

    @Test
    fun theDateKeyIsDerivedFromTheWindowInTheInjectedZone() {
        // 20:00 UTC is already the 26th in Tashkent (UTC+5): the zone decides the day, and
        // it is the injected zone, never an ambient default.
        val range = UsageRange(at("2026-09-25T23:00:00Z"), at("2026-09-26T00:00:00Z"))
        val tashkent = UsageSnapshotDeltaEngine(java.time.ZoneId.of("Asia/Tashkent"))

        val comparison = tashkent.compare(
            UsageSnapshot.empty(range),
            UsageSnapshot.of(range, listOf(AppUsageSample(youtube, 1_000L))),
        ) as UsageSnapshotComparison.Compared

        assertEquals("2026-09-26", comparison.dateKey)
    }
}
