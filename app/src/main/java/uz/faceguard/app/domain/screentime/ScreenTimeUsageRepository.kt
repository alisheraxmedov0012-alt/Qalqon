package uz.faceguard.app.domain.screentime

import kotlinx.coroutines.flow.Flow

/**
 * Phase 4 Step 1B-2: the single write/read abstraction over per-app screen-time usage.
 *
 * This is *accounting only*: it stores and aggregates what a caller reports. It does
 * not measure usage (no `UsageStatsManager`), does not decide when a day rolls over
 * (no timer/scheduler), and does not enforce limits — the later Screen Time Engine
 * supplies the `dateKey` and the delta, and consumes the aggregates.
 *
 * Usage is keyed by (accountId, childId, dateKey, packageName), so a write can never
 * land on another account, child, day or package. Duration is always exact `Long`
 * milliseconds; nothing is rounded to minutes here.
 */
interface ScreenTimeUsageRepository {

    /**
     * Adds [deltaMs] to the (account, child, day, package) bucket, creating it when
     * absent. Accumulation is a single SQL-atomic increment, so concurrent reports
     * cannot lose an update.
     *
     * [deltaMs] must be positive and at most one day (see [MAX_DELTA_MS]); `0` is a
     * no-op, and a negative delta is rejected before it reaches the database. The DAO
     * keeps its own non-negative guard as the second line of defence.
     *
     * @param category the app's [AppCategory], resolved by the caller; stored as its
     *   stable `name` so reads and aggregates never disagree about the grouping.
     */
    suspend fun addUsage(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String,
        category: AppCategory,
        deltaMs: Long,
    )

    /** Usage of one app on one day, or `0L` when nothing was recorded. */
    suspend fun getAppUsageMs(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String,
    ): Long

    /** Total usage of one [category] on one day, or `0L` when nothing matched. */
    suspend fun getCategoryUsageMs(
        accountId: Long,
        childId: Long,
        dateKey: String,
        category: AppCategory,
    ): Long

    /** Total usage of every app on one day, or `0L` when nothing was recorded. */
    suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String): Long

    /** Every app with recorded usage on one day, ordered by package name. */
    suspend fun getDayUsage(accountId: Long, childId: Long, dateKey: String): List<AppUsage>

    /** [getDayUsage] as an observable stream; re-emits on every write to that day. */
    fun observeDayUsage(accountId: Long, childId: Long, dateKey: String): Flow<List<AppUsage>>

    companion object {
        /**
         * Upper bound for a single increment: one day. Usage is reported per scan, so
         * no legitimate delta exceeds this, and the bound keeps the running total far
         * from `Long` overflow (SQLite would silently widen an overflowing sum to
         * REAL, breaking the exact-millisecond invariant).
         */
        val MAX_DELTA_MS: Long = ScreenTimeLimits.MINUTES_PER_DAY * ScreenTimeLimits.MS_PER_MINUTE
    }
}
