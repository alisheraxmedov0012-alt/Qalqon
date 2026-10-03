package uz.faceguard.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import uz.faceguard.app.R
import uz.faceguard.app.core.debug.DebugFlags
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonShapes
import uz.faceguard.app.core.ui.qalqon.QalqonAlertRow
import uz.faceguard.app.core.ui.qalqon.QalqonAlertSeverity
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonChildCard
import uz.faceguard.app.core.ui.qalqon.QalqonDivider
import uz.faceguard.app.core.ui.qalqon.QalqonEmptyState
import uz.faceguard.app.core.ui.qalqon.QalqonErrorState
import uz.faceguard.app.core.ui.qalqon.QalqonLoadingState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.core.ui.qalqon.QalqonStatusBadge
import uz.faceguard.app.core.ui.qalqon.severityColor
import uz.faceguard.app.core.ui.qalqon.toneColor
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.screentime.ScreenTimeActiveChildRepository
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository

/**
 * Home dashboard state holder.
 *
 * A read-only aggregation over existing application data (profiles, per-child
 * policies, protected apps, activity events, protection runtime). No metric is
 * invented: a value that cannot be read is reported as unavailable rather than
 * shown as a fabricated figure.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val parentProfileRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    policyRepository: ChildAppPolicyRepository,
    protectedAppsRepository: ProtectedAppsRepository,
    activityLogRepository: ActivityLogRepository,
    settingsRepository: SettingsRepository,
    requestRepository: ParentRequestRepository,
    eyeSafetyRepository: EyeSafetyRepository,
    private val screenTimeActiveChildRepository: ScreenTimeActiveChildRepository,
    screenTimeUsageRepository: ScreenTimeUsageRepository,
    screenTimeLimitRepository: ScreenTimeLimitRepository,
    screenTimeLimitEvaluator: ScreenTimeLimitEvaluator,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    runtime: ProtectionRuntime,
) : ViewModel() {

    private val aggregator = DashboardAggregator(
        accountRepository = accountRepository,
        childRepository = childRepository,
        policyRepository = policyRepository,
        protectedAppsRepository = protectedAppsRepository,
        activityLogRepository = activityLogRepository,
        settingsRepository = settingsRepository,
        requestRepository = requestRepository,
        eyeSafetyRepository = eyeSafetyRepository,
        runtimeState = runtime.state,
    )

    val dashboard: StateFlow<DashboardUiState> = aggregator.observe(viewModelScope)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _account = MutableStateFlow<UserAccount?>(null)
    val account: StateFlow<UserAccount?> = _account

    val parentProfile: StateFlow<ParentProfile?> = accountRepository.currentAccountId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else parentProfileRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            accountRepository.currentAccountId.collect { id ->
                _account.value = if (id == null) null else accountRepository.getCurrentAccount()
            }
        }
    }

    fun selectChild(childId: Long?) = aggregator.selectChild(childId)

    fun retry() = aggregator.retry()

    // ------------------------------------------------- Phase 4 screen-time target

    private val _screenTimeTarget = MutableStateFlow(ScreenTimeTargetUiState())

    /**
     * Phase 4 Step 1B-8: the device's screen-time target, read reactively from the persisted
     * (account-scoped) value. Re-created per account, so a previous account's choice can
     * never appear as the new account's.
     */
    val screenTimeTarget: StateFlow<ScreenTimeTargetUiState> = accountRepository.currentAccountId
        .flatMapLatest { accountId ->
            if (accountId == null) {
                flowOf(ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.NO_ACCOUNT))
            } else {
                combine(
                    childRepository.observeChildren(accountId),
                    screenTimeActiveChildRepository.observeActiveChildId(accountId),
                ) { children, activeChildId ->
                    ScreenTimeTargetUiState(
                        status = ScreenTimeTargetStatus.READY,
                        children = children,
                        activeChildId = activeChildId,
                    )
                }.catch {
                    // A read failure must not invent a selection: report it and offer retry.
                    emit(ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.ERROR))
                }
            }
        }
        // Local UI progress (saving / a failed write) is layered on the persisted value.
        .combine(_screenTimeTarget) { persisted, local ->
            persisted.copy(saving = local.saving, errorMessageRes = local.errorMessageRes)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenTimeTargetUiState())

    // ------------------------------------------------- Phase 4 screen-time summary

    private val screenTimeSummaryLoader = ScreenTimeSummaryLoader(
        usageRepository = screenTimeUsageRepository,
        limitRepository = screenTimeLimitRepository,
        evaluator = screenTimeLimitEvaluator,
        zone = zone,
        clock = clock,
    )

    /**
     * Today's usage and limit state for the selected screen-time child, assembled from the
     * evaluator so the screen never computes a remaining time or an exceeded flag itself.
     */
    val screenTimeSummary: StateFlow<ScreenTimeSummaryUiState> = screenTimeSummaryLoader.observe(
        accountId = accountRepository.currentAccountId,
        target = screenTimeTarget,
        usageAccessGranted = runtime.state.map { it.usageAccessGranted },
        scope = viewModelScope,
    )

    /**
     * Persists [childId] as this device's screen-time target.
     *
     * The child must belong to the signed-in account: the id is validated against the
     * account's own children before it is written, so a stale or forged id can never be
     * stored. A no-op re-selection writes nothing.
     */
    fun selectScreenTimeChild(childId: Long) {
        viewModelScope.launch {
            val accountId = accountRepository.currentAccountId.first() ?: return@launch
            val owned = childRepository.observeChildren(accountId).first().any { it.id == childId }
            if (!owned) {
                _screenTimeTarget.update { it.copy(errorMessageRes = R.string.screentime_target_error_save) }
                return@launch
            }
            if (screenTimeActiveChildRepository.activeChildId(accountId) == childId) return@launch

            _screenTimeTarget.update { it.copy(saving = true, errorMessageRes = null) }
            runCatching { screenTimeActiveChildRepository.setActiveChildId(accountId, childId) }
                .onFailure { _screenTimeTarget.update { state -> state.copy(errorMessageRes = R.string.screentime_target_error_save) } }
            _screenTimeTarget.update { it.copy(saving = false) }
        }
    }
}

/**
 * UI/UX redesign, Phase 3: the QALQON Home dashboard.
 *
 * A single scrolling dashboard that answers the parent's questions in priority
 * order — is protection active, does anything need attention, what is happening with
 * the children, today's key states, and what can I do now. It replaces the previous
 * configuration dump (child picker, per-child policy/EYE-safety cards, a usage table,
 * a 7-step checklist, a developer section) with an overview; the configuration
 * itself continues to live on the screens that own it.
 *
 * It only presents state the existing [HomeViewModel]/[DashboardAggregator] already
 * produced — no Room/DataStore access, no policy/screen-time/schedule/permission
 * calculation, and no fabricated value.
 */
@Composable
fun HomeScreen(
    onOpenChildren: () -> Unit,
    onOpenProtectedApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenChildPolicy: (Long) -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenRecognition: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val screenTimeSummary by viewModel.screenTimeSummary.collectAsStateWithLifecycle()
    val parentProfile by viewModel.parentProfile.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            HomeTopBar(
                parentName = parentProfile?.displayName,
                onOpenPrivacy = onOpenPrivacy,
                onOpenHelp = onOpenHelp,
                onOpenRecognition = onOpenRecognition,
            )
        },
    ) { padding ->
        when (dashboard.status) {
            DashboardStatus.LOADING -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonLoadingState()
            }

            DashboardStatus.ERROR -> Box(
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

            DashboardStatus.NO_ACCOUNT -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonEmptyState(
                    title = stringResource(R.string.dashboard_no_account),
                    description = stringResource(R.string.dashboard_no_account_hint),
                )
            }

            DashboardStatus.READY -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = QalqonDimens.screenPadding,
                    end = QalqonDimens.screenPadding,
                    top = QalqonDimens.spacing.lg,
                    bottom = QalqonDimens.spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.lg),
            ) {
                item { ProtectionStatusSection(dashboard, onOpenProtection) }

                // The attention section is hidden entirely when the dashboard is calm.
                val attention = homeAttentionItems(dashboard)
                if (attention.isNotEmpty()) {
                    item {
                        AttentionSection(
                            items = attention,
                            onOpenRequests = onOpenRequests,
                            onOpenChildren = onOpenChildren,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }

                // Recomposed dashboard order (V2): hero -> notice -> today -> children
                // -> quick actions, so the useful dashboard information sits above the
                // fold and the child area no longer dominates the screen.
                item { TodaySection(dashboard, screenTimeSummary) }
                item { ChildrenSection(dashboard, screenTimeSummary, onOpenChildPolicy, onOpenChildren) }
                item {
                    QuickActionsSection(
                        state = dashboard,
                        onOpenChildren = onOpenChildren,
                        onOpenProtection = onOpenProtection,
                        onOpenProtectedApps = onOpenProtectedApps,
                        onOpenRequests = onOpenRequests,
                    )
                }
            }
        }
    }
}

/**
 * The Home top app bar: a localized greeting (the parent's own name when known, else
 * the plain title) plus a one-line subtitle. The overflow holds the secondary
 * destinations that no bottom-navigation tab owns (Privacy, Help), so they stay
 * reachable; the developer diagnostics entry is present only when [DebugFlags]
 * enables the debug screens, so it never appears in a production UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    parentName: String?,
    onOpenPrivacy: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenRecognition: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Never present a phone number (or a blank value) as the parent's name.
    val name = homeGreetingName(parentName)
    val greeting = name?.let { stringResource(R.string.home_greeting, it) }
    val initial = name?.firstOrNull()?.uppercase()

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Compact identity block: the parent's own initial, from the real
                // profile name — no invented avatar artwork.
                if (initial != null) {
                    Box(
                        modifier = Modifier
                            .size(QalqonDimens.sizes.avatar)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = initial,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.size(QalqonDimens.spacing.md))
                }
                Column {
                    Text(
                        text = greeting ?: stringResource(R.string.home_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.home_more_options),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_privacy)) },
                    onClick = {
                        menuExpanded = false
                        onOpenPrivacy()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_help)) },
                    onClick = {
                        menuExpanded = false
                        onOpenHelp()
                    },
                )
                if (DebugFlags.DEBUG_SCREENS_ENABLED) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_recognition_debug)) },
                        onClick = {
                            menuExpanded = false
                            onOpenRecognition()
                        },
                    )
                }
            }
        },
    )
}

/**
 * The signature QALQON hero (V3): protection state on a large, premium soft-blue surface
 * with a large rounded status glyph (semantic tone), a dominant title, a supporting line,
 * the real protected-app count as a metadata chip while protection runs, and a visually
 * dominant full-width CTA.
 *
 * Presentation only: every value comes from [DashboardUiState] via the existing
 * presentation helpers — no protection logic, no fabricated state.
 */
@Composable
private fun ProtectionStatusSection(state: DashboardUiState, onOpenProtection: () -> Unit) {
    val status = homeProtectionStatus(state)
    val tone = homeProtectionTone(status)
    val accent = toneColor(tone)
    val actionLabel = homeProtectionActionLabelRes(status)
    val runCount = (status == HomeProtectionStatus.ACTIVE || status == HomeProtectionStatus.BLOCKING) &&
        state.protectedAppsCount > 0

    // Signature hero: a large, borderless soft-blue surface (distinct from the white
    // bordered cards elsewhere) with the QALQON wordmark, a big rounded status glyph,
    // a dominant title and an action-first CTA. STATE -> EXPLANATION -> ACTION.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = QalqonShapes.largeShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = QalqonDimens.elevation.flat,
    ) {
        Column(
            modifier = Modifier.padding(QalqonDimens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(QalqonDimens.sizes.buttonLarge)
                        .background(color = accent.copy(alpha = 0.20f), shape = QalqonShapes.largeShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(QalqonDimens.icon.md),
                    )
                }
                Spacer(Modifier.size(QalqonDimens.spacing.md))
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
            Text(
                text = stringResource(homeProtectionLabelRes(status)),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(homeProtectionSupportingRes(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            state.blockedApp?.let { packageName ->
                Text(
                    text = stringResource(R.string.dashboard_active_app, packageName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            // Real metadata chip: the actual protected-app count, only while protection runs.
            if (runCount) {
                QalqonStatusBadge(
                    label = stringResource(R.string.home_protection_apps_count, state.protectedAppsCount),
                    tone = tone,
                )
            }
            Button(
                onClick = onOpenProtection,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QalqonDimens.spacing.xs),
            ) {
                Text(stringResource(actionLabel))
            }
        }
    }
}

/** Compact attention list; only rendered when [items] is not empty. */
@Composable
private fun AttentionSection(
    items: List<HomeAttentionItem>,
    onOpenRequests: () -> Unit,
    onOpenChildren: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(
            title = stringResource(R.string.home_attention_title),
            leading = { HomeSectionIcon(Icons.Filled.Warning, severityColor(QalqonAlertSeverity.WARNING)) },
        )
        items.forEach { item ->
            val text = item.count
                ?.let { count -> stringResource(item.messageRes, count) }
                ?: stringResource(item.messageRes)
            QalqonAlertRow(
                text = text,
                severity = item.severity,
                onClick = when (item.kind) {
                    HomeAttentionKind.PENDING_REQUESTS -> onOpenRequests
                    HomeAttentionKind.CHILDREN_NEED_SETUP -> onOpenChildren
                    HomeAttentionKind.NOTIFICATIONS_DISABLED -> onOpenSettings
                },
            )
        }
    }
}

/**
 * The children overview: one tappable card per child, or the existing empty state
 * when there is no child yet. It is an overview, not the future Child Detail hub —
 * tapping a child opens the existing per-child destination.
 */
@Composable
private fun ChildrenSection(
    state: DashboardUiState,
    screenTime: ScreenTimeSummaryUiState,
    onOpenChildPolicy: (Long) -> Unit,
    onOpenChildren: () -> Unit,
) {
    val children = homeChildSummaries(state)
    // "All" only when there is something to see in the Children hub.
    val allAction: (@Composable () -> Unit)? = if (children.isNotEmpty()) {
        {
            TextButton(onClick = onOpenChildren) {
                Text(stringResource(R.string.home_all_children))
            }
        }
    } else {
        null
    }

    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(
            title = stringResource(R.string.children_section),
            leading = { HomeSectionIcon(Icons.Filled.Person, MaterialTheme.colorScheme.primary) },
            action = allAction,
        )

        if (children.isEmpty()) {
            // Compact, intentional empty state: a bounded, borderless surface (row + CTA)
            // instead of a tall centred block, so it no longer consumes a large vertical
            // area and stops repeating the bordered-card geometry.
            QalqonCard(modifier = Modifier.fillMaxWidth(), bordered = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(QalqonDimens.sizes.avatar)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(QalqonDimens.icon.sm),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = QalqonDimens.spacing.md),
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_child_none),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_child_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onOpenChildren,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dashboard_child_add))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
                children.forEach { child ->
                    val faceStatus = stringResource(
                        if (child.faceEnrolled) R.string.dashboard_child_face_on
                        else R.string.dashboard_child_face_off,
                    )
                    // The card line is shown only for the child the existing screen-time
                    // summary actually belongs to — real data, never a stand-in figure.
                    val todayScreenTime: String? = if (
                        screenTime.status == ScreenTimeSummaryStatus.READY &&
                        screenTime.childId == child.childId &&
                        screenTime.total != null
                    ) {
                        stringResource(
                            R.string.home_child_screen_time,
                            durationLabel(screenTime.total!!.usedMs),
                        )
                    } else {
                        null
                    }
                    QalqonChildCard(
                        name = child.name,
                        initial = child.initial,
                        protectionConfigured = child.faceEnrolled,
                        faceEnrolled = child.faceEnrolled,
                        faceStatus = faceStatus,
                        screenTime = todayScreenTime,
                        onClick = { onOpenChildPolicy(child.childId) },
                        contentDescription = stringResource(
                            R.string.home_child_content_description,
                            child.name,
                            faceStatus,
                        ),
                    )
                }
            }
        }
    }
}

/** The compact today-overview metrics, laid out two per row. */
@Composable
private fun TodaySection(state: DashboardUiState, screenTime: ScreenTimeSummaryUiState) {
    val metrics = homeTodayMetrics(state, screenTime)
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(
            title = stringResource(R.string.home_today_title),
            supportingText = stringResource(R.string.home_today_subtitle),
            leading = { HomeSectionIcon(Icons.Filled.DateRange, MaterialTheme.colorScheme.primary) },
        )
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Equal-height tiles: the row takes the intrinsic height of its
                    // tallest tile and every tile fills it, so paired cards line up.
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
            ) {
                rowMetrics.forEach { metric ->
                    HomeMetricTile(
                        metric = metric,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The glyph for each metric; decorative, so the localized label carries the meaning. */
private fun homeMetricIcon(kind: HomeTodayMetricKind): ImageVector = when (kind) {
    HomeTodayMetricKind.SCREEN_TIME -> Icons.Filled.DateRange
    HomeTodayMetricKind.SCHEDULE -> Icons.Filled.Info
    HomeTodayMetricKind.EYE_SAFETY -> Icons.Filled.Warning
    HomeTodayMetricKind.PROTECTION -> Icons.Filled.Lock
}

/**
 * A small rounded-square icon container used as a dashboard section marker, so the
 * Home sections read as one designed product instead of plain text headings.
 */
@Composable
private fun HomeSectionIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(QalqonDimens.icon.lg)
            .background(color = tint.copy(alpha = 0.14f), shape = QalqonShapes.smallShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(QalqonDimens.icon.xs),
        )
    }
}

@Composable
private fun HomeMetricTile(metric: HomeTodayMetric, modifier: Modifier = Modifier) {
    val accent = metric.tone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    // A duration is short and numeric (prominent); a status/text value can be a full
    // sentence, so it renders smaller and WRAPS — never an ellipsis on important text.
    val (valueText, valueStyle) = when (val value = metric.value) {
        is HomeMetricValue.Duration ->
            durationLabel(value.ms) to MaterialTheme.typography.titleLarge
        is HomeMetricValue.Text ->
            (if (value.arg != null) stringResource(value.res, value.arg) else stringResource(value.res)) to
                MaterialTheme.typography.bodyLarge
    }
    // Borderless cool-tonal dashboard tile: a distinct surface layer from the white
    // bordered cards, so the page is not one repeated rounded rectangle.
    QalqonCard(
        modifier = modifier,
        bordered = false,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier
                .size(QalqonDimens.icon.lg)
                .background(color = accent.copy(alpha = 0.14f), shape = QalqonShapes.smallShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = homeMetricIcon(metric.kind),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(QalqonDimens.icon.sm),
            )
        }
        Text(
            text = stringResource(metric.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = valueText,
            style = valueStyle,
            color = accent,
            // The full sentence is allowed to wrap; nothing here truncates it.
        )
        metric.caption?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * At most three contextual quick actions, each routed through the existing
 * navigation callbacks (so the lock gate and protected-route rules are unchanged).
 */
@Composable
private fun QuickActionsSection(
    state: DashboardUiState,
    onOpenChildren: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenProtectedApps: () -> Unit,
    onOpenRequests: () -> Unit,
) {
    val actions = homeQuickActions(state)
    if (actions.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(
            title = stringResource(R.string.home_quick_actions_title),
            supportingText = stringResource(R.string.home_quick_actions_subtitle),
            leading = { HomeSectionIcon(Icons.AutoMirrored.Filled.List, MaterialTheme.colorScheme.primary) },
        )
        // One grouped, borderless command-center surface with divided rows (a distinct
        // layer from the tonal Today tiles and the soft-blue hero).
        QalqonCard(
            modifier = Modifier.fillMaxWidth(),
            bordered = false,
            contentPadding = QalqonDimens.spacing.none,
        ) {
            actions.forEachIndexed { index, action ->
                val onClick = when (action) {
                    HomeQuickAction.MANAGE_CHILDREN -> onOpenChildren
                    HomeQuickAction.PROTECTION_SETTINGS -> onOpenProtection
                    HomeQuickAction.PROTECTED_APPS -> onOpenProtectedApps
                    HomeQuickAction.REVIEW_REQUESTS -> onOpenRequests
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onClick)
                        .padding(
                            horizontal = QalqonDimens.cardPadding,
                            vertical = QalqonDimens.rowPadding,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(QalqonDimens.sizes.avatar)
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = homeQuickActionIcon(action),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(QalqonDimens.icon.sm),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = QalqonDimens.spacing.md),
                    ) {
                        Text(
                            text = stringResource(homeQuickActionLabelRes(action)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(homeQuickActionDescriptionRes(action)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index != actions.lastIndex) {
                    QalqonDivider(modifier = Modifier.padding(start = QalqonDimens.spacing.xl))
                }
            }
        }
    }
}

/** The glyph for each quick action; decorative, the label carries the meaning. */
private fun homeQuickActionIcon(action: HomeQuickAction): ImageVector = when (action) {
    HomeQuickAction.MANAGE_CHILDREN -> Icons.Filled.Person
    HomeQuickAction.PROTECTION_SETTINGS -> Icons.Filled.Lock
    HomeQuickAction.PROTECTED_APPS -> Icons.AutoMirrored.Filled.List
    HomeQuickAction.REVIEW_REQUESTS -> Icons.Filled.Notifications
}
