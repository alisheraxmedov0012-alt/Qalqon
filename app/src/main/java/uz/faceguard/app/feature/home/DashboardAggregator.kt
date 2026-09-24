package uz.faceguard.app.feature.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.repository.SettingsRepository

/**
 * Phase 10: a read-only aggregation layer over existing application state.
 *
 * Room/DataStore/runtime -> this aggregator -> [DashboardUiState] -> Compose.
 * It never queries a DAO/DataStore directly, never talks to the camera or the
 * accessibility service, and never decides whether a child should be blocked —
 * it only presents the existing runtime/domain state.
 *
 * Isolation is structural:
 * - the whole account chain is re-created per account id ([flatMapLatest]), so a
 *   previous account's children/policies/events can never leak into the next;
 * - child-scoped data is keyed by the resolved child id and only applied when it
 *   still matches the current selection, so a slow load for a previously selected
 *   child cannot overwrite the newly selected one;
 * - usage is never fabricated (Phase 4 does not exist).
 */
class DashboardAggregator(
    private val accountRepository: AccountRepository,
    private val childRepository: ChildProfileRepository,
    private val policyRepository: ChildAppPolicyRepository,
    private val protectedAppsRepository: ProtectedAppsRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val settingsRepository: SettingsRepository,
    private val requestRepository: ParentRequestRepository,
    private val runtimeState: Flow<ProtectionRuntimeState>,
    private val recentEventLimit: Int = RECENT_EVENT_LIMIT,
) {

    private val selectedChildId = MutableStateFlow<Long?>(null)
    private val refreshTrigger = MutableStateFlow(0L)

    /** Parent switched the visible child. */
    fun selectChild(childId: Long?) {
        selectedChildId.value = childId
    }

    /** Retry after a failed load (also used as an explicit refresh). */
    fun retry() {
        refreshTrigger.value += 1
    }

    fun observe(scope: CoroutineScope): StateFlow<DashboardUiState> {
        // A selection from a previous account must not survive the account switch.
        scope.launch {
            accountRepository.currentAccountId.distinctUntilChanged().collect {
                selectedChildId.value = null
            }
        }
        return refreshTrigger
            .flatMapLatest {
                accountRepository.currentAccountId
                    .distinctUntilChanged()
                    .flatMapLatest { accountId ->
                        if (accountId == null) flowOf(DashboardUiState(status = DashboardStatus.NO_ACCOUNT))
                        else accountDashboard(accountId)
                    }
                    // Handled inside the trigger scope so a failure ends only this
                    // attempt: the outer chain stays alive and retry() re-subscribes.
                    .catch {
                        emit(DashboardUiState(status = DashboardStatus.ERROR, errorMessageRes = R.string.dashboard_error))
                    }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())
    }

    /** Child-scoped policies, tagged with the child they belong to. */
    private data class ChildScopedPolicies(val childId: Long?, val policies: List<AppPolicy>)

    private data class Sources(
        val children: List<ChildProfile>,
        val resolvedChildId: Long?,
        val policies: ChildScopedPolicies,
        val protectedApps: List<ProtectedApp>,
        val events: List<ActivityEvent>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun accountDashboard(accountId: Long): Flow<DashboardUiState> {
        val children = childRepository.observeChildren(accountId).catch { emit(emptyList()) }

        val resolvedChildId = combine(children, selectedChildId) { kids, selected ->
            resolveSelectedChild(kids, current = selected)
        }.distinctUntilChanged()

        val policies = resolvedChildId.flatMapLatest { childId ->
            if (childId == null) {
                flowOf(ChildScopedPolicies(null, emptyList()))
            } else {
                policyRepository.observePolicies(accountId, childId)
                    .map<List<AppPolicy>, ChildScopedPolicies> { ChildScopedPolicies(childId, it) }
                    .catch { emit(ChildScopedPolicies(childId, emptyList())) }
            }
        }

        val protectedApps = protectedAppsRepository.protectedApps.catch { emit(emptyList()) }
        val events = activityLogRepository.recent(accountId).catch { emit(emptyList()) }
        val pendingRequests = requestRepository.observePendingCount(accountId).catch { emit(0) }

        val core = combine(children, resolvedChildId, policies, protectedApps, events) { kids, childId, policySnapshot, apps, eventList ->
            Sources(kids, childId, policySnapshot, apps, eventList)
        }

        return combine(
            core,
            settingsRepository.settings.catch { emit(AppSettings()) },
            runtimeState,
            pendingRequests,
        ) { sources, settings, runtime, pending -> buildState(sources, settings, runtime, pending) }
    }

    private fun buildState(
        sources: Sources,
        settings: AppSettings,
        runtime: ProtectionRuntimeState,
        pendingRequestCount: Int,
    ): DashboardUiState {
        val selectedChildId = sources.resolvedChildId
        val profile = sources.children.firstOrNull { it.id == selectedChildId }

        // Keep a previous child's policies off screen until the new child's load lands.
        val policiesCurrent = isChildScopedCurrent(sources.policies.childId, selectedChildId)
        val policies = if (policiesCurrent) sources.policies.policies else emptyList()
        val policiesLoading = selectedChildId != null && !policiesCurrent

        val child = selectedChildId?.let { id ->
            ChildSection(
                childId = id,
                name = profile?.childName,
                faceEnrolled = profile?.isFaceEnrolled == true,
                level = profile?.restrictionLevel,
                policies = policyCounts(policies),
                limits = configuredLimits(policies),
                policiesLoading = policiesLoading,
            )
        }

        return DashboardUiState(
            status = DashboardStatus.READY,
            selectedChildId = selectedChildId,
            children = sources.children,
            child = child,
            protectionEnabled = settings.protectionEnabled,
            runtimeActive = runtime.active,
            protectionState = runtime.protectionState,
            identity = runtime.identity?.identity,
            liveness = runtime.liveness?.state,
            blockedApp = runtime.blockedApp,
            overlayGranted = runtime.overlayGranted,
            usageAccessGranted = runtime.usageAccessGranted,
            accessibilityEnabled = runtime.accessibilityEnabled,
            parentFaceEnrolled = runtime.parentFaceEnrolled,
            protectedAppsCount = sources.protectedApps.count { it.isProtected },
            pendingRequestCount = pendingRequestCount,
            notificationsEnabled = runtime.notificationsEnabled,
            recentEvents = recentEventSummaries(sources.events, recentEventLimit),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
