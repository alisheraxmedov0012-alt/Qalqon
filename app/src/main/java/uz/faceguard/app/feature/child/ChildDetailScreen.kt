package uz.faceguard.app.feature.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.qalqon.QalqonAlertRow
import uz.faceguard.app.core.ui.qalqon.QalqonBadge
import uz.faceguard.app.core.ui.qalqon.QalqonChildCard
import uz.faceguard.app.core.ui.qalqon.QalqonEmptyState
import uz.faceguard.app.core.ui.qalqon.QalqonLoadingState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.core.ui.qalqon.QalqonSettingRow
import uz.faceguard.app.core.ui.qalqon.QalqonStatusCard
import uz.faceguard.app.core.ui.qalqon.toneColor
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.schedule.ScheduleRepository
import uz.faceguard.app.feature.home.HomeProtectionStatus
import uz.faceguard.app.feature.home.homeProtectionLabelRes
import uz.faceguard.app.feature.home.homeProtectionSupportingRes
import uz.faceguard.app.feature.home.homeProtectionTone

/** Navigation argument carrying the child this hub is scoped to. */
object ChildDetailArgs {
    const val CHILD_ID = "childId"
}

enum class ChildDetailStatus { LOADING, READY, MISSING, ERROR }

/** The child's configuration state as the hub's control rows present it. */
data class ChildDetailUiState(
    val status: ChildDetailStatus = ChildDetailStatus.LOADING,
    val childId: Long = -1L,
    val name: String? = null,
    val initial: String = "?",
    val faceEnrolled: Boolean = false,
    val protectionStatus: HomeProtectionStatus = HomeProtectionStatus.OFF,
    val pendingRequestCount: Int = 0,
    val appsCount: Int = 0,
    val scheduleCount: Int = 0,
    val eyeSafetyConfigured: Boolean = false,
    val eyeSafetyEnabled: Boolean = false,
)

/**
 * UI/UX redesign, Phase 4: the Child Detail hub's state.
 *
 * A read-only aggregation of existing, child-scoped repositories plus the existing
 * [ProtectionRuntime] — no new data source, no calculation and no fabricated value.
 * Every child-scoped flow is keyed by the route's child id, so a different child can
 * never leak into this screen's data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChildDetailViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val childRepository: ChildProfileRepository,
    private val appPolicyRepository: ChildAppPolicyRepository,
    private val scheduleRepository: ScheduleRepository,
    private val eyeSafetyRepository: EyeSafetyRepository,
    private val requestRepository: ParentRequestRepository,
    private val runtime: ProtectionRuntime,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val childId: Long = savedStateHandle.get<Long>(ChildDetailArgs.CHILD_ID) ?: -1L

    val ui: StateFlow<ChildDetailUiState> = accountRepository.currentAccountId
        .flatMapLatest { accountId ->
            if (accountId == null) flowOf(ChildDetailUiState(status = ChildDetailStatus.ERROR))
            else observe(accountId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChildDetailUiState())

    private fun observe(accountId: Long): Flow<ChildDetailUiState> {
        val children = childRepository.observeChildren(accountId).catch { emit(emptyList()) }
        val policies = appPolicyRepository.observePolicies(accountId, childId).catch { emit(emptyList()) }
        val schedules = scheduleRepository.observeSchedules(accountId, childId).catch { emit(emptyList()) }
        val eyeSafety = eyeSafetyRepository.observeConfig(accountId, childId).catch { emit(null) }
        val requests = requestRepository.observePendingForChild(accountId, childId).catch { emit(emptyList()) }

        val childScoped = combine(children, policies, schedules) { kids, policyList, scheduleList ->
            Triple(kids, policyList.size, scheduleList.size)
        }
        return combine(childScoped, eyeSafety, requests, runtime.state) { scoped, config, requestList, runtimeState ->
            val (kids, appCount, scheduleCount) = scoped
            val child = kids.firstOrNull { it.id == childId }
                ?: return@combine ChildDetailUiState(status = ChildDetailStatus.MISSING, childId = childId)

            ChildDetailUiState(
                status = ChildDetailStatus.READY,
                childId = childId,
                name = child.childName,
                initial = child.childName.trim().firstOrNull()?.uppercase() ?: "?",
                faceEnrolled = child.isFaceEnrolled,
                protectionStatus = protectionStatusOf(runtimeState),
                pendingRequestCount = requestList.size,
                appsCount = appCount,
                scheduleCount = scheduleCount,
                eyeSafetyConfigured = config != null,
                eyeSafetyEnabled = config?.config?.enabled == true,
            )
        }
    }

    private fun protectionStatusOf(state: ProtectionRuntimeState): HomeProtectionStatus {
        val ready = state.overlayGranted && state.usageAccessGranted && state.accessibilityEnabled
        return childProtectionStatus(
            protectionEnabled = state.enabled,
            protectionState = state.protectionState,
            enforcementReady = ready,
        )
    }
}

/**
 * UI/UX redesign, Phase 4: the Child Detail hub.
 *
 * The single place where every child-scoped control is reachable: identity,
 * protection status, child-scoped attention, then the control rows that link to the
 * existing destinations. It is deliberately not a settings dump — each row hands off
 * to the screen that owns the detailed configuration, keeping the child context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildDetailScreen(
    onBack: () -> Unit,
    onOpenApps: (Long) -> Unit,
    onOpenScreenTime: (Long) -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onOpenEyeSafety: (Long) -> Unit,
    onOpenFace: (Long) -> Unit,
    onOpenRequests: () -> Unit,
    viewModel: ChildDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ui.name ?: stringResource(R.string.children_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.child_detail_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (ui.status) {
            ChildDetailStatus.LOADING -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonLoadingState()
            }

            ChildDetailStatus.ERROR -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonEmptyState(
                    title = stringResource(R.string.dashboard_no_account),
                    description = stringResource(R.string.dashboard_no_account_hint),
                )
            }

            ChildDetailStatus.MISSING -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonEmptyState(
                    title = stringResource(R.string.child_detail_missing),
                    description = stringResource(R.string.child_detail_missing_hint),
                )
            }

            ChildDetailStatus.READY -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = QalqonDimens.screenPadding,
                    end = QalqonDimens.screenPadding,
                    top = QalqonDimens.spacing.lg,
                    bottom = QalqonDimens.spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.lg),
            ) {
                item { ChildIdentityHeader(ui) }
                item { ChildProtectionStatus(ui) }

                val attention = childAttentionItems(ui.protectionStatus, ui.faceEnrolled)
                if (attention.isNotEmpty()) {
                    item { ChildAttentionSection(attention) }
                }

                item {
                    ChildControlsSection(
                        ui = ui,
                        onOpenApps = { onOpenApps(ui.childId) },
                        onOpenScreenTime = { onOpenScreenTime(ui.childId) },
                        onOpenSchedule = { onOpenSchedule(ui.childId) },
                        onOpenEyeSafety = { onOpenEyeSafety(ui.childId) },
                        onOpenFace = { onOpenFace(ui.childId) },
                        onOpenRequests = onOpenRequests,
                    )
                }
            }
        }
    }
}

/** Identity: avatar, name, face state — the child's own, in one place. */
@Composable
private fun ChildIdentityHeader(ui: ChildDetailUiState) {
    QalqonChildCard(
        name = ui.name.orEmpty(),
        initial = ui.initial,
        protectionConfigured = ui.faceEnrolled,
        faceEnrolled = ui.faceEnrolled,
        faceStatus = stringResource(
            if (ui.faceEnrolled) R.string.children_face_on else R.string.children_face_off,
        ),
    )
}

/** The account's protection verdict, presented with the Home status vocabulary. */
@Composable
private fun ChildProtectionStatus(ui: ChildDetailUiState) {
    QalqonStatusCard(
        title = stringResource(homeProtectionLabelRes(ui.protectionStatus)),
        statusColor = toneColor(homeProtectionTone(ui.protectionStatus)),
        supportingText = stringResource(homeProtectionSupportingRes(ui.protectionStatus)),
    ) {}
}

@Composable
private fun ChildAttentionSection(items: List<ChildAttentionItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        QalqonSectionHeader(title = stringResource(R.string.home_attention_title))
        items.forEach { item ->
            QalqonAlertRow(
                text = stringResource(item.messageRes),
                severity = item.severity,
            )
        }
    }
}

/** The control hub: one row per child-scoped destination, each ≥48dp. */
@Composable
private fun ChildControlsSection(
    ui: ChildDetailUiState,
    onOpenApps: () -> Unit,
    onOpenScreenTime: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenEyeSafety: () -> Unit,
    onOpenFace: () -> Unit,
    onOpenRequests: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs)) {
        QalqonSectionHeader(title = stringResource(R.string.child_detail_controls_title))
        CHILD_DETAIL_CONTROLS.forEach { control ->
            QalqonSettingRow(
                title = stringResource(childControlLabelRes(control)),
                description = controlDescription(control, ui),
                leading = {
                    Icon(
                        imageVector = childControlIcon(control),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing = {
                    if (control == ChildDetailControl.REQUESTS && ui.pendingRequestCount > 0) {
                        QalqonBadge(
                            count = ui.pendingRequestCount,
                            contentDescription = stringResource(
                                R.string.dashboard_requests_pending,
                                ui.pendingRequestCount,
                            ),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = when (control) {
                    ChildDetailControl.APPS -> onOpenApps
                    ChildDetailControl.SCREEN_TIME -> onOpenScreenTime
                    ChildDetailControl.SCHEDULE -> onOpenSchedule
                    ChildDetailControl.EYE_SAFETY -> onOpenEyeSafety
                    ChildDetailControl.FACE -> onOpenFace
                    ChildDetailControl.REQUESTS -> onOpenRequests
                },
            )
        }
    }
}

/**
 * The row's supporting line: a real, child-scoped status when the hub has one,
 * otherwise the control's static description. Never a fabricated value.
 */
@Composable
private fun controlDescription(control: ChildDetailControl, ui: ChildDetailUiState): String = when (control) {
    // No per-child app count of its own, so the row states its purpose rather than
    // borrowing an account-level figure.
    ChildDetailControl.APPS -> stringResource(R.string.child_detail_apps_desc)

    ChildDetailControl.SCREEN_TIME -> stringResource(R.string.child_detail_screen_time_desc)

    ChildDetailControl.SCHEDULE -> stringResource(R.string.child_detail_schedule_desc)

    ChildDetailControl.EYE_SAFETY -> when {
        !ui.eyeSafetyConfigured -> stringResource(R.string.eye_safety_status_not_configured)
        ui.eyeSafetyEnabled -> stringResource(R.string.eye_safety_status_enabled)
        else -> stringResource(R.string.eye_safety_status_disabled)
    }

    ChildDetailControl.FACE -> stringResource(R.string.child_detail_face_desc)

    ChildDetailControl.REQUESTS ->
        if (ui.pendingRequestCount > 0) {
            stringResource(R.string.dashboard_requests_pending, ui.pendingRequestCount)
        } else {
            stringResource(R.string.child_detail_requests_desc)
        }
}

@Composable
private fun childControlIcon(control: ChildDetailControl): ImageVector = when (control) {
    ChildDetailControl.APPS -> Icons.AutoMirrored.Filled.List
    ChildDetailControl.SCREEN_TIME -> Icons.Filled.DateRange
    ChildDetailControl.SCHEDULE -> Icons.Filled.Info
    ChildDetailControl.EYE_SAFETY -> Icons.Filled.Warning
    ChildDetailControl.FACE -> Icons.Filled.Face
    ChildDetailControl.REQUESTS -> Icons.Filled.Notifications
}
