package uz.faceguard.app.feature.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.notification.AppLabelResolver
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonConfirmDialog
import uz.faceguard.app.core.ui.qalqon.QalqonEmptyState
import uz.faceguard.app.core.ui.qalqon.QalqonErrorState
import uz.faceguard.app.core.ui.qalqon.QalqonListCard
import uz.faceguard.app.core.ui.qalqon.QalqonListRow
import uz.faceguard.app.core.ui.qalqon.QalqonLoadingState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageDateKey
import uz.faceguard.app.feature.child.childOverviews
import uz.faceguard.app.feature.home.durationLabel
import uz.faceguard.app.feature.home.eventLabelRes
import uz.faceguard.app.feature.requests.requestRows
import uz.faceguard.app.feature.requests.requestStatusLabelRes

/**
 * UI/UX redesign, Phase 5: the Activity centre state holder.
 *
 * A read-only aggregation over existing sources — the activity log, the request
 * store, the child list and the screen-time usage records — presented through the
 * pure [ActivityPresentation] mapper. It never queries a DAO/DataStore directly,
 * never recomputes screen time, and never fabricates a value: an unreadable metric is
 * reported as unavailable.
 *
 * The screen-time history is the only child-scoped section, so the child filter
 * applies there; the activity log itself carries no child id, so its events are shown
 * account-wide rather than under a filter that would not apply to them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityLogViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val activityRepository: ActivityLogRepository,
    private val requestRepository: ParentRequestRepository,
    private val childRepository: ChildProfileRepository,
    private val usageRepository: ScreenTimeUsageRepository,
    private val appUsageSource: AppUsageSource,
    private val appLabelResolver: AppLabelResolver,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val selectedChildId = MutableStateFlow<Long?>(null)
    private val retryTrigger = MutableStateFlow(0L)

    val ui: StateFlow<ActivityUiState> = retryTrigger
        .flatMapLatest {
            accountRepository.currentAccountId
                .flatMapLatest { accountId ->
                    if (accountId == null) {
                        flowOf(ActivityUiState(status = ActivityStatus.NO_ACCOUNT))
                    } else {
                        centre(accountId)
                    }
                }
                // Handled inside the trigger scope so a failure ends only this attempt:
                // the outer chain stays alive and retry() re-subscribes.
                .catch { emit(ActivityUiState(status = ActivityStatus.ERROR)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun retry() {
        retryTrigger.value += 1
    }

    /** The child the screen-time history is filtered to; `null` = all children. */
    fun selectChild(childId: Long?) {
        selectedChildId.value = childId
    }

    /** Clears the account's activity log (existing capability, kept). */
    fun clear() {
        viewModelScope.launch {
            val accountId = accountRepository.currentAccountId.first() ?: return@launch
            activityRepository.clear(accountId)
        }
    }

    private fun centre(accountId: Long): Flow<ActivityUiState> {
        val todayKey = UsageDateKey.of(clock(), zone)
        val yesterdayKey = LocalDate.parse(todayKey).minusDays(1).toString()
        val usageAvailable = appUsageSource.usageAccess() == UsageAccessState.AVAILABLE

        val children = childRepository.observeChildren(accountId).catch { emit(emptyList()) }
        val events = activityRepository.recent(accountId).catch { emit(emptyList()) }
        val pending = requestRepository.observePending(accountId).catch { emit(emptyList()) }

        val todayUsage = children.flatMapLatest { kids -> todayUsageFlow(accountId, kids, todayKey) }
        val pastUsage = children.flatMapLatest { kids -> pastUsageFlow(accountId, kids, todayKey) }

        val lists = combine(children, events, pending) { kids, eventList, requestList ->
            Triple(kids, eventList, requestList)
        }
        val usage = combine(todayUsage, pastUsage, children) { today, past, kids ->
            Triple(today, past, kids)
        }

        return combine(lists, usage, selectedChildId) { (kids, eventList, requestList), (today, past, _), selected ->
            buildState(
                children = kids,
                events = eventList,
                pendingCount = requestList.size,
                requests = requestList,
                todayUsage = today,
                pastUsage = past,
                usageAvailable = usageAvailable,
                selectedChildId = selected,
                todayKey = todayKey,
                yesterdayKey = yesterdayKey,
            )
        }
    }

    /** Today's per-child totals, reactively, so today's numbers update as usage lands. */
    private fun todayUsageFlow(
        accountId: Long,
        children: List<ChildProfile>,
        todayKey: String,
    ): Flow<Map<Long, Long>> {
        if (children.isEmpty()) return flowOf(emptyMap())
        val flows = children.map { child ->
            usageRepository.observeDayUsage(accountId, child.id, todayKey)
                .map { rows -> child.id to rows.sumOf { it.usedMs } }
                .catch { emit(child.id to 0L) }
        }
        return combine(flows) { pairs -> pairs.toMap() }
    }

    /** The previous days' per-child totals: closed days, so they are read once per subscription. */
    private fun pastUsageFlow(
        accountId: Long,
        children: List<ChildProfile>,
        todayKey: String,
    ): Flow<Map<Long, List<ActivityDayUsage>>> {
        if (children.isEmpty()) return flowOf(emptyMap())
        val keys = (1 until HISTORY_DAYS).map { offset ->
            LocalDate.parse(todayKey).minusDays(offset.toLong()).toString()
        }
        return flow {
            val map = children.associate { child ->
                child.id to keys.map { key ->
                    ActivityDayUsage(key, usageRepository.getTotalUsageMs(accountId, child.id, key))
                }
            }
            emit(map)
        }.catch { emit(emptyMap()) }
    }

    private fun buildState(
        children: List<ChildProfile>,
        events: List<ActivityEvent>,
        pendingCount: Int,
        requests: List<ParentRequest>,
        todayUsage: Map<Long, Long>,
        pastUsage: Map<Long, List<ActivityDayUsage>>,
        usageAvailable: Boolean,
        selectedChildId: Long?,
        todayKey: String,
        yesterdayKey: String,
    ): ActivityUiState {
        val overviews = childOverviews(children)
        val usages = overviews.map { overview ->
            ActivityChildUsage(
                childId = overview.childId,
                name = overview.name,
                initial = overview.initial,
                todayMs = if (usageAvailable) todayUsage[overview.childId] ?: 0L else null,
                pastDays = if (usageAvailable) pastUsage[overview.childId].orEmpty() else emptyList(),
            )
        }
        val eventsToday = eventsTodayCount(events, todayKey) { at -> UsageDateKey.of(at, zone) }
        val screenTimeToday = if (usageAvailable) usages.sumOf { it.todayMs ?: 0L } else null

        return ActivityUiState(
            status = ActivityStatus.READY,
            children = overviews,
            events = events.map { event ->
                ActivityEventItem(
                    type = event.type,
                    labelRes = eventLabelRes(event.type),
                    detail = event.detail,
                    at = event.at,
                )
            },
            requests = requestRows(requests, children.associate { it.id to it.childName }, appLabelResolver::labelFor),
            usages = usages,
            metrics = activityMetrics(screenTimeToday, pendingCount, eventsToday),
            usageAvailable = usageAvailable,
            selectedChildId = selectedChildId,
            todayKey = todayKey,
            yesterdayKey = yesterdayKey,
        )
    }

    private companion object {
        /**
         * Days of screen-time history the centre shows (today + the six previous days).
         * Bounded so the screen stays compact and the per-day reads stay cheap.
         */
        const val HISTORY_DAYS = 7
    }
}

/**
 * UI/UX redesign, Phase 5: the Activity top-level destination.
 *
 * A compact "what happened?" centre: today's overview, the actionable pending
 * requests, the recent protection events, and the child-scoped screen-time history.
 * It is a top-level bottom-navigation tab, so it has no back arrow; the existing
 * "clear log" capability moved into the top-bar overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    onOpenRequests: () -> Unit,
    viewModel: ActivityLogViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.home_more_options),
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.activity_clear)) },
                            onClick = {
                                menuExpanded = false
                                confirmingClear = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (ui.status) {
            ActivityStatus.LOADING -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonLoadingState()
            }

            ActivityStatus.NO_ACCOUNT -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonEmptyState(
                    title = stringResource(R.string.dashboard_no_account),
                    description = stringResource(R.string.dashboard_no_account_hint),
                )
            }

            ActivityStatus.ERROR -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonErrorState(
                    title = stringResource(R.string.dashboard_error),
                    message = stringResource(R.string.dashboard_error_hint),
                    retryLabel = stringResource(R.string.dashboard_retry),
                    onRetry = viewModel::retry,
                )
            }

            ActivityStatus.READY -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = QalqonDimens.screenPadding,
                    end = QalqonDimens.screenPadding,
                    top = QalqonDimens.spacing.lg,
                    bottom = QalqonDimens.spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.lg),
            ) {
                item { OverviewSection(ui.metrics) }
                item { RequestsSection(ui, onOpenRequests) }
                item { EventsSection(ui) }
                item { ScreenTimeSection(ui, viewModel::selectChild) }
            }
        }
    }

    if (confirmingClear) {
        QalqonConfirmDialog(
            title = stringResource(R.string.activity_clear),
            message = stringResource(R.string.activity_clear_confirm),
            onDismiss = { confirmingClear = false },
            onConfirm = {
                confirmingClear = false
                viewModel.clear()
            },
            destructive = true,
        )
    }
}

/** The compact today overview: two metrics per row, the remainder filling the last row. */
@Composable
private fun OverviewSection(metrics: List<ActivityMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(title = stringResource(R.string.activity_overview_title))
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
            ) {
                rowMetrics.forEach { metric ->
                    ActivityMetricTile(metric = metric, modifier = Modifier.weight(1f))
                }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityMetricTile(metric: ActivityMetric, modifier: Modifier = Modifier) {
    QalqonCard(modifier = modifier) {
        Text(
            text = stringResource(metric.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (val value = metric.value) {
            is ActivityMetricValue.DurationMs -> Text(
                text = durationLabel(value.ms),
                style = MaterialTheme.typography.titleMedium,
            )
            is ActivityMetricValue.Count -> Text(
                text = value.count.toString(),
                style = MaterialTheme.typography.titleMedium,
            )
            ActivityMetricValue.Unavailable -> Text(
                text = stringResource(R.string.activity_usage_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The actionable requests: child, app and status, each opening the requests screen. */
@Composable
private fun RequestsSection(ui: ActivityUiState, onOpenRequests: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(title = stringResource(R.string.requests_title))
        if (!ui.hasRequests) {
            Text(
                text = stringResource(R.string.dashboard_requests_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        QalqonListCard {
            ui.requests.forEach { row ->
                val status = stringResource(requestStatusLabelRes(row.request.status))
                val subtitle = listOfNotNull(row.childName, status).joinToString(" · ")
                QalqonListRow(
                    title = row.appLabel,
                    subtitle = subtitle,
                    onClick = onOpenRequests,
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

/** The recent protection events: type, time and the event's own detail. */
@Composable
private fun EventsSection(ui: ActivityUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(title = stringResource(R.string.dashboard_activity_title))
        if (!ui.hasEvents) {
            Text(
                text = stringResource(R.string.dashboard_activity_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        QalqonListCard {
            ui.events.forEach { event ->
                val time = formatEventTime(event.at)
                val subtitle = listOfNotNull(event.detail, time).joinToString(" · ")
                QalqonListRow(
                    title = stringResource(event.labelRes),
                    subtitle = subtitle,
                )
            }
        }
    }
}

/** The child-scoped screen-time history, with the child filter that narrows it. */
@Composable
private fun ScreenTimeSection(ui: ActivityUiState, onSelectChild: (Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(title = stringResource(R.string.activity_screentime_title))

        when {
            !ui.hasChildren -> Text(
                text = stringResource(R.string.dashboard_child_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            !ui.usageAvailable -> Text(
                text = stringResource(R.string.activity_usage_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                // The filter only appears when it can change the result, and only
                // narrows the child-scoped history below it.
                if (ui.hasMultipleChildren) {
                    ChildFilterRow(ui, onSelectChild)
                }
                val days = ui.usageDays
                if (days.isEmpty()) {
                    Text(
                        text = stringResource(R.string.activity_no_screentime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    days.forEach { day -> UsageDayGroup(day) }
                }
            }
        }
    }
}

@Composable
private fun ChildFilterRow(ui: ActivityUiState, onSelectChild: (Long?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
    ) {
        FilterChip(
            selected = ui.selectedChildId == null,
            onClick = { onSelectChild(null) },
            label = { Text(stringResource(R.string.activity_filter_all)) },
        )
        ui.children.forEach { child ->
            FilterChip(
                selected = ui.selectedChildId == child.childId,
                onClick = { onSelectChild(child.childId) },
                label = {
                    Text(
                        text = child.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun UsageDayGroup(day: ActivityUsageDay) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs)) {
        Text(
            text = activityDayLabelRes(day.kind)?.let { stringResource(it) } ?: day.dateKey,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QalqonListCard {
            day.rows.forEach { row ->
                val duration = durationLabel(row.usedMs)
                QalqonListRow(
                    title = row.name,
                    subtitle = duration,
                )
            }
        }
    }
}

internal fun formatEventTime(at: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
