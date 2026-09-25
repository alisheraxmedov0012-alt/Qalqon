package uz.faceguard.app.feature.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.debug.DebugFlags
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.ui.SectionCard
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
import uz.faceguard.app.domain.screentime.ScreenTimeActiveChildRepository
import uz.faceguard.app.domain.request.ParentRequestRepository

/**
 * Phase 10 Parent Dashboard.
 *
 * A read-only aggregation over existing application data (profiles, per-child
 * policies, protected apps, activity events, protection runtime). No metric is
 * invented: screen-time usage does not exist yet, so the dashboard says so
 * rather than showing a fabricated figure.
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
    private val screenTimeActiveChildRepository: ScreenTimeActiveChildRepository,
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

@Composable
fun HomeScreen(
    onOpenParent: () -> Unit,
    onOpenChildren: () -> Unit,
    onOpenProtectedApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecognition: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenChildPolicy: (Long) -> Unit,
    onOpenRequests: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val parentProfile by viewModel.parentProfile.collectAsStateWithLifecycle()
    val screenTimeTarget by viewModel.screenTimeTarget.collectAsStateWithLifecycle()

    var showMore by remember { mutableStateOf(false) }
    var showDeveloperTools by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (dashboard.status) {
                DashboardStatus.LOADING -> Text(
                    stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )

                DashboardStatus.ERROR -> ErrorCard(onRetry = viewModel::retry)

                DashboardStatus.NO_ACCOUNT -> SectionCard(
                    title = stringResource(R.string.dashboard_no_account),
                    subtitle = stringResource(R.string.dashboard_no_account_hint),
                ) {}

                DashboardStatus.READY -> {
                    ChildContextCard(
                        dashboard = dashboard,
                        onSelectChild = viewModel::selectChild,
                        onOpenChildren = onOpenChildren,
                    )
                    ProtectionStatusCard(dashboard = dashboard, onOpenProtection = onOpenProtection)
                    ActiveProtectionCard(dashboard)
                    ChildPolicySummaryCard(
                        dashboard = dashboard,
                        onOpenChildPolicy = onOpenChildPolicy,
                        onOpenChildren = onOpenChildren,
                    )
                    ProtectedAppsSummaryCard(dashboard = dashboard, onOpenProtectedApps = onOpenProtectedApps)
                    RequestsSummaryCard(dashboard = dashboard, onOpenRequests = onOpenRequests)
                    ChildProfileSummaryCard(dashboard, onOpenChildren = onOpenChildren)
                    RecentActivityCard(dashboard, onOpenActivity = onOpenActivity)
                    UsageCard(
                        target = screenTimeTarget,
                        onSelectChild = viewModel::selectScreenTimeChild,
                        onOpenChildren = onOpenChildren,
                    )
                }
            }

            SetupChecklistCard(
                account = account,
                parentProfile = parentProfile,
                dashboard = dashboard,
                settings = settings,
            )

            MainActionsCard(
                onOpenProtection = onOpenProtection,
                onOpenParent = onOpenParent,
                onOpenChildren = onOpenChildren,
                onOpenProtectedApps = onOpenProtectedApps,
                onOpenSettings = onOpenSettings,
                protectedCount = dashboard.protectedAppsCount,
            )

            AdditionalSection(
                expanded = showMore,
                onToggle = { showMore = !showMore },
                onOpenActivity = onOpenActivity,
                onOpenPrivacy = onOpenPrivacy,
                onOpenHelp = onOpenHelp,
            )

            if (DebugFlags.DEBUG_SCREENS_ENABLED) {
                DeveloperSection(
                    expanded = showDeveloperTools,
                    onToggle = { showDeveloperTools = !showDeveloperTools },
                    onOpenRecognition = onOpenRecognition,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.dashboard_error),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.dashboard_error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_retry))
            }
        }
    }
}

/** A. Which child's data is on screen, and how to switch. */
@Composable
private fun ChildContextCard(
    dashboard: DashboardUiState,
    onSelectChild: (Long?) -> Unit,
    onOpenChildren: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.dashboard_child_title),
        subtitle = if (dashboard.hasChild) {
            stringResource(R.string.dashboard_child_selected, dashboard.child?.name.orEmpty())
        } else {
            stringResource(R.string.dashboard_child_none)
        },
    ) {
        if (!dashboard.hasChild) {
            Text(
                stringResource(R.string.dashboard_child_none_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenChildren, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_child_add))
            }
            return@SectionCard
        }

        if (dashboard.hasMultipleChildren) {
            Text(
                stringResource(R.string.dashboard_child_switch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dashboard.children.forEach { child ->
                    FilterChip(
                        selected = child.id == dashboard.selectedChildId,
                        onClick = { onSelectChild(child.id) },
                        label = { Text(child.childName) },
                    )
                }
            }
        }
    }
}

/** B. Protection enabled/off plus the real capability state. */
@Composable
private fun ProtectionStatusCard(dashboard: DashboardUiState, onOpenProtection: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_protection_title)) {
        Text(
            stringResource(
                if (dashboard.protectionEnabled) R.string.dashboard_protection_on
                else R.string.dashboard_protection_off,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(
                if (dashboard.runtimeActive) R.string.dashboard_protection_active
                else R.string.dashboard_protection_inactive,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (dashboard.protectionEnabled && !dashboard.enforcementReady) {
            // Protection is on but a prerequisite is missing: never present this
            // as "everything is protected".
            Text(
                stringResource(R.string.dashboard_capability_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            CapabilityLine(dashboard.overlayGranted, R.string.protection_req_overlay)
            CapabilityLine(dashboard.usageAccessGranted, R.string.protection_req_usage)
            CapabilityLine(dashboard.accessibilityEnabled, R.string.protection_req_accessibility)
            CapabilityLine(dashboard.parentFaceEnrolled, R.string.protection_req_parent_face)
            OutlinedButton(onClick = onOpenProtection, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_capability_open))
            }
        } else if (dashboard.enforcementReady) {
            Text(
                stringResource(R.string.dashboard_capability_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CapabilityLine(satisfied: Boolean, labelRes: Int) {
    val label = stringResource(labelRes)
    Text(
        text = stringResource(
            if (satisfied) R.string.dashboard_capability_ready else R.string.dashboard_capability_needed,
            label,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (satisfied) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

/** C. The current runtime cycle (authoritative current state, never from events). */
@Composable
private fun ActiveProtectionCard(dashboard: DashboardUiState) {
    SectionCard(title = stringResource(R.string.dashboard_active_title)) {
        Text(stringResource(activeStatusLabelRes(dashboard)), style = MaterialTheme.typography.bodyLarge)

        livenessLabelRes(dashboard.liveness)?.let { res ->
            Text(
                stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        dashboard.blockedApp?.let { pkg ->
            Text(
                stringResource(R.string.dashboard_active_app, pkg),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (dashboard.protectionState == ProtectionState.RECOVERING) {
            Text(
                stringResource(R.string.dashboard_active_recovering),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The current-state line: the live identity when the runtime knows one, else the
 * protection state, else the neutral "nothing active" text. Domain -> resource
 * mapping lives in [DashboardState], not scattered through the UI.
 */
private fun activeStatusLabelRes(dashboard: DashboardUiState): Int = when {
    dashboard.identity != null -> identityLabelRes(dashboard.identity)
    dashboard.protectionState == ProtectionState.UNPROTECTED -> R.string.dashboard_active_none
    else -> protectionStateLabelRes(dashboard.protectionState)
}

/** D. Per-child policy summary + configured limits (no usage). */
@Composable
private fun ChildPolicySummaryCard(
    dashboard: DashboardUiState,
    onOpenChildPolicy: (Long) -> Unit,
    onOpenChildren: () -> Unit,
) {
    val child = dashboard.child ?: return
    SectionCard(title = stringResource(R.string.dashboard_policy_title)) {
        if (child.policiesLoading) {
            Text(stringResource(R.string.dashboard_policy_loading), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        if (child.policies.total == 0) {
            Text(
                stringResource(R.string.dashboard_policy_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(
                    R.string.dashboard_policy_counts,
                    child.policies.total,
                    child.policies.allow,
                    child.policies.limit,
                    child.policies.block,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (child.limits.isNotEmpty()) {
            Text(
                stringResource(R.string.dashboard_policy_limits_title),
                style = MaterialTheme.typography.titleSmall,
            )
            child.limits.forEach { limit ->
                Text(
                    text = if (limit.dailyLimitMinutes != null) {
                        stringResource(R.string.dashboard_policy_limit_line, limit.packageName, limit.dailyLimitMinutes)
                    } else {
                        stringResource(R.string.dashboard_policy_limit_line_unknown, limit.packageName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Phase 4 (usage accounting) does not exist: say so honestly instead of
            // showing a fabricated remaining/used figure.
            Text(
                stringResource(R.string.dashboard_policy_no_usage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = { child.childId?.let(onOpenChildPolicy) ?: onOpenChildren() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dashboard_policy_open))
        }
    }
}

/** F. Global protected-app catalog summary. */
@Composable
private fun ProtectedAppsSummaryCard(dashboard: DashboardUiState, onOpenProtectedApps: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_apps_title)) {
        Text(
            stringResource(R.string.dashboard_apps_count, dashboard.protectedAppsCount),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (dashboard.protectedAppsCount == 0) {
            Text(
                stringResource(R.string.dashboard_apps_none_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onOpenProtectedApps, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_apps_open))
        }
    }
}

/** G. Child profile metadata (no biometric data). */
@Composable
private fun ChildProfileSummaryCard(dashboard: DashboardUiState, onOpenChildren: () -> Unit) {
    val child = dashboard.child ?: return
    SectionCard(title = stringResource(R.string.dashboard_profile_title)) {
        Text(
            child.name ?: stringResource(R.string.dashboard_value_unknown),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(
                if (child.faceEnrolled) R.string.dashboard_child_face_on
                else R.string.dashboard_child_face_off,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.children_level_label, stringResource(restrictionLevelLabelRes(child.level))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenChildren, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_profile_open))
        }
    }
}

/** E. Historical events only - never used to infer the current runtime state. */
@Composable
private fun RecentActivityCard(dashboard: DashboardUiState, onOpenActivity: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_activity_title)) {
        if (dashboard.recentEvents.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_activity_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dashboard.recentEvents.forEach { event ->
                val label = stringResource(eventLabelRes(event.type))
                Text(
                    text = event.detail?.let { "$label · $it" } ?: label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        TextButton(onClick = onOpenActivity, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_activity_open))
        }
    }
}

/**
 * Phase 4 Step 1B-8: which child this device's screen time is tracked for.
 *
 * Deliberately worded as a *configuration* choice ("screen-time child"), never as
 * recognition: this is not "who is holding the phone", which is what the protection engine
 * decides from faces. No child is pre-selected — until the parent chooses, the background
 * collector attributes nothing.
 */
@Composable
private fun UsageCard(
    target: ScreenTimeTargetUiState,
    onSelectChild: (Long) -> Unit,
    onOpenChildren: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.screentime_target_title),
        subtitle = stringResource(R.string.screentime_target_subtitle),
    ) {
        when {
            target.status == ScreenTimeTargetStatus.LOADING -> Text(
                stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            target.status == ScreenTimeTargetStatus.ERROR -> Text(
                stringResource(R.string.screentime_target_error_load),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            // No account: nothing to configure on a signed-out device.
            target.status == ScreenTimeTargetStatus.NO_ACCOUNT -> Text(
                stringResource(R.string.dashboard_no_account_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            target.noChildren -> {
                Text(
                    stringResource(R.string.screentime_target_no_children),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenChildren, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dashboard_child_add))
                }
            }

            else -> {
                // The parent must choose: no child is marked until they do.
                if (target.needsSelection) {
                    Text(
                        stringResource(R.string.screentime_target_none_selected),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (target.activeChildMissing) {
                    Text(
                        stringResource(R.string.screentime_target_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                target.children.forEach { child ->
                    val selected = child.id == target.activeChildId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = !target.saving,
                                role = Role.RadioButton,
                                onClick = { onSelectChild(child.id) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null, // the whole row is the target
                            enabled = !target.saving,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = child.childName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text(
                    text = if (target.activeChild != null) {
                        stringResource(R.string.screentime_target_active_hint, target.activeChild!!.childName)
                    } else {
                        stringResource(R.string.screentime_target_choose_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (target.errorMessageRes != null) {
                    Text(
                        stringResource(target.errorMessageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Seven-step readiness checklist with a simple progress indicator. */
@Composable
private fun SetupChecklistCard(
    account: UserAccount?,
    parentProfile: ParentProfile?,
    dashboard: DashboardUiState,
    settings: AppSettings,
) {
    val items = listOf(
        (account != null) to R.string.setup_account,
        (parentProfile != null) to R.string.setup_parent_profile,
        (parentProfile?.isFaceEnrolled == true) to R.string.setup_parent_face,
        dashboard.children.isNotEmpty() to R.string.setup_child_added,
        dashboard.children.any { it.isFaceEnrolled } to R.string.setup_child_face,
        (dashboard.protectedAppsCount > 0) to R.string.setup_protected_apps,
        settings.protectionEnabled to R.string.setup_protection_enabled,
    )
    val completed = items.count { it.first }
    val total = items.size
    val allDone = completed == total
    val nextStep = items.firstOrNull { !it.first }?.second

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)

            if (allDone) {
                Text(
                    stringResource(R.string.setup_all_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    stringResource(R.string.home_setup_progress, completed, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { completed.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                nextStep?.let { label ->
                    Text(
                        stringResource(R.string.setup_next_step, stringResource(label)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items.forEach { (done, labelRes) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(if (done) R.string.setup_done_mark else R.string.setup_todo_mark),
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActionsCard(
    onOpenProtection: () -> Unit,
    onOpenParent: () -> Unit,
    onOpenChildren: () -> Unit,
    onOpenProtectedApps: () -> Unit,
    onOpenSettings: () -> Unit,
    protectedCount: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.home_main_actions_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onOpenProtection, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.protection_title))
            }
            Button(onClick = onOpenParent, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_menu_parent))
            }
            Button(onClick = onOpenChildren, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_menu_children))
            }
            Button(onClick = onOpenProtectedApps, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_menu_protected_apps))
            }
            Text(
                stringResource(R.string.home_protected_count, protectedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_menu_settings))
            }
        }
    }
}

@Composable
private fun AdditionalSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_additional_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onToggle) {
                    Text(
                        stringResource(
                            if (expanded) R.string.home_section_hide else R.string.home_section_show,
                        ),
                    )
                }
            }
            if (expanded) {
                OutlinedButton(onClick = onOpenActivity, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_menu_activity))
                }
                OutlinedButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_menu_privacy))
                }
                OutlinedButton(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_menu_help))
                }
            }
        }
    }
}

@Composable
private fun DeveloperSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenRecognition: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_developer_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onToggle) {
                    Text(
                        stringResource(
                            if (expanded) R.string.home_section_hide else R.string.home_section_show,
                        ),
                    )
                }
            }
            if (expanded) {
                Text(
                    stringResource(R.string.home_developer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenRecognition, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_recognition_debug))
                }
                ForegroundDebugCard()
            }
        }
    }
}

@Composable
private fun ForegroundDebugCard() {
    val context = LocalContext.current
    val monitor = remember { ForegroundAppMonitor(context) }
    var hasAccess by remember { mutableStateOf(monitor.hasUsageAccess()) }
    val foreground by monitor.current.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        monitor.start(scope)
        onDispose { monitor.stop() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_foreground_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (!hasAccess) {
                Text(stringResource(R.string.home_usage_access_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(monitor.usageAccessIntent())
                        hasAccess = monitor.hasUsageAccess()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_usage_access_grant))
                }
            } else {
                Text(
                    stringResource(
                        R.string.home_foreground_current,
                        foreground ?: stringResource(R.string.home_foreground_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Phase 11: pending parent requests (durable request rows — never a count of
 * protection events) plus the honest notification-capability warning. A denied
 * notification permission never hides or blocks a request, so this is only a
 * warning about *delivery*.
 */
@Composable
private fun RequestsSummaryCard(dashboard: DashboardUiState, onOpenRequests: () -> Unit) {
    SectionCard(title = stringResource(R.string.requests_title)) {
        if (dashboard.hasPendingRequests) {
            Text(
                stringResource(R.string.dashboard_requests_pending, dashboard.pendingRequestCount),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(R.string.dashboard_requests_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!dashboard.notificationsEnabled) {
            Text(
                stringResource(R.string.dashboard_notifications_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = onOpenRequests, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_requests_open))
        }
    }
}
