package uz.faceguard.app.domain.screentime

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository

/**
 * Phase 4 Step 1B-7: the production caller for usage collection.
 *
 * It answers exactly one question — *"account this device's usage now?"* — and it is the
 * only place that decides that. Per §10 the decision is a chain, and every link is
 * explicit:
 *
 * ```
 * current account ─▶ stored active child ─▶ child exists AND belongs to the account
 *        ─▶ local day window ─▶ PersistentUsageSnapshotRecorder ─▶ accounting
 * ```
 *
 * It stops at delegation on purpose: [PersistentUsageSnapshotRecorder] already owns the
 * whole lower pipeline (access check, query, snapshot, delta, checkpoint transaction), so
 * querying the source here as well would double-read the device for nothing — which is
 * also the battery-conscious choice.
 *
 * Everything it refuses to do is as important as what it does:
 *  - it never invents a child. No active child, or an id that no longer resolves to a
 *    child of this account, means **nothing is collected** — not the first child, not a
 *    recognised face, not a UI selection. A deleted child's leftover id therefore cannot
 *    divert usage onto a sibling.
 *  - it never picks its own window: the window is the local day containing [clock]'s
 *    instant, built by the existing [UsageDateKey.dayRange] (single day, never
 *    midnight-crossing, no hard-coded zone).
 *  - it holds no limits, no remaining time, no enforcement and no schedule: it only
 *    accounts for what happened.
 *  - it never lets a failure escape. A thrown error is reported as [Outcome.Failed] so a
 *    disk or platform fault cannot take down the protection runtime that drives it; the
 *    checkpoint is untouched, so the next tick retries from the same baseline.
 *
 * Concurrency (§11): collections are serialized by [collectionLock], so overlapping calls
 * cannot both read the same baseline and both account the same interval — the guarantee
 * does not rest on database uniqueness alone.
 */
@Singleton
class ScreenTimeUsageCollectionCoordinator @Inject constructor(
    private val accountRepository: AccountRepository,
    private val activeChildRepository: ScreenTimeActiveChildRepository,
    private val childProfileRepository: ChildProfileRepository,
    private val recorder: PersistentUsageSnapshotRecorder,
    private val zone: ZoneId,
    private val clock: () -> Long,
) {

    /** What one collection attempt did, with enough metadata to be diagnosable. */
    sealed interface Outcome {

        /** No signed-in account, so there is nothing to attribute usage to. */
        data object NoAccount : Outcome

        /** No child is selected as this device's screen-time target. */
        data object NoActiveChild : Outcome

        /**
         * The stored target does not resolve to a child of the current account — for
         * example the child was deleted, or the value belongs to another account.
         * Nothing is collected and no other child is substituted.
         */
        data class ActiveChildNotOwned(val childId: Long) : Outcome

        /** The device's usage statistics cannot be read; retryable, nothing written. */
        data object UsageAccessUnavailable : Outcome

        /** The window could not be attributed to one local day; nothing written. */
        data object WindowNotAttributable : Outcome

        /** The observation was accounted for. */
        data class Collected(
            val childId: Long,
            val dateKey: String,
            val packagesObserved: Int,
            /** Total usage added to the child's day by this collection. */
            val accountedMs: Long,
        ) : Outcome {
            /** True when this was the first observation of the window (a baseline). */
            val baselineOnly: Boolean get() = accountedMs == 0L
        }

        /** Something went wrong; nothing was accounted and the baseline is unchanged. */
        data class Failed(val reason: String) : Outcome
    }

    private val collectionLock = Mutex()

    /**
     * Collects once for the current account's active child over today's local day window.
     *
     * @param categoryOf the caller's package -> [AppCategory] mapping; the coordinator
     *   classifies nothing itself.
     */
    suspend fun collect(
        categoryOf: (String) -> AppCategory = AppCategories::categoryFor,
    ): Outcome = collectionLock.withLock {
        val accountId = accountRepository.currentAccountId.first() ?: return@withLock Outcome.NoAccount

        val childId = activeChildRepository.activeChildId(accountId) ?: return@withLock Outcome.NoActiveChild

        // Ownership: the stored id must resolve to a child of *this* account. Listing the
        // account's children is the existing, smallest check available and needs no new
        // persistence.
        val owns = childProfileRepository.observeChildren(accountId).first().any { it.id == childId }
        if (!owns) return@withLock Outcome.ActiveChildNotOwned(childId)

        val range = UsageDateKey.dayRange(clock(), zone)

        runCatching {
            recorder.ingest(
                accountId = accountId,
                childId = childId,
                range = range,
                categoryOf = categoryOf,
            )
        }.fold(
            onSuccess = { outcome -> outcome.toCollectionOutcome(childId) },
            onFailure = { error -> Outcome.Failed(error::class.simpleName ?: "error") },
        )
    }

    private fun SnapshotAccountingOutcome.toCollectionOutcome(childId: Long): Outcome = when (this) {
        SnapshotAccountingOutcome.UsageAccessUnavailable -> Outcome.UsageAccessUnavailable
        SnapshotAccountingOutcome.WindowNotAttributable -> Outcome.WindowNotAttributable
        is SnapshotAccountingOutcome.Accounted -> Outcome.Collected(
            childId = childId,
            dateKey = dateKey,
            packagesObserved = packagesObserved,
            accountedMs = deltas.sumOf { it.elapsedMs },
        )
    }
}
