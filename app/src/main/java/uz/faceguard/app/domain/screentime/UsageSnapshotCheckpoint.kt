package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-6: persistent snapshot checkpoints, and the transaction boundary the
 * usage/checkpoint write pair needs.
 *
 * Why this exists: a UsageStats counter only becomes a delta when it can be compared with
 * the value seen last time. Holding that value in memory is enough until the process is
 * killed — which is exactly when screen-time accounting must not lose its place. The
 * checkpoint is that "last time", stored.
 *
 * A checkpoint is **not** consumed screen time. [cumulativeForegroundMs] is the platform's
 * `totalTimeInForeground` for the window — a baseline that is only ever subtracted, never
 * added up. `daily_app_usage` holds consumed time; the two must never share storage.
 *
 * Pure domain: no Android, no Room, no clock.
 */

/**
 * Which device usage source a checkpoint was observed from.
 *
 * An enum rather than free text so the source is deterministic and a typo cannot silently
 * create a second, parallel set of baselines. A future source (for example one built on
 * `queryEvents`) adds a value here rather than inventing a string; nothing else implements
 * it in this step.
 */
enum class UsageSourceId { USAGE_STATS }

/**
 * The counter observed for one package, in one window, at one moment.
 *
 * The identity is (account, child, source, window, package): a checkpoint can never leak
 * across an account, a child, a package, an observation window or a source, so two
 * different observations can never overwrite one another.
 */
class UsageSnapshotCheckpoint(
    val accountId: Long,
    val childId: Long,
    val source: UsageSourceId,
    val range: UsageRange,
    val packageName: String,
    /** Platform cumulative foreground ms for [range]; never consumed usage. */
    val cumulativeForegroundMs: Long,
    /** When this counter was read (wall clock), which is unrelated to its value. */
    val observedAtMs: Long,
) {

    init {
        require(accountId > 0L) { "accountId must be positive, was $accountId" }
        require(childId > 0L) { "childId must be positive, was $childId" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(cumulativeForegroundMs >= 0L) {
            "cumulativeForegroundMs must not be negative, was $cumulativeForegroundMs"
        }
        require(observedAtMs >= 0L) { "observedAtMs must not be negative, was $observedAtMs" }
    }

    /** Same identity means the same storage row; anything else is a different baseline. */
    fun hasSameIdentityAs(other: UsageSnapshotCheckpoint): Boolean =
        accountId == other.accountId &&
            childId == other.childId &&
            source == other.source &&
            range == other.range &&
            packageName == other.packageName
}

/**
 * Storage for snapshot checkpoints. Implementations are account + child scoped in every
 * operation, so no caller can read or overwrite another child's baselines.
 */
interface UsageSnapshotCheckpointRepository {

    suspend fun checkpoint(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
        packageName: String,
    ): UsageSnapshotCheckpoint?

    /**
     * Every checkpoint of one window — i.e. the previous snapshot for that window. A row
     * whose stored source is unrecognizable is treated as absent (fail-safe), so a damaged
     * row can never be mistaken for a real baseline.
     */
    suspend fun checkpointsForWindow(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
    ): List<UsageSnapshotCheckpoint>

    /** Overwrites only rows with the same identity; never another account/child/window. */
    suspend fun save(checkpoints: List<UsageSnapshotCheckpoint>)

    suspend fun delete(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
        packageName: String,
    )

    suspend fun deleteForChild(accountId: Long, childId: Long)

    suspend fun deleteForAccount(accountId: Long)
}

/**
 * A unit of work spanning more than one repository.
 *
 * Accounting a snapshot performs two writes that must not be observed separately: the usage
 * deltas into `daily_app_usage`, and the advanced baselines into the checkpoint table. If
 * only the first succeeded, the next run would re-count the same interval; if only the
 * second succeeded, that interval would be lost. Both must land, or neither — so the domain
 * expresses the boundary here and the data layer supplies the real (Room) implementation.
 *
 * This is the reason the recorder takes a transaction rather than calling the two
 * repositories back to back. It is not a general-purpose transaction API: it exists for
 * exactly one pair of writes.
 */
interface UsageAccountingTransaction {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
