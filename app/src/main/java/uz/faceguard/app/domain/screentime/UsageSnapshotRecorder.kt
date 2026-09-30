package uz.faceguard.app.domain.screentime

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 Step 1B-5: the orchestration seam that connects the device's usage snapshots to
 * the accounting pipeline.
 *
 * ```
 * AppUsageSource ──▶ UsageSnapshot ──▶ UsageSnapshotDeltaEngine
 *                                            │  (explicit outcome)
 *                                            ▼
 *                                     UsageDelta ──▶ ScreenTimeUsageAccounting
 *                                                        ──▶ ScreenTimeUsageRepository
 * ```
 *
 * It decides nothing on its own: which window to observe, which child the usage belongs to,
 * and how a package is categorized are all supplied by the caller. It owns no scope, keeps
 * no state, reads no device clock, and touches neither `UsageStatsManager` nor Room.
 *
 * Boundaries that are structural here (Step 1B-5 §16, §17):
 *  - **no child identity is inferred.** A usage source maps package -> duration and nothing
 *    more, so `accountId`/`childId` must be passed in. Device usage can therefore never be
 *    silently attributed to a child by this layer.
 *  - **no category policy is invented.** `categoryOf` is the caller's mapping; this layer
 *    does not contain a classifier.
 *
 * Limitation, and the reason this layer takes `previous` as a parameter: durable checkpoints
 * are **not** implemented in this step. Persisting the last snapshot needs a dedicated table
 * (the existing `daily_app_usage` holds *consumed* screen time, and storing a cumulative
 * platform counter in `usedMs` would corrupt every aggregate). Until that persistence step
 * exists, the caller must hold the previous snapshot; a process restart simply starts a new
 * baseline ([PackageUsageDelta.NewBaseline]), which never invents usage.
 */
@Singleton
class UsageSnapshotRecorder @Inject constructor(
    private val accounting: ScreenTimeUsageAccounting,
    private val engine: UsageSnapshotDeltaEngine,
) {

    /**
     * Compares [current] against [previous] and accounts for everything that accrued.
     *
     * @param categoryOf the caller's package -> [AppCategory] mapping; reusable categories
     *   come from [AppCategories], and this layer adds no classification of its own.
     * @return the comparison outcome, so the caller can tell what happened — including
     *   [UsageSnapshotComparison.DifferentObservationWindow], in which case nothing was
     *   written.
     */
    suspend fun record(
        accountId: Long,
        childId: Long,
        previous: UsageSnapshot?,
        current: UsageSnapshot,
        categoryOf: (String) -> AppCategory,
    ): UsageSnapshotComparison {
        val comparison = engine.compare(previous, current)
        if (comparison !is UsageSnapshotComparison.Compared) return comparison

        val deltas = comparison.deltas
            .filter { it.deltaMs > 0L }
            .map { delta ->
                UsageDelta(
                    dateKey = comparison.dateKey,
                    packageName = delta.packageName,
                    category = categoryOf(delta.packageName),
                    elapsedMs = delta.deltaMs,
                )
            }
        if (deltas.isNotEmpty()) {
            accounting.recordDeltas(accountId, childId, deltas)
        }
        return comparison
    }
}
