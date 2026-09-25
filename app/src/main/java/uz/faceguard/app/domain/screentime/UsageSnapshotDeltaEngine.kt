package uz.faceguard.app.domain.screentime

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 Step 1B-5: the deterministic snapshot -> delta calculation.
 *
 * Takes two observations of the *same, caller-anchored* window and says exactly how much
 * usage each package accrued between them. It is pure: no Android, no Room, no clock, no
 * scheduler, and no persistence of its own — the caller supplies both snapshots. The zone
 * is injected because attributing a delta to a calendar day needs one, and there is no
 * `systemDefault()` hidden inside, so the same inputs always produce the same result.
 *
 * Two safety rules are structural rather than conventional:
 *  - **the window is never silently re-interpreted.** Different windows are not subtracted
 *    at all ([UsageSnapshotComparison.DifferentObservationWindow]); a snapshot whose window
 *    crosses midnight cannot be attributed to a day and is rejected, because a snapshot is
 *    a total and totals cannot be split (only real intervals can, via
 *    [UsageAccounting.splitAtMidnight], which is not re-implemented here).
 *  - **no decreasing counter can become negative usage.** A decrement is classified as
 *    [PackageUsageDelta.ResetBaseline] and contributes the observed value.
 */
@Singleton
class UsageSnapshotDeltaEngine @Inject constructor(private val zone: ZoneId) {

    /**
     * Compares [current] against [previous] (null for the first observation ever).
     *
     * @throws IllegalArgumentException when [current]'s window spans more than one local
     *   calendar day, i.e. when the delta could not be attributed to exactly one day.
     */
    fun compare(previous: UsageSnapshot?, current: UsageSnapshot): UsageSnapshotComparison {
        val dateKey = dateKeyForAttributableDay(current.range)

        if (previous != null && previous.range != current.range) {
            // A different window measures a different quantity; subtracting would be a
            // silent, wrong delta. Report it instead.
            return UsageSnapshotComparison.DifferentObservationWindow
        }

        val deltas = current.packages.map { packageName ->
            comparePackage(
                packageName = packageName,
                previousMs = previous?.takeIf { it.hasPackage(packageName) }?.cumulativeMsFor(packageName),
                currentMs = current.cumulativeMsFor(packageName),
            )
        }
        return UsageSnapshotComparison.Compared(dateKey, deltas)
    }

    private fun comparePackage(
        packageName: String,
        previousMs: Long?,
        currentMs: Long,
    ): PackageUsageDelta = when {
        // No baseline: either the first ever observation, or a package that only appears
        // now. Neither can be attributed to this interval (see NewBaseline).
        previousMs == null -> PackageUsageDelta.NewBaseline(packageName, currentMs)

        currentMs > previousMs -> PackageUsageDelta.Accumulated(
            packageName,
            Math.subtractExact(currentMs, previousMs),
        )

        currentMs == previousMs -> PackageUsageDelta.Unchanged(packageName)

        // A decreasing counter is a source reset, never negative usage.
        else -> PackageUsageDelta.ResetBaseline(packageName, currentMs)
    }

    /**
     * The single local day [range] can be attributed to.
     *
     * Half-open `[start, end)`: the last instant observed is `end - 1`, so a window ending
     * exactly at midnight still belongs entirely to the day it started in. The key comes
     * from the existing [UsageDateKey], so no second date format exists.
     */
    private fun dateKeyForAttributableDay(range: UsageRange): String {
        val startDay = Instant.ofEpochMilli(range.startTimeMs).atZone(zone).toLocalDate()
        val lastInstantDay = Instant.ofEpochMilli(range.endTimeMs - 1L).atZone(zone).toLocalDate()
        require(startDay == lastInstantDay) {
            "a usage snapshot must cover one local calendar day to be attributable, " +
                "but ${range.startTimeMs}..${range.endTimeMs} spans $startDay..$lastInstantDay in $zone"
        }
        return UsageDateKey.of(range.startTimeMs, zone)
    }
}
