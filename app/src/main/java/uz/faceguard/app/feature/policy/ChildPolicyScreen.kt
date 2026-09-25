package uz.faceguard.app.feature.policy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeLimits

/** Navigation contract for the child app-policy route. */
object ChildPolicyArgs {
    const val CHILD_ID = "childId"
}

/** Daily-limit choices offered for [AppPolicyMode.LIMIT]. */
val LIMIT_OPTIONS_MINUTES = listOf(15, 30, 60, 120)

const val DEFAULT_LIMIT_MINUTES = 30

/** Longest limit the input accepts (4 digits covers the 1440-minute maximum). */
private const val MAX_LIMIT_DIGITS = 4

private const val SEARCH_THRESHOLD = 15

/**
 * How an app looks to the parent for the selected child.
 *
 * [DEFAULT_ALLOW] / [DEFAULT_BLOCK] mean no explicit per-child policy exists and
 * the evaluator's inherited rule applies (global protected catalog or allow) —
 * this is display state only; the decision itself stays in `PolicyEvaluator`.
 */
enum class PolicyDisplay {
    EXPLICIT_ALLOW,
    EXPLICIT_LIMIT,
    EXPLICIT_BLOCK,
    DEFAULT_ALLOW,
    DEFAULT_BLOCK,
}

/** Pure mapping used by the screen; kept here so it is JVM-testable. */
fun displayFor(policy: AppPolicy?, isGlobalProtected: Boolean): PolicyDisplay = when {
    policy == null -> if (isGlobalProtected) PolicyDisplay.DEFAULT_BLOCK else PolicyDisplay.DEFAULT_ALLOW
    policy.mode == AppPolicyMode.ALLOW -> PolicyDisplay.EXPLICIT_ALLOW
    policy.mode == AppPolicyMode.LIMIT -> PolicyDisplay.EXPLICIT_LIMIT
    else -> PolicyDisplay.EXPLICIT_BLOCK
}

/** Fixed action per mode: the UI never invents a protection action. */
internal fun AppPolicyMode.defaultAction(): ProtectionAction = when (this) {
    AppPolicyMode.ALLOW -> ProtectionAction.ALLOW
    AppPolicyMode.LIMIT -> ProtectionAction.SOFT_BLOCK
    AppPolicyMode.BLOCK -> ProtectionAction.HARD_BLOCK
}

/** Which screen-time limit a save/remove is currently acting on. */
sealed interface ScreenTimeLimitScopeTarget {
    data object Total : ScreenTimeLimitScopeTarget
    data class Category(val category: AppCategory) : ScreenTimeLimitScopeTarget
}

/**
 * Phase 4 Step 1D: the selected child's TOTAL and CATEGORY screen-time limits.
 *
 * `null` values are meaningful and are the whole point of the section: they mean *no limit
 * configured*, which the evaluator reports as unlimited. They are never rendered as `0`,
 * because `0` is a real configuration meaning "immediately exceeded" — the two must stay
 * distinguishable everywhere.
 *
 * Per-app limits are deliberately absent: they belong to the app policies below, so there is
 * one place to edit each kind of limit.
 */
data class ScreenTimeLimitsUiState(
    val loading: Boolean = false,
    /** The child's total daily limit in minutes, or `null` when none is configured. */
    val totalMinutes: Int? = null,
    /** Configured category limits, keyed by category; a missing key means "no limit". */
    val categoryMinutes: Map<AppCategory, Int?> = emptyMap(),
    val savingTarget: ScreenTimeLimitScopeTarget? = null,
    val errorMessageRes: Int? = null,
) {
    /** Every category the product supports, so each one can be configured. */
    val categories: List<AppCategory> get() = AppCategory.entries.toList()

    fun categoryLimit(category: AppCategory): Int? = categoryMinutes[category]
}

data class ChildPolicyUiState(
    val state: UiState = UiState.Loading,
    val children: List<ChildProfile> = emptyList(),
    val selectedChildId: Long? = null,
    val apps: List<ProtectedApp> = emptyList(),
    val policies: Map<String, AppPolicy> = emptyMap(),
    val globalProtected: Set<String> = emptySet(),
    val refreshing: Boolean = false,
) {
    val selectedChild: ChildProfile? get() = children.firstOrNull { it.id == selectedChildId }

    fun policyFor(packageName: String): AppPolicy? = policies[packageName]

    fun displayFor(packageName: String): PolicyDisplay =
        displayFor(policies[packageName], packageName in globalProtected)
}

@HiltViewModel
class ChildPolicyViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val childRepository: ChildProfileRepository,
    private val childAppPolicyRepository: ChildAppPolicyRepository,
    private val protectedAppsRepository: ProtectedAppsRepository,
    /** Phase 4 Step 1D: TOTAL/CATEGORY limits — the same store the evaluator reads. */
    private val screenTimeLimitRepository: ScreenTimeLimitRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialChildId: Long = savedStateHandle.get<Long>(ChildPolicyArgs.CHILD_ID) ?: -1L

    private val _ui = MutableStateFlow(ChildPolicyUiState())
    val ui: StateFlow<ChildPolicyUiState> = _ui

    private var accountId: Long? = null
    private var observedChildId: Long? = null
    private var policyJob: Job? = null

    init {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount()
            if (account == null) {
                _ui.update { it.copy(state = UiState.Error(R.string.error_invalid_credentials)) }
                return@launch
            }
            accountId = account.id
            _ui.update { it.copy(state = UiState.Success) }

            launch {
                childRepository.observeChildren(account.id).collect { children ->
                    _ui.update { it.copy(children = children) }
                    selectChild(resolveChildId(children))
                }
            }
            launch {
                protectedAppsRepository.protectedApps.collect { apps ->
                    _ui.update {
                        it.copy(
                            apps = apps,
                            globalProtected = apps
                                .filter { app -> app.isProtected }
                                .map { app -> app.packageName }
                                .toSet(),
                        )
                    }
                }
            }
            refreshApps()
        }
    }

    /** Keeps the current selection when possible, else falls back to the nav child. */
    private fun resolveChildId(children: List<ChildProfile>): Long? {
        val current = observedChildId
        return when {
            current != null && children.any { it.id == current } -> current
            children.any { it.id == initialChildId } -> initialChildId
            else -> children.firstOrNull()?.id
        }
    }

    fun selectChild(childId: Long?) {
        if (childId == null) {
            policyJob?.cancel()
            policyJob = null
            observedChildId = null
            observeScreenTimeLimits(null)
            _ui.update { it.copy(selectedChildId = null, policies = emptyMap()) }
            return
        }
        if (childId == observedChildId) {
            _ui.update { it.copy(selectedChildId = childId) }
            return
        }
        observedChildId = childId
        policyJob?.cancel()
        _ui.update { it.copy(selectedChildId = childId, policies = emptyMap()) }
        // The screen-time limits follow the same selection, so they can never be shown or
        // edited for a child other than the one on screen.
        observeScreenTimeLimits(childId)

        val account = accountId ?: return
        policyJob = viewModelScope.launch {
            childAppPolicyRepository.observePolicies(account, childId).collect { list ->
                _ui.update { it.copy(policies = list.associateBy { policy -> policy.packageName }) }
            }
        }
    }

    fun refreshApps() {
        if (_ui.value.refreshing) return
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            try {
                protectedAppsRepository.refreshFromDevice()
            } finally {
                _ui.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Creates/updates the explicit per-child policy for [packageName]. */
    fun setPolicy(
        packageName: String,
        mode: AppPolicyMode,
        dailyLimitMinutes: Int? = null,
    ) {
        val account = accountId ?: return
        val childId = _ui.value.selectedChildId ?: return
        viewModelScope.launch {
            childAppPolicyRepository.upsert(
                accountId = account,
                childId = childId,
                policy = AppPolicy(
                    packageName = packageName,
                    mode = mode,
                    action = mode.defaultAction(),
                    dailyLimitMinutes = if (mode == AppPolicyMode.LIMIT) {
                        dailyLimitMinutes ?: DEFAULT_LIMIT_MINUTES
                    } else {
                        null
                    },
                    childId = childId,
                ),
            )
        }
    }

    /** Drops the explicit override so the inherited/default rule applies again. */
    fun resetPolicy(packageName: String) {
        val account = accountId ?: return
        val childId = _ui.value.selectedChildId ?: return
        viewModelScope.launch { childAppPolicyRepository.delete(account, childId, packageName) }
    }

    // ------------------------------------------------- Phase 4 screen-time limits

    private var limitJob: Job? = null

    /** The selected child's TOTAL/CATEGORY limits, observed so a save is reflected at once. */
    private val _screenTimeLimits = MutableStateFlow(ScreenTimeLimitsUiState())

    val screenTimeLimits: StateFlow<ScreenTimeLimitsUiState> = _screenTimeLimits

    /**
     * Observes the limits of whichever child is selected, so switching children loads that
     * child's configuration and nothing leaks between them.
     */
    private fun observeScreenTimeLimits(childId: Long?) {
        limitJob?.cancel()
        limitJob = null
        if (childId == null) {
            _screenTimeLimits.value = ScreenTimeLimitsUiState()
            return
        }
        val account = accountId ?: return
        _screenTimeLimits.value = ScreenTimeLimitsUiState(loading = true)
        limitJob = viewModelScope.launch {
            screenTimeLimitRepository.observeLimits(account, childId).collect { limits ->
                _screenTimeLimits.value = ScreenTimeLimitsUiState(
                    loading = false,
                    totalMinutes = limits.firstOrNull { it.scope == LimitScope.TOTAL }?.limitMinutes,
                    categoryMinutes = limits
                        .filter { it.scope == LimitScope.CATEGORY && it.category != null }
                        .associate { it.category!! to it.limitMinutes },
                )
            }
        }
    }

    /**
     * Saves the child's total daily limit.
     *
     * A value the domain does not accept is rejected before anything is written, and the
     * rejection is reported rather than silently ignored — the previously saved value stays
     * in place.
     */
    fun setTotalLimit(minutes: Int) {
        val account = accountId ?: return
        val childId = _ui.value.selectedChildId ?: return
        saveLimit(ScreenTimeLimitScopeTarget.Total) {
            screenTimeLimitRepository.upsert(account, childId, LimitScope.TOTAL, null, minutes)
        }
    }

    /** Removes the total daily limit so the scope evaluates as unlimited again. */
    fun removeTotalLimit() {
        val account = accountId ?: return
        val childId = _ui.value.selectedChildId ?: return
        saveLimit(ScreenTimeLimitScopeTarget.Total, remove = true) {
            screenTimeLimitRepository.delete(account, childId, LimitScope.TOTAL, null)
        }
    }

    fun setCategoryLimit(category: AppCategory, minutes: Int) {
        val account = accountId ?: return
        val childId = _ui.value.selectedChildId ?: return
        saveLimit(ScreenTimeLimitScopeTarget.Category(category)) {
            screenTimeLimitRepository.upsert(account, childId, LimitScope.CATEGORY, category, minutes)
        }
    }

    /** Removes one category's limit; every other category and the total are untouched. */
    fun removeCategoryLimit(category: AppCategory) {
        val account = accountId ?: return
        val childId = _ui.value.selectedChildId ?: return
        saveLimit(ScreenTimeLimitScopeTarget.Category(category), remove = true) {
            screenTimeLimitRepository.delete(account, childId, LimitScope.CATEGORY, category)
        }
    }

    /**
     * Runs one limit write and reports its outcome on the section's state.
     *
     * Validation lives in the repository, so this catches its rejection instead of
     * duplicating the rule; a failure never reports success and never changes the saved
     * value.
     */
    private fun saveLimit(target: ScreenTimeLimitScopeTarget, remove: Boolean = false, write: suspend () -> Unit) {
        viewModelScope.launch {
            _screenTimeLimits.update { it.copy(savingTarget = target, errorMessageRes = null) }
            runCatching { write() }.onFailure {
                _screenTimeLimits.update {
                    it.copy(errorMessageRes = if (remove) R.string.screentime_limit_error_remove else R.string.screentime_limit_error_save)
                }
            }
            _screenTimeLimits.update { it.copy(savingTarget = null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildPolicyScreen(
    onBack: () -> Unit,
    onOpenChildren: () -> Unit,
    viewModel: ChildPolicyViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val limits by viewModel.screenTimeLimits.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var dialogApp by remember { mutableStateOf<ProtectedApp?>(null) }
    // Which screen-time limit is being edited: null, or the scope the dialog is open for.
    var limitDialog by remember { mutableStateOf<ScreenTimeLimitScopeTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.child_policy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (val state = ui.state) {
            is UiState.Loading, is UiState.Idle -> StatusText(padding) {
                Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Error -> StatusText(padding) {
                Text(
                    stringResource(state.messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is UiState.Success -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.child_policy_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.children.isEmpty()) {
                    item { NoChildrenCard(onOpenChildren) }
                } else {
                    item { ChildSelector(ui, viewModel::selectChild) }
                    item {
                        ScreenTimeLimitsCard(
                            limits = limits,
                            childName = ui.selectedChild?.childName,
                            onSaveTotal = viewModel::setTotalLimit,
                            onRemoveTotal = viewModel::removeTotalLimit,
                            onSaveCategory = viewModel::setCategoryLimit,
                            onRemoveCategory = viewModel::removeCategoryLimit,
                        )
                    }
                    item { AppsHeader(refreshing = ui.refreshing, onRefresh = viewModel::refreshApps) }

                    val visible = if (query.isBlank()) {
                        ui.apps
                    } else {
                        ui.apps.filter { it.appDisplayName.contains(query.trim(), ignoreCase = true) }
                    }
                    if (ui.apps.size > SEARCH_THRESHOLD) {
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(stringResource(R.string.papps_search_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        item { EmptyAppsCard(refreshing = ui.refreshing) }
                    } else {
                        items(visible, key = { it.packageName }) { app ->
                            AppPolicyRow(
                                app = app,
                                display = ui.displayFor(app.packageName),
                                policy = ui.policyFor(app.packageName),
                                onOpen = { dialogApp = app },
                            )
                        }
                    }
                }
            }
        }
    }

    dialogApp?.let { app ->
        val policy = ui.policyFor(app.packageName)
        PolicyDialog(
            appName = app.appDisplayName,
            policy = policy,
            isGlobalProtected = app.packageName in ui.globalProtected,
            onDismiss = { dialogApp = null },
            onSave = { mode, limit ->
                viewModel.setPolicy(app.packageName, mode, limit)
                dialogApp = null
            },
            onReset = {
                viewModel.resetPolicy(app.packageName)
                dialogApp = null
            },
        )
    }
}

@Composable
private fun StatusText(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        content()
    }
}

@Composable
private fun ChildSelector(
    ui: ChildPolicyUiState,
    onSelect: (Long) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.child_policy_choose_child),
        subtitle = ui.selectedChild?.let { stringResource(R.string.child_policy_selected, it.childName) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.children.forEach { child ->
                FilterChip(
                    selected = child.id == ui.selectedChildId,
                    onClick = { onSelect(child.id) },
                    label = { Text(child.childName) },
                )
            }
        }
    }
}

@Composable
private fun AppsHeader(refreshing: Boolean, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.child_policy_apps_title),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (refreshing) R.string.papps_refreshing else R.string.papps_refresh))
        }
    }
}

@Composable
private fun EmptyAppsCard(refreshing: Boolean) {
    SectionCard(title = stringResource(R.string.child_policy_apps_title)) {
        Text(
            stringResource(if (refreshing) R.string.papps_loading else R.string.child_policy_empty_apps),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!refreshing) {
            Text(
                stringResource(R.string.child_policy_empty_apps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoChildrenCard(onOpenChildren: () -> Unit) {
    SectionCard(title = stringResource(R.string.child_policy_no_children)) {
        Text(
            stringResource(R.string.child_policy_no_children_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onOpenChildren, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_menu_children))
        }
    }
}

@Composable
private fun AppPolicyRow(
    app: ProtectedApp,
    display: PolicyDisplay,
    policy: AppPolicy?,
    onOpen: () -> Unit,
) {
    SectionCard(title = app.appDisplayName) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayLabel(display, policy),
                style = MaterialTheme.typography.bodyMedium,
                color = displayColor(display),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.child_policy_open))
            }
        }
    }
}
/**
 * Phase 4 Step 1D: the selected child's daily screen-time limits.
 *
 * Two kinds are configurable here, and the distinction is spelled out so a parent is never
 * guessing: the total for the whole day, and a limit per app category. Per-app limits are
 * *not* here — they belong to each app's own ALLOW/LIMIT/BLOCK policy below, so there is one
 * place to edit each kind of limit.
 *
 * This is manual configuration, not recognition: the child is whichever profile is selected
 * above, and the wording says the parent is setting limits for them. "No limit" and
 * "0 minutes" are shown differently on purpose.
 */
@Composable
private fun ScreenTimeLimitsCard(
    limits: ScreenTimeLimitsUiState,
    childName: String?,
    onSaveTotal: (Int) -> Unit,
    onRemoveTotal: () -> Unit,
    onSaveCategory: (AppCategory, Int) -> Unit,
    onRemoveCategory: (AppCategory) -> Unit,
) {
    var editing by remember { mutableStateOf<ScreenTimeLimitScopeTarget?>(null) }

    SectionCard(
        title = stringResource(R.string.screentime_limits_title),
        subtitle = childName?.let { stringResource(R.string.screentime_limits_subtitle, it) },
    ) {
        if (limits.loading) {
            Text(
                stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        LimitRow(
            name = stringResource(R.string.screentime_limits_total_label),
            minutes = limits.totalMinutes,
            enabled = limits.savingTarget == null,
            onEdit = { editing = ScreenTimeLimitScopeTarget.Total },
            onRemove = onRemoveTotal,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.screentime_limits_category_header),
            style = MaterialTheme.typography.bodyMedium,
        )
        limits.categories.forEach { category ->
            LimitRow(
                name = categoryLabel(category),
                minutes = limits.categoryLimit(category),
                enabled = limits.savingTarget == null,
                onEdit = { editing = ScreenTimeLimitScopeTarget.Category(category) },
                onRemove = { onRemoveCategory(category) },
            )
        }

        limits.errorMessageRes?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    editing?.let { target ->
        TextLimitDialog(
            title = when (target) {
                ScreenTimeLimitScopeTarget.Total -> stringResource(R.string.screentime_limits_total_label)
                is ScreenTimeLimitScopeTarget.Category -> categoryLabel(target.category)
            },
            current = when (target) {
                ScreenTimeLimitScopeTarget.Total -> limits.totalMinutes
                is ScreenTimeLimitScopeTarget.Category -> limits.categoryLimit(target.category)
            },
            onDismiss = { editing = null },
            onSave = { minutes ->
                when (target) {
                    ScreenTimeLimitScopeTarget.Total -> onSaveTotal(minutes)
                    is ScreenTimeLimitScopeTarget.Category -> onSaveCategory(target.category, minutes)
                }
                editing = null
            },
        )
    }
}

/** One configurable limit: its name, its saved value, and how to change or remove it. */
@Composable
private fun LimitRow(
    name: String,
    minutes: Int?,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                // "No limit" is the absence of a configured limit, never a stored 0.
                text = if (minutes == null) {
                    stringResource(R.string.screentime_limits_no_limit)
                } else {
                    stringResource(R.string.screentime_limits_minutes_value, minutes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit, enabled = enabled) {
            Text(
                stringResource(
                    if (minutes == null) R.string.screentime_limits_set else R.string.screentime_limits_edit,
                ),
            )
        }
        if (minutes != null) {
            TextButton(onClick = onRemove, enabled = enabled) {
                Text(stringResource(R.string.screentime_limits_remove))
            }
        }
    }
}

/**
 * Numeric input for one limit.
 *
 * Digits only, so malformed text and negatives cannot be entered, and the accepted range is
 * enforced before saving so the parent is told rather than silently ignored. `0` is accepted
 * and is explained in the note, because it means "already used up" rather than "no limit".
 */
@Composable
private fun TextLimitDialog(
    title: String,
    current: Int?,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var text by remember(current) {
        mutableStateOf(current?.toString() ?: DEFAULT_LIMIT_MINUTES.toString())
    }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && ScreenTimeLimits.isAccepted(parsed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screentime_limits_dialog_title, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    // Keep only digits: no sign, no letters, no separators.
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(MAX_LIMIT_DIGITS) },
                    label = { Text(stringResource(R.string.screentime_limits_input_label)) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIMIT_OPTIONS_MINUTES.forEach { minutes ->
                        FilterChip(
                            selected = parsed == minutes,
                            onClick = { text = minutes.toString() },
                            label = { Text(stringResource(R.string.child_policy_minutes, minutes)) },
                        )
                    }
                }
                if (!valid) {
                    Text(
                        stringResource(R.string.screentime_limits_input_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (parsed == 0) {
                    Text(
                        stringResource(R.string.screentime_limits_zero_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.screentime_limits_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = valid) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

/** Localized name of an existing category; the enum name stays the canonical identifier. */
@Composable
private fun categoryLabel(category: AppCategory): String = stringResource(
    when (category) {
        AppCategory.VIDEO -> R.string.app_category_video
        AppCategory.GAMES -> R.string.app_category_games
        AppCategory.EDUCATION -> R.string.app_category_education
        AppCategory.SOCIAL -> R.string.app_category_social
        AppCategory.OTHER -> R.string.app_category_other
    },
)

@Composable
private fun displayLabel(display: PolicyDisplay, policy: AppPolicy?): String = when (display) {
    PolicyDisplay.EXPLICIT_ALLOW -> stringResource(R.string.child_policy_state_explicit_allow)
    PolicyDisplay.EXPLICIT_LIMIT -> stringResource(
        R.string.child_policy_state_explicit_limit,
        policy?.dailyLimitMinutes ?: DEFAULT_LIMIT_MINUTES,
    )
    PolicyDisplay.EXPLICIT_BLOCK -> stringResource(R.string.child_policy_state_explicit_block)
    PolicyDisplay.DEFAULT_ALLOW -> stringResource(R.string.child_policy_state_default_allow)
    PolicyDisplay.DEFAULT_BLOCK -> stringResource(R.string.child_policy_state_default_block)
}

@Composable
private fun displayColor(display: PolicyDisplay) = when (display) {
    PolicyDisplay.EXPLICIT_BLOCK, PolicyDisplay.DEFAULT_BLOCK -> MaterialTheme.colorScheme.error
    PolicyDisplay.EXPLICIT_LIMIT -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyDialog(
    appName: String,
    policy: AppPolicy?,
    isGlobalProtected: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppPolicyMode, Int) -> Unit,
    onReset: () -> Unit,
) {
    var mode by remember(policy, appName) {
        mutableStateOf(policy?.mode ?: if (isGlobalProtected) AppPolicyMode.BLOCK else AppPolicyMode.ALLOW)
    }
    var limitMinutes by remember(policy, appName) {
        mutableIntStateOf(policy?.dailyLimitMinutes ?: DEFAULT_LIMIT_MINUTES)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.child_policy_dialog_title, appName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.child_policy_dialog_current,
                        displayLabel(displayFor(policy, isGlobalProtected), policy),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ModeOption(AppPolicyMode.ALLOW, mode, R.string.child_policy_mode_allow) { mode = it }
                ModeOption(AppPolicyMode.LIMIT, mode, R.string.child_policy_mode_limit) { mode = it }
                ModeOption(AppPolicyMode.BLOCK, mode, R.string.child_policy_mode_block) { mode = it }

                if (mode == AppPolicyMode.LIMIT) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.child_policy_limit_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LIMIT_OPTIONS_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = limitMinutes == minutes,
                                onClick = { limitMinutes = minutes },
                                label = { Text(stringResource(R.string.child_policy_minutes, minutes)) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.child_policy_limit_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(mode, limitMinutes) }) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            Row {
                if (policy != null) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.child_policy_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        },
    )
}

@Composable
private fun ModeOption(
    option: AppPolicyMode,
    selected: AppPolicyMode,
    labelRes: Int,
    onSelect: (AppPolicyMode) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == option, onClick = { onSelect(option) })
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}
