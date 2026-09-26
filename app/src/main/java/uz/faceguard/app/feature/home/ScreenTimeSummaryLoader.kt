package uz.faceguard.app.feature.home

import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uz.faceguard.app.R
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageDateKey

/**
 * Phase 4 Step 2: assembles the screen-time summary the Home screen shows.
 *
 * Same shape as the existing `DashboardAggregator` — built by `HomeViewModel` and observed via
 * `observe(...)` — so the summary is one more section of the existing dashboard rather than a
 * parallel one.
 *
 * What it does *not* do is decide anything:
 *  - every number comes from `ScreenTimeLimitEvaluator`; this class never subtracts usage from
 *    a limit, never compares them and never invents a remaining time;
 *  - the account and the target child are explicit inputs. There is no fallback to a first
 *    child, no face recognition and no foreground-app inference, so a deleted or unselected
 *    target simply has no summary, and one account can never be shown another's data;
 *  - Usage Access is reported as its own state, so "cannot read usage" is never rendered as
 *    "used 0 min".
 *
 * It stays current by observing the sources that can change the answer — the selected day's
 * usage and the child's limit configuration — plus the target and the access state. No polling,
 * no timer, and the collector's 10-minute cadence is left exactly as designed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTimeSummaryLoader(
    private val usageRepository: ScreenTimeUsageRepository,
    private val limitRepository: ScreenTimeLimitRepository,
    private val evaluator: ScreenTimeLimitEvaluator,
    private val zone: ZoneId,
    private val clock: () -> Long,
) {

    /** The scopes the summary reports: the child's total day plus every known category. */
    private val categories: List<AppCategory> = AppCategory.entries.toList()

    fun observe(
        accountId: Flow<Long?>,
        target: Flow<ScreenTimeTargetUiState>,
        usageAccessGranted: Flow<Boolean>,
        scope: CoroutineScope,
    ): StateFlow<ScreenTimeSummaryUiState> =
        combine(accountId, target, usageAccessGranted) { id, targetState, granted ->
            Triple(id, targetState, granted)
        }
            .distinctUntilChanged()
            .flatMapLatest { (id, targetState, granted) -> summaryFor(id, targetState, granted) }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ScreenTimeSummaryUiState())

    private fun summaryFor(
        accountId: Long?,
        target: ScreenTimeTargetUiState,
        usageAccessGranted: Boolean,
    ): Flow<ScreenTimeSummaryUiState> {
        if (accountId == null || target.status == ScreenTimeTargetStatus.NO_ACCOUNT) {
            return flowOf(ScreenTimeSummaryUiState(status = ScreenTimeSummaryStatus.NO_ACCOUNT))
        }

        // The target must still resolve to a child of this account. A deleted or foreign id
        // has nothing to report and must never be substituted with a sibling.
        val child = target.activeChild
        val dateKey = todayDateKey()
        if (child == null) {
            return flowOf(
                ScreenTimeSummaryUiState(
                    status = ScreenTimeSummaryStatus.NO_TARGET,
                    dateKey = dateKey,
                ),
            )
        }

        if (!usageAccessGranted) {
            // Without Usage Access usage is unknown, not zero.
            return flowOf(
                ScreenTimeSummaryUiState(
                    status = ScreenTimeSummaryStatus.USAGE_UNAVAILABLE,
                    childId = child.id,
                    childName = child.childName,
                    dateKey = dateKey,
                ),
            )
        }

        // Recompute whenever the persisted usage or the configured limits change.
        val changes: Flow<Unit> = combine(
            usageRepository.observeDayUsage(accountId, child.id, dateKey),
            limitRepository.observeLimits(accountId, child.id),
        ) { _, _ -> Unit }

        return changes
            .map { load(accountId, child.id, child.childName, dateKey) }
            // A read failure is reported as an error; it is never shown as "no limit" or as
            // zero usage.
            .catch {
                emit(
                    ScreenTimeSummaryUiState(
                        status = ScreenTimeSummaryStatus.ERROR,
                        childId = child.id,
                        childName = child.childName,
                        dateKey = dateKey,
                        errorMessageRes = R.string.screentime_summary_error,
                    ),
                )
            }
    }

    private suspend fun load(
        accountId: Long,
        childId: Long,
        childName: String,
        dateKey: String,
    ): ScreenTimeSummaryUiState {
        val total = evaluator.evaluateTotal(accountId, childId, dateKey)
        val categoryRows = categories.map { evaluator.evaluateCategory(accountId, childId, dateKey, it) }

        return ScreenTimeSummaryUiState(
            status = ScreenTimeSummaryStatus.READY,
            childId = childId,
            childName = childName,
            dateKey = dateKey,
            total = total.toInfoRow(),
            categories = categoryRows.map { it.toInfoRow() },
        )
    }

    /** Today's local day through the existing date-key helper — no new date format. */
    private fun todayDateKey(): String = UsageDateKey.of(clock(), zone)

    companion object {
        /** Keeps the summary warm briefly across recomposition, like the dashboard does. */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
