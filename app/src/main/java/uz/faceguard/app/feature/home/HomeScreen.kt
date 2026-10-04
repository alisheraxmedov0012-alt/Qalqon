package uz.faceguard.app.feature.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonShapes
import uz.faceguard.app.core.theme.QalqonTheme
import uz.faceguard.app.core.ui.qalqon.QalqonAlertRow
import uz.faceguard.app.core.ui.qalqon.QalqonAlertSeverity
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonChildCard
import uz.faceguard.app.core.ui.qalqon.QalqonDivider
import uz.faceguard.app.core.ui.qalqon.QalqonEmptyState
import uz.faceguard.app.core.ui.qalqon.QalqonErrorState
import uz.faceguard.app.core.ui.qalqon.ProtectionDegradedBanner
import uz.faceguard.app.core.ui.qalqon.QalqonLoadingState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.core.ui.qalqon.QalqonStatusBadge
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.core.ui.qalqon.severityColor
import uz.faceguard.app.core.ui.qalqon.toneColor
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.core.ui.protectionCapabilityLabelRes
import uz.faceguard.app.core.ui.qalqon.QalqonStatusBanner
import uz.faceguard.app.domain.protection.PROTECTION_CAPABILITY_PRIORITY
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.highestPriorityMissing
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private val runtime: ProtectionRuntime,
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

    /**
     * Live protection health, so Home can warn when a capability was revoked. Read
     * straight from the app-scoped runtime (the single source of truth), never
     * recomputed here.
     */
    val protectionState: StateFlow<ProtectionRuntimeState> = runtime.state

    /** Re-probes capabilities (called when Home resumes) so a revoked one surfaces at once. */
    fun refreshProtectionCapabilities() = runtime.refreshPermissions()

    /** The system settings page that fixes [capability], for the degraded banner's action. */
    fun capabilitySettingsIntent(capability: ProtectionCapability): Intent =
        runtime.capabilitySettingsIntent(capability)

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
    val protectionState by viewModel.protectionState.collectAsStateWithLifecycle()

    // Re-probe capabilities whenever Home resumes, so a permission revoked in system
    // settings surfaces as a degraded banner as soon as the parent comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshProtectionCapabilities()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        // The light-blue canvas is what makes the white cards read as premium,
        // floating surfaces rather than one flat grey page.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HomeHeader(
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
                    top = QalqonDimens.spacing.sm,
                    bottom = QalqonDimens.spacing.lg,
                ),
                // A compact, intentional vertical rhythm: the reference keeps the page
                // tight rather than stacking large blocks with big gaps.
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
            ) {
                // Priority order: the protection verdict, then the single banner area
                // (most severe first), then today's states, the children and the actions.
                item { ProtectionStatusSection(dashboard, onOpenProtection) }

                // ONE banner area: degraded protection, camera-limited-after-boot,
                // notifications off, pending requests and children needing setup are
                // merged into a single, most-severe-first list — never separate cards.
                item {
                    HomeBannerArea(
                        banners = homeBanners(
                            state = dashboard,
                            degradedCapabilities = protectionState.degradedCapabilities,
                            cameraLimitedAfterBoot = protectionState.cameraLimitedAfterBoot,
                        ),
                        degradedCapabilities = protectionState.degradedCapabilities,
                        onFixCapability = { capability ->
                            context.startActivity(viewModel.capabilitySettingsIntent(capability))
                        },
                        onEnableNotifications = { context.startActivity(notificationSettingsIntent(context)) },
                        onOpenRequests = onOpenRequests,
                        onOpenChildren = onOpenChildren,
                    )
                }

                item { TodaySection(dashboard, screenTimeSummary, onOpenChildPolicy, onOpenChildren) }
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
 * The Home header: a compact identity block rather than a Material app bar.
 *
 * The parent's initial sits on a circular container, followed by the localized
 * greeting (the parent's own name when known, else the plain title) and a secondary
 * supporting line. The overflow holds the secondary destinations that no
 * bottom-navigation tab owns (Privacy, Help), so they stay reachable; the developer
 * diagnostics entry is present only when [DebugFlags] enables the debug screens, so
 * it never appears in a production UI.
 */
@Composable
private fun HomeHeader(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = QalqonDimens.screenPadding,
                end = QalqonDimens.spacing.sm,
                top = QalqonDimens.spacing.md,
                bottom = QalqonDimens.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A known name shows its initial; an unknown name shows the QALQON protection
        // mark, never an invented identity.
        Box(
            modifier = Modifier
                .size(QalqonDimens.sizes.buttonDefault)
                .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (initial != null) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(QalqonDimens.icon.md),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = QalqonDimens.spacing.md),
        ) {
            Text(
                text = greeting ?: stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
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
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.home_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
    }
}

/** Duration of the hero's state-colour cross-fade; short enough to feel instant. */
private const val HERO_COLOR_ANIM_MS = 300

/**
 * The OS page where the parent can re-enable Qalqon's notifications. Deep-links to the
 * app's own notification settings, so the banner's action lands on the right screen.
 */
private fun notificationSettingsIntent(context: android.content.Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * The signature QALQON protection hero — the Home screen's focal point.
 *
 * Composed to the reference layout (Variant A): a semantic protection icon and the
 * QALQON wordmark + live state copy on the left, a large protective shield/device
 * emblem anchoring the right, and an action-first full-width CTA at the bottom. The
 * order reads STATE -> EXPLANATION -> ACTION on a large, very light tonal surface.
 *
 * Presentation only: every value comes from [DashboardUiState] via the existing
 * presentation helpers — no protection logic, no fabricated state.
 */
@Composable
private fun ProtectionStatusSection(state: DashboardUiState, onOpenProtection: () -> Unit) {
    val status = homeProtectionStatus(state)
    val tone = homeProtectionTone(status)
    val actionLabel = homeProtectionActionLabelRes(status)
    val statusLabel = stringResource(homeProtectionLabelRes(status))
    val runCount = (status == HomeProtectionStatus.ACTIVE || status == HomeProtectionStatus.BLOCKING) &&
        state.protectedAppsCount > 0

    // ON = green, OFF/setup/recovering = amber, a live block = red. The colour is a
    // semantic token, animated so a state change reads as a calm transition rather
    // than a hard swap.
    val heroSurface = homeProtectionHeroSurface(status)
    val cardColor by animateColorAsState(
        targetValue = when (heroSurface) {
            HomeHeroSurface.SUCCESS -> QalqonTheme.colors.successContainer
            HomeHeroSurface.WARNING -> QalqonTheme.colors.warningContainer
            HomeHeroSurface.BLOCKING -> MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = tween(durationMillis = HERO_COLOR_ANIM_MS),
        label = "heroCardColor",
    )
    val shieldAccent by animateColorAsState(
        targetValue = when (heroSurface) {
            HomeHeroSurface.SUCCESS -> QalqonTheme.colors.protectionActive
            HomeHeroSurface.WARNING -> QalqonTheme.colors.protectionWarning
            HomeHeroSurface.BLOCKING -> QalqonTheme.colors.protectionBlocking
        },
        animationSpec = tween(durationMillis = HERO_COLOR_ANIM_MS),
        label = "heroShieldColor",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = QalqonShapes.xLargeShape,
        // State-tinted surface (green when protected, amber when off, red when blocking).
        color = cardColor,
        tonalElevation = QalqonDimens.elevation.flat,
    ) {
        Column(
            modifier = Modifier.padding(QalqonDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
        ) {
            // Reference hero: the text block on the left, the illustration on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.app_name).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(homeProtectionSupportingRes(status)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(QalqonDimens.spacing.md))
                // Right: the prominent shield + smartphone illustration (>= 100 dp), the
                // hero's visual anchor — not a small status icon.
                QalqonProtectionMotif(
                    accent = shieldAccent,
                    modifier = Modifier.size(QalqonDimens.sizes.illustration),
                )
            }
            state.blockedApp?.let { packageName ->
                Text(
                    text = stringResource(R.string.dashboard_active_app, packageName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Real metadata chip: the actual protected-app count, only while protection runs.
            if (runCount) {
                QalqonStatusBadge(
                    label = stringResource(R.string.home_protection_apps_count, state.protectedAppsCount),
                    tone = tone,
                )
            }
            // The hero's primary, full-width pill action in the mockup's deep blue, with
            // a shield glyph (the mockup's "🛡️"), the label and a forward chevron. The
            // shield is a real icon rather than an emoji glyph, per the product's icon rules.
            Button(
                onClick = onOpenProtection,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QalqonDimens.sizes.buttonDefault),
                shape = QalqonShapes.pillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = QalqonTheme.colors.cta,
                    contentColor = QalqonTheme.colors.onCta,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(QalqonDimens.icon.sm),
                )
                Spacer(Modifier.size(QalqonDimens.spacing.sm))
                Text(stringResource(actionLabel))
                Spacer(Modifier.size(QalqonDimens.spacing.sm))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(QalqonDimens.icon.sm),
                )
            }
        }
    }
}

/**
 * The single Home banner area.
 *
 * Renders [banners] (already ordered most-severe-first by [homeBanners]) using the
 * shared [QalqonStatusBanner], each with its real action button when one exists. The
 * whole area renders nothing when the list is empty, so the caller can place it
 * unconditionally.
 */
@Composable
private fun HomeBannerArea(
    banners: List<HomeBanner>,
    degradedCapabilities: Set<ProtectionCapability>,
    onFixCapability: (ProtectionCapability) -> Unit,
    onEnableNotifications: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenChildren: () -> Unit,
) {
    if (banners.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        banners.forEach { banner ->
            when (banner.kind) {
                HomeBannerKind.PROTECTION_DEGRADED -> {
                    val names = PROTECTION_CAPABILITY_PRIORITY
                        .filter { it in degradedCapabilities }
                        .map { stringResource(protectionCapabilityLabelRes(it)) }
                        .joinToString(", ")
                    QalqonStatusBanner(
                        title = stringResource(R.string.protection_degraded_title),
                        text = stringResource(R.string.protection_degraded_body, names),
                        severity = banner.severity,
                        action = {
                            degradedCapabilities.highestPriorityMissing()?.let { capability ->
                                HomeBannerButton(
                                    stringResource(R.string.protection_degraded_action),
                                    onClick = { onFixCapability(capability) },
                                )
                            }
                        },
                    )
                }

                HomeBannerKind.CAMERA_LIMITED -> QalqonStatusBanner(
                    text = stringResource(R.string.protection_after_boot_camera),
                    severity = banner.severity,
                )

                HomeBannerKind.NOTIFICATIONS_DISABLED -> QalqonStatusBanner(
                    text = stringResource(R.string.dashboard_notifications_disabled),
                    severity = banner.severity,
                    action = {
                        HomeBannerButton(
                            stringResource(R.string.home_banner_notifications_action),
                            onClick = onEnableNotifications,
                        )
                    },
                )

                HomeBannerKind.PENDING_REQUESTS -> QalqonStatusBanner(
                    text = stringResource(R.string.dashboard_requests_pending, banner.count ?: 0),
                    severity = banner.severity,
                    action = {
                        HomeBannerButton(
                            stringResource(R.string.home_banner_requests_action),
                            onClick = onOpenRequests,
                        )
                    },
                )

                HomeBannerKind.CHILDREN_NEED_SETUP -> QalqonStatusBanner(
                    text = stringResource(R.string.home_attention_children_setup),
                    severity = banner.severity,
                    action = {
                        HomeBannerButton(
                            stringResource(R.string.home_banner_children_action),
                            onClick = onOpenChildren,
                        )
                    },
                )
            }
        }
    }
}

/** A real, tappable action inside a banner (meets the 48 dp touch target). */
@Composable
private fun HomeBannerButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = QalqonDimens.sizes.touchTarget),
    ) {
        Text(label)
    }
}

/**
 * The children overview: one tappable card per child, or a compact onboarding card
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
            supportingText = stringResource(R.string.home_children_subtitle),
            leading = { HomeSectionIcon(Icons.Filled.Person, MaterialTheme.colorScheme.primary) },
            action = allAction,
        )

        if (children.isEmpty()) {
            // Compact, intentional onboarding card: a friendly medallion, a bounded
            // explanation and one primary CTA — never a tall centred block.
            QalqonCard(
                modifier = Modifier.fillMaxWidth(),
                bordered = false,
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = QalqonDimens.elevation.raised,
                shape = QalqonShapes.xLargeShape,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(QalqonDimens.sizes.buttonDefault)
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
                            modifier = Modifier.size(QalqonDimens.icon.md),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = QalqonDimens.spacing.md),
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_child_none),
                            style = MaterialTheme.typography.titleSmall,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(QalqonDimens.sizes.buttonDefault),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(QalqonDimens.icon.sm),
                    )
                    Spacer(Modifier.size(QalqonDimens.spacing.sm))
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
                    // A captured local makes the non-null case explicit for the compiler
                    // (no `!!`), while the guard above still decides whether it is shown.
                    val total = screenTime.total
                    val todayScreenTime: String? = if (
                        screenTime.status == ScreenTimeSummaryStatus.READY &&
                        screenTime.childId == child.childId &&
                        total != null
                    ) {
                        stringResource(
                            R.string.home_child_screen_time,
                            durationLabel(total.usedMs),
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
private fun TodaySection(
    state: DashboardUiState,
    screenTime: ScreenTimeSummaryUiState,
    onOpenChildPolicy: (Long) -> Unit,
    onOpenScreenTimeChild: () -> Unit,
) {
    val metrics = homeTodayMetrics(state, screenTime)
    // The screen-time tile opens the selected child's detail hub (which owns screen
    // time); with no child selected it has no destination and stays static.
    val screenTimeTarget: (() -> Unit)? =
        screenTime.childId?.let { childId -> { onOpenChildPolicy(childId) } }
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
                    // The screen-time tile with no target offers a real button to pick a child.
                    val needsChild = metric.kind == HomeTodayMetricKind.SCREEN_TIME &&
                        metric.value is HomeMetricValue.Text &&
                        (metric.value as HomeMetricValue.Text).res == R.string.screentime_summary_no_target
                    HomeMetricTile(
                        metric = metric,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = when (metric.kind) {
                            HomeTodayMetricKind.SCREEN_TIME -> screenTimeTarget
                            HomeTodayMetricKind.SCHEDULE,
                            HomeTodayMetricKind.EYE_SAFETY,
                            -> null
                        },
                        inlineActionLabelRes = if (needsChild) R.string.home_screentime_select_child_action else null,
                        onInlineAction = if (needsChild) onOpenScreenTimeChild else null,
                    )
                }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The glyph for each metric; decorative, so the localized label carries the meaning. */
private fun homeMetricIcon(kind: HomeTodayMetricKind): ImageVector = when (kind) {
    // Reference mockup: a clock for screen time.
    HomeTodayMetricKind.SCREEN_TIME -> HomeClockIcon
    HomeTodayMetricKind.SCHEDULE -> Icons.Filled.Info
    HomeTodayMetricKind.EYE_SAFETY -> Icons.Filled.Warning
}

/**
 * The Home dashboard's own glyphs.
 *
 * The bundled Material icon set (`material-icons-core`) ships only ~50 glyphs and has
 * no clock, shield or group icon (the extended set is thousands of assets and is not a
 * dependency). These small hand-built vectors give the mockup's exact semantics without
 * pulling in that weight. Each is a single 24×24 path; `Icon`'s tint overrides the fill,
 * so one vector serves every theme and colour role.
 */
private fun homeIcon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = QalqonDimens.icon.lg,
        defaultHeight = QalqonDimens.icon.lg,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

/** An outlined clock (screen time / schedule). */
private val HomeClockIcon: ImageVector by lazy {
    homeIcon("HomeClock") {
        // Outer ring + inner cut-out = the outline.
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 22f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 2f)
            close()
            moveTo(12f, 4.4f)
            arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 19.6f)
            arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 4.4f)
            close()
        }
        // Twelve-o'clock hand and three-o'clock hand.
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.1f, 6.8f)
            lineTo(12.9f, 6.8f)
            lineTo(12.9f, 12.6f)
            lineTo(11.1f, 12.6f)
            close()
            moveTo(12.4f, 11.1f)
            lineTo(17.2f, 11.1f)
            lineTo(17.2f, 12.9f)
            lineTo(12.4f, 12.9f)
            close()
        }
    }
}

/** A solid shield (protection / protected apps). */
private val HomeShieldIcon: ImageVector by lazy {
    homeIcon("HomeShield") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            lineTo(20f, 5f)
            lineTo(20f, 11f)
            curveTo(20f, 16.2f, 16.6f, 20.2f, 12f, 22f)
            curveTo(7.4f, 20.2f, 4f, 16.2f, 4f, 11f)
            lineTo(4f, 5f)
            close()
        }
    }
}

/** A group of people (manage children). */
private val HomeGroupIcon: ImageVector by lazy {
    homeIcon("HomeGroup") {
        path(fill = SolidColor(Color.Black)) {
            // Two heads.
            moveTo(9f, 5.2f)
            arcTo(3.1f, 3.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9f, y1 = 11.4f)
            arcTo(3.1f, 3.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9f, y1 = 5.2f)
            close()
            moveTo(16.7f, 6.1f)
            arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16.7f, y1 = 11.3f)
            arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16.7f, y1 = 6.1f)
            close()
            // Two shoulders.
            moveTo(3.4f, 19f)
            curveTo(3.4f, 14.6f, 5.9f, 12.6f, 9f, 12.6f)
            curveTo(12.1f, 12.6f, 14.6f, 14.6f, 14.6f, 19f)
            close()
            moveTo(15.2f, 18.9f)
            curveTo(15.2f, 15.8f, 16.7f, 14.3f, 18.6f, 14.3f)
            curveTo(20.5f, 14.3f, 22.1f, 15.9f, 22.1f, 18.9f)
            close()
        }
    }
}

/**
 * A Compose-drawn lightning bolt marking the Quick actions section.
 *
 * The bundled Material icon set has no bolt glyph (and the extended icon set is not a
 * dependency), so the bolt is a small hand-built vector rather than an emoji or a
 * bitmap. `Icon`'s tint overrides the fill, so a single vector serves every theme.
 */
private val HomeQuickActionBolt: ImageVector by lazy {
    homeIcon("HomeQuickActionBolt") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 2f)
            lineTo(4f, 14f)
            lineTo(11f, 14f)
            lineTo(10f, 22f)
            lineTo(20f, 10f)
            lineTo(13f, 10f)
            close()
        }
    }
}

/**
 * The hero's protection illustration: a filled soft-blue shield with a stylized
 * smartphone on top carrying a lock.
 *
 * Drawn entirely with Compose primitives (no bitmap, no remote asset, no extra
 * dependency) from the theme palette, so it stays crisp at any size and correct in
 * both light and dark themes. It is intentionally large (the hero sizes it to
 * [uz.faceguard.app.core.theme.QalqonSizes.illustration], >= 100 dp) because it is the
 * hero's visual anchor, not a status icon. `contentDescription` is deliberately absent:
 * the hero's title already carries the meaning, so the illustration must not be
 * announced twice.
 */
@Composable
private fun QalqonProtectionMotif(accent: Color, modifier: Modifier = Modifier) {
    // Premium shield rendered in the current state's semantic accent (green when
    // protected, amber when off, red when blocking), with a lighter inner face for
    // 2.5D depth and a clean white/blue smartphone.
    val shieldFill = accent.copy(alpha = 0.18f)
    val shieldHighlight = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
    val stroke = accent
    val phoneFill = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // The shield outline, optionally scaled about its centre (the inner face).
            fun shieldAt(scale: Float): Path {
                fun x(f: Float) = w * 0.5f + (w * f - w * 0.5f) * scale
                fun y(f: Float) = h * 0.5f + (h * f - h * 0.5f) * scale
                return Path().apply {
                    moveTo(x(0.5f), y(0.02f))
                    lineTo(x(0.94f), y(0.20f))
                    lineTo(x(0.94f), y(0.52f))
                    quadraticTo(x(0.94f), y(0.86f), x(0.5f), y(0.99f))
                    quadraticTo(x(0.06f), y(0.86f), x(0.06f), y(0.52f))
                    lineTo(x(0.06f), y(0.20f))
                    close()
                }
            }
            // Solid soft-blue shield, then a lighter inner face for depth, so the emblem
            // reads as a filled 2.5D badge rather than transparent line-art.
            drawPath(shieldAt(1f), color = shieldFill)
            drawPath(shieldAt(0.80f), color = shieldHighlight)
            drawPath(shieldAt(1f), color = stroke, style = Stroke(width = h * 0.035f))

            // The stylized smartphone sitting on the shield.
            val phoneW = w * 0.48f
            val phoneH = h * 0.58f
            val phoneTop = h * 0.22f
            val phoneLeft = (w - phoneW) / 2f
            val phoneRadius = CornerRadius(phoneW * 0.20f)
            drawRoundRect(
                color = phoneFill,
                topLeft = Offset(phoneLeft, phoneTop),
                size = Size(phoneW, phoneH),
                cornerRadius = phoneRadius,
            )
            drawRoundRect(
                color = stroke,
                topLeft = Offset(phoneLeft, phoneTop),
                size = Size(phoneW, phoneH),
                cornerRadius = phoneRadius,
                style = Stroke(width = h * 0.03f),
            )
            // A speaker slot, so the rounded rectangle reads as a phone.
            drawRoundRect(
                color = stroke.copy(alpha = 0.7f),
                topLeft = Offset(w * 0.5f - phoneW * 0.18f, phoneTop + h * 0.05f),
                size = Size(phoneW * 0.36f, h * 0.022f),
                cornerRadius = CornerRadius(h * 0.011f),
            )
        }
        // The lock centred on the phone screen.
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = stroke,
            modifier = Modifier.size(QalqonDimens.icon.lg),
        )
    }
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

/**
 * A compact metric card for the Today grid.
 *
 * Reference treatment: a white surface with a hairline border and a soft lift, a
 * semantic icon inside a tonal circular container (light blue for screen time, soft
 * green for protection), the metric title as a secondary label, an optional supporting
 * caption and the real value as the prominent line in its semantic tone. When the tile
 * has a real destination it is clickable and shows a trailing chevron; with no
 * destination it stays static and draws no chevron (never a fake affordance). Nothing
 * truncates: the supporting sentence wraps.
 */
@Composable
private fun HomeMetricTile(
    metric: HomeTodayMetric,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    @androidx.annotation.StringRes inlineActionLabelRes: Int? = null,
    onInlineAction: (() -> Unit)? = null,
) {
    val accent = metric.tone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    // The value is the tile's headline: a duration or a status reads in a prominent
    // title style, never as plain secondary body copy.
    val (valueText, valueStyle) = when (val value = metric.value) {
        is HomeMetricValue.Duration ->
            durationLabel(value.ms) to MaterialTheme.typography.titleLarge
        is HomeMetricValue.Text ->
            (if (value.arg != null) stringResource(value.res, value.arg) else stringResource(value.res)) to
                MaterialTheme.typography.titleMedium
    }
    // Colour-coded circular icon container, so the Today grid reads at a glance.
    val (iconContainer, iconTint) = when (metric.kind) {
        HomeTodayMetricKind.SCREEN_TIME ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        else ->
            accent.copy(alpha = 0.14f) to accent
    }
    QalqonCard(
        modifier = modifier,
        bordered = true,
        containerColor = MaterialTheme.colorScheme.surface,
        elevation = QalqonDimens.elevation.raised,
        shape = QalqonShapes.xLargeShape,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(QalqonDimens.sizes.buttonDefault)
                    .background(color = iconContainer, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = homeMetricIcon(metric.kind),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(QalqonDimens.icon.sm),
                )
            }
            // The chevron is shown only when the tile really has a destination.
            if (onClick != null) {
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(QalqonDimens.icon.sm),
                )
            }
        }
        Text(
            text = stringResource(metric.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        metric.caption?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = valueText,
            style = valueStyle,
            color = accent,
            // The full sentence is allowed to wrap; nothing here truncates it.
        )
        // A real, tappable button for the tile's one inline action (e.g. select a child).
        if (inlineActionLabelRes != null && onInlineAction != null) {
            Button(
                onClick = onInlineAction,
                modifier = Modifier.heightIn(min = QalqonDimens.sizes.touchTarget),
            ) {
                Text(stringResource(inlineActionLabelRes))
            }
        }
    }
}

/**
 * The contextual quick actions, each routed through the existing navigation callbacks
 * (so the lock gate and protected-route rules are unchanged). Each action is its own
 * compact white card — a soft icon container, a title, a one-line description and a
 * chevron — so the group reads as a set of tappable commands rather than a settings list.
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
            leading = { HomeSectionIcon(HomeQuickActionBolt, MaterialTheme.colorScheme.primary) },
        )
        actions.forEach { action ->
            val onClick = when (action) {
                HomeQuickAction.MANAGE_CHILDREN -> onOpenChildren
                HomeQuickAction.PROTECTION_SETTINGS -> onOpenProtection
                HomeQuickAction.PROTECTED_APPS -> onOpenProtectedApps
                HomeQuickAction.REVIEW_REQUESTS -> onOpenRequests
            }
            val (iconContainer, iconTint) = quickActionIconColors(action)
            QalqonCard(
                modifier = Modifier.fillMaxWidth(),
                bordered = true,
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = QalqonDimens.elevation.raised,
                shape = QalqonShapes.largeShape,
                onClick = onClick,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(QalqonDimens.sizes.buttonDefault)
                            .background(color = iconContainer, shape = CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = homeQuickActionIcon(action),
                            contentDescription = null,
                            tint = iconTint,
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
                            style = MaterialTheme.typography.titleSmall,
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
            }
        }
    }
}

/**
 * Colour-coded circular icon container per quick action, so the group is scannable:
 * soft blue for children, soft purple for protected apps, the neutral secondary
 * container for the remaining commands. All three come from theme roles.
 */
@Composable
private fun quickActionIconColors(action: HomeQuickAction): Pair<Color, Color> = when (action) {
    HomeQuickAction.MANAGE_CHILDREN ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    HomeQuickAction.PROTECTED_APPS ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.tertiary
    HomeQuickAction.PROTECTION_SETTINGS,
    HomeQuickAction.REVIEW_REQUESTS,
    -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

/** The glyph for each quick action; decorative, the label carries the meaning. */
private fun homeQuickActionIcon(action: HomeQuickAction): ImageVector = when (action) {
    // Reference mockup: a group for children, a shield for protected apps.
    HomeQuickAction.MANAGE_CHILDREN -> HomeGroupIcon
    HomeQuickAction.PROTECTION_SETTINGS -> Icons.Filled.Lock
    HomeQuickAction.PROTECTED_APPS -> HomeShieldIcon
    HomeQuickAction.REVIEW_REQUESTS -> Icons.Filled.Notifications
}
