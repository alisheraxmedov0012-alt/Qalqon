package uz.faceguard.app.domain.screentime

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one snapshot ingestion did, so a caller can tell "we could not look" apart from
 * "we looked and nothing accrued".
 */
sealed interface SnapshotAccountingOutcome {

    /** Usage access is not granted, so nothing was observed and nothing was written. */
    data object UsageAccessUnavailable : SnapshotAccountingOutcome

    /** The window cannot be attributed to one local day, so nothing was written. */
    data object WindowNotAttributable : SnapshotAccountingOutcome

    /**
     * The observation was accounted for.
     *
     * [deltas] is empty when nothing accrued — the very first observation of a window, or a
     * counter that had not moved — which is a normal result, not a failure.
     */
    data class Accounted(
        val dateKey: String,
        val packagesObserved: Int,
        val deltas: List<UsageDelta>,
    ) : SnapshotAccountingOutcome
}

/**
 * Phase 4 Step 1B-6: snapshot ingestion backed by a persistent checkpoint.
 *
 * ```
 * AppUsageSource ─▶ UsageSnapshot ─▶ CheckpointRepository.read(previous)
 *                                          │
 *                                          ▼
 *                                   UsageSnapshotDeltaEngine ─▶ UsageDelta
 *                                          │                       │
 *                                          ▼                       ▼
 *                                   save(current) ◀── ScreenTimeUsageAccounting
 * ```
 *
 * The reason this class exists: [UsageSnapshotRecorder] can only compare two snapshots it is
 * handed, so after a process restart it has no baseline and accounts for nothing (a
 * deliberate, safe choice — see that class). This one reads the baseline back from storage,
 * so the delta survives a restart instead of being lost.
 *
 * Two properties it guarantees:
 *  - **one day per observation.** The window must be attributable to a single local day
 *    (decided by [UsageSnapshotDeltaEngine.attributableDateKey], not re-implemented here),
 *    which is what lets a delta be filed under a day key at all.
 *  - **the two writes are one unit.** Usage deltas and the advanced baselines go through
 *    [UsageAccountingTransaction] together, so a crash cannot leave the pair half-applied
 *    (which would either re-count an interval or lose it).
 *
 * It does not poll, schedule, enforce, or know about Android: it is invoked, and it returns.
 * Which window to observe, which child the usage belongs to, and how a package is
 * categorized are all the caller's decisions.
 *
 * @param sourceId which source these baselines belong to. It is fixed for the injected
 *   [AppUsageSource]; a future second source must be a distinct value so the two never share
 *   baselines.
 */
@Singleton
class PersistentUsageSnapshotRecorder @Inject constructor(
    private val source: AppUsageSource,
    private val engine: UsageSnapshotDeltaEngine,
    private val checkpointRepository: UsageSnapshotCheckpointRepository,
    private val accounting: ScreenTimeUsageAccounting,
    private val transaction: UsageAccountingTransaction,
    private val sourceId: UsageSourceId,
    private val clock: () -> Long,
) {

    /**
     * Observes [range] once, accounts for whatever accrued since the stored baseline, and
     * advances the baseline.
     *
     * @param categoryOf the caller's package -> [AppCategory] mapping; no classification
     *   happens here.
     */
    suspend fun ingest(
        accountId: Long,
        childId: Long,
        range: UsageRange,
        categoryOf: (String) -> AppCategory,
    ): SnapshotAccountingOutcome {
        // Fail fast on a window with no single day to attribute to, before anything is read
        // or written, so an unusable window can never advance a baseline.
        val dateKey = runCatching { engine.attributableDateKey(range) }.getOrNull()
            ?: return SnapshotAccountingOutcome.WindowNotAttributable

        // The device is read outside the transaction: it is a slow platform call and must
        // not hold a database transaction open.
        val query = source.queryUsage(range)
        if (query !is AppUsageQueryResult.Available) return SnapshotAccountingOutcome.UsageAccessUnavailable

        val current = UsageSnapshot.of(range, query.samples)
        val observedAtMs = clock()

        // Baseline read, comparison, usage write and baseline advance are one unit: reading
        // the baseline inside the transaction is what stops two concurrent ingestions of the
        // same window from both counting the same interval.
        return transaction.inTransaction {
            val comparison = engine.compare(previousSnapshot(accountId, childId, range), current)
            if (comparison is UsageSnapshotComparison.DifferentObservationWindow) {
                // Unreachable while the baseline is read by this exact window; handled
                // explicitly rather than assumed, and it writes nothing.
                return@inTransaction SnapshotAccountingOutcome.WindowNotAttributable
            }
            val compared = comparison as UsageSnapshotComparison.Compared

            val deltas = compared.deltas
                .filter { it.deltaMs > 0L }
                .map { delta ->
                    UsageDelta(
                        dateKey = compared.dateKey,
                        packageName = delta.packageName,
                        category = categoryOf(delta.packageName),
                        elapsedMs = delta.deltaMs,
                    )
                }
            if (deltas.isNotEmpty()) {
                accounting.recordDeltas(accountId, childId, deltas)
            }

            // Every package observed now becomes the baseline for next time. A package the
            // current snapshot does not mention is not observed, so its stored baseline is
            // left exactly as it was.
            checkpointRepository.save(
                current.samples.map { sample ->
                    UsageSnapshotCheckpoint(
                        accountId = accountId,
                        childId = childId,
                        source = sourceId,
                        range = range,
                        packageName = sample.packageName,
                        cumulativeForegroundMs = sample.foregroundMs,
                        observedAtMs = observedAtMs,
                    )
                },
            )

            SnapshotAccountingOutcome.Accounted(
                dateKey = compared.dateKey,
                packagesObserved = current.samples.size,
                deltas = deltas,
            )
        }
    }

    /**
     * The stored baseline for [range], expressed as a [UsageSnapshot] so it can be compared
     * directly — the stored counter is per package, with no span of its own.
     */
    private suspend fun previousSnapshot(
        accountId: Long,
        childId: Long,
        range: UsageRange,
    ): UsageSnapshot? {
        val stored = checkpointRepository.checkpointsForWindow(accountId, childId, sourceId, range)
        if (stored.isEmpty()) return null
        return UsageSnapshot.of(
            range,
            stored.map { AppUsageSample(it.packageName, it.cumulativeForegroundMs) },
        )
    }
}
