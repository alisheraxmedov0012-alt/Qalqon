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

/** Navigation contract for the child app-policy route. */
object ChildPolicyArgs {
    const val CHILD_ID = "childId"
}

/** Daily-limit choices offered for [AppPolicyMode.LIMIT]. */
val LIMIT_OPTIONS_MINUTES = listOf(15, 30, 60, 120)

const val DEFAULT_LIMIT_MINUTES = 30

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildPolicyScreen(
    onBack: () -> Unit,
    onOpenChildren: () -> Unit,
    viewModel: ChildPolicyViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var dialogApp by remember { mutableStateOf<ProtectedApp?>(null) }

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
