package uz.faceguard.app.domain.screentime

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 Step 1B-3: the application-level entry point for usage accounting.
 *
 * It is the only place that knows both the domain maths ([UsageAccounting]) and the
 * persistence contract ([ScreenTimeUsageRepository]), so callers report spans and read
 * totals without ever handling deltas, day keys or validation themselves.
 *
 * What it deliberately is *not*: it does not observe the device (no `UsageStatsManager`,
 * no accessibility, no foreground service), does not own a coroutine scope, does not
 * schedule anything, and does not enforce limits. It has no state beyond its two
 * injected collaborators, so it is safe to share as a singleton.
 *
 * Important limitation, by design of this step: deduplication is *within a call*. The
 * same span reported twice in one batch is counted once, because spans are unioned per
 * app before writing ([UsageAccounting.deltasFor]). Recognising that a span was already
 * accounted for in an *earlier* call needs a persisted record of processed spans, which
 * would need a new table — deliberately out of scope here (no schema change in Step
 * 1B-3). Until such a step exists, callers must not re-report a span they already
 * reported; the union in [planDeltas] is what makes a batch safe to retry.
 */
@Singleton
class ScreenTimeUsageAccounting @Inject constructor(
    private val repository: ScreenTimeUsageRepository,
    private val zone: ZoneId,
) {

    /**
     * The deltas [intervals] would produce for the given [zone], without writing
     * anything. Exposed so a caller can inspect (or log) what it is about to account
     * for, and so the maths is testable without a database.
     */
    fun planDeltas(intervals: List<UsageInterval>): List<UsageDelta> =
        UsageAccounting.deltasFor(intervals, zone)

    /**
     * Accounts for one span. A span crossing local midnight is filed under both days.
     */
    suspend fun recordInterval(accountId: Long, childId: Long, interval: UsageInterval) {
        recordIntervals(accountId, childId, listOf(interval))
    }

    /**
     * Accounts for [intervals], unioning overlapping/duplicate spans per app first.
     *
     * Returns the deltas that were written, so the caller can tell exactly what was
     * accounted for.
     */
    suspend fun recordIntervals(
        accountId: Long,
        childId: Long,
        intervals: List<UsageInterval>,
    ): List<UsageDelta> {
        val deltas = planDeltas(intervals)
        return recordDeltas(accountId, childId, deltas)
    }

    /**
     * Accounts for already day-attributed [deltas] (for example produced by
     * [UsageSnapshotDeltaEngine] from two usage snapshots).
     *
     * The deltas carry their own day key, so this writes exactly what it is given and adds
     * nothing: every entry is positive by construction ([UsageDelta] rejects `0` and
     * negatives), so no phantom row and no negative usage can reach the repository.
     * Returns the deltas written.
     */
    suspend fun recordDeltas(
        accountId: Long,
        childId: Long,
        deltas: List<UsageDelta>,
    ): List<UsageDelta> {
        for (delta in deltas) {
            repository.addUsage(
                accountId = accountId,
                childId = childId,
                dateKey = delta.dateKey,
                packageName = delta.packageName,
                category = delta.category,
                deltaMs = delta.elapsedMs,
            )
        }
        return deltas
    }

    /**
     * One child's usage for one day, aggregated from what was persisted (apps, per-app
     * totals, per-category totals, grand total). Empty when nothing was recorded — a
     * zero-usage day is a value, not an error.
     */
    suspend fun usageFor(accountId: Long, childId: Long, dateKey: String): ChildDayUsage =
        ChildDayUsage(
            childId = childId,
            dateKey = dateKey,
            apps = repository.getDayUsage(accountId, childId, dateKey),
        )
}
