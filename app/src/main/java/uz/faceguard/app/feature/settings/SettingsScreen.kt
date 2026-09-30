package uz.faceguard.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.diagnostics.SystemHealthSnapshotSource
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.LanguageState
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.data.prefs.AppLanguageStore
import uz.faceguard.app.feature.language.LanguageOptions
import uz.faceguard.app.domain.diagnostics.DiagnosticStatus
import uz.faceguard.app.domain.diagnostics.SystemHealthLevel
import uz.faceguard.app.domain.diagnostics.SystemHealthReport
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.model.ScanMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ResetRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val protectedAppsRepository: ProtectedAppsRepository,
    private val parentProfileRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    private val resetRepository: ResetRepository,
    /** Phase 12: the Android reader behind the system-health audit. */
    private val healthSource: SystemHealthSnapshotSource,
    /** The single source of truth for the application language. */
    private val languageStore: AppLanguageStore,
    /** Process-scoped parent UI lock; cleared on logout and full reset. */
    private val appLockState: AppLockState,
) : ViewModel() {

    /** The currently selected language (null until one has been chosen). */
    val language: StateFlow<LanguageState> = languageStore.state

    /** Changing the language takes effect immediately and survives restarts. */
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { languageStore.setLanguage(language) }
    }

    /**
     * Null until the first persisted value is available, so the UI never shows
     * default-looking values as if they were the real (possibly non-default)
     * settings. A read failure flips [settingsLoadError] instead.
     */
    private val _settingsLoadError = MutableStateFlow(false)
    val settingsLoadError: StateFlow<Boolean> = _settingsLoadError

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .catch { _settingsLoadError.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val protectedApps: StateFlow<List<ProtectedApp>> = protectedAppsRepository.protectedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _appsRefreshing = MutableStateFlow(false)
    val appsRefreshing: StateFlow<Boolean> = _appsRefreshing

    private val _logoutState = MutableStateFlow<UiState>(UiState.Idle)
    val logoutState: StateFlow<UiState> = _logoutState

    fun setProtectionEnabled(payload: Boolean) =
        viewModelScope.launch { settingsRepository.setProtectionEnabled(payload) }

    fun setScanMode(payload: ScanMode) =
        viewModelScope.launch { settingsRepository.setScanMode(payload) }

    fun setRecoveryDelay(seconds: Int) =
        viewModelScope.launch { settingsRepository.setRecoveryDelayMs(seconds * 1000L) }

    fun setUnknownPolicy(payload: BlockPolicy) =
        viewModelScope.launch { settingsRepository.setUnknownUserPolicy(payload) }

    fun setNoFacePolicy(payload: BlockPolicy) =
        viewModelScope.launch { settingsRepository.setNoFacePolicy(payload) }

    fun setLowBatteryBehavior(payload: Boolean) =
        viewModelScope.launch { settingsRepository.setLowBatteryBehaviorEnabled(payload) }

    fun toggleProtectedApp(packageName: String, isProtected: Boolean) =
        viewModelScope.launch { protectedAppsRepository.toggleProtection(packageName, isProtected) }

    fun refreshProtectedApps() {
        if (_appsRefreshing.value) return
        viewModelScope.launch {
            _appsRefreshing.value = true
            try {
                protectedAppsRepository.refreshFromDevice()
            } finally {
                _appsRefreshing.value = false
            }
        }
    }

    private val _children = MutableStateFlow<List<ChildProfile>>(emptyList())
    val children: StateFlow<List<ChildProfile>> = _children

    private val _resetDone = MutableStateFlow(false)
    val resetDone: StateFlow<Boolean> = _resetDone

    /** Phase 12: null until the first audit has run. */
    private val _healthReport = MutableStateFlow<SystemHealthReport?>(null)
    val healthReport: StateFlow<SystemHealthReport?> = _healthReport

    private val _healthRunning = MutableStateFlow(false)
    val healthRunning: StateFlow<Boolean> = _healthRunning

    private val _healthError = MutableStateFlow(false)
    val healthError: StateFlow<Boolean> = _healthError

    /** Phase 12: reads the live signals and evaluates the health audit. */
    fun runDiagnostics() {
        if (_healthRunning.value) return
        viewModelScope.launch {
            _healthRunning.value = true
            _healthError.value = false
            try {
                _healthReport.value = healthSource.report()
            } catch (t: Throwable) {
                _healthError.value = true
            } finally {
                _healthRunning.value = false
            }
        }
    }

    init {
        refreshProtectedApps()
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount() ?: return@launch
            childRepository.observeChildren(account.id).collect { _children.value = it }
        }
    }

    fun deleteParentFace() = viewModelScope.launch {
        val account = accountRepository.getCurrentAccount() ?: return@launch
        parentProfileRepository.deleteFaceData(account.id)
    }

    fun deleteChildFace(childId: Long) = viewModelScope.launch {
        val account = accountRepository.getCurrentAccount() ?: return@launch
        childRepository.deleteFaceData(account.id, childId)
    }

    /** Full local wipe; caller navigates back to the welcome flow. */
    fun resetAll() = viewModelScope.launch {
        resetRepository.resetAll()
        // A wiped install must not leave the UI unlocked.
        appLockState.lock()
        _resetDone.value = true
    }

    fun logout() {
        if (_logoutState.value == UiState.Loading) return
        _logoutState.value = UiState.Loading
        viewModelScope.launch {
            accountRepository.logout()
            // Logging out ends the unlocked UI session too; the next launch asks for
            // the PIN (or shows Welcome, matching the existing sign-out flow).
            appLockState.lock()
            _logoutState.value = UiState.Success
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    initialTab: Int = 0,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsLoadError by viewModel.settingsLoadError.collectAsStateWithLifecycle()
    val protectedApps by viewModel.protectedApps.collectAsStateWithLifecycle()
    val appsRefreshing by viewModel.appsRefreshing.collectAsStateWithLifecycle()
    val logoutState by viewModel.logoutState.collectAsStateWithLifecycle()
    val children by viewModel.children.collectAsStateWithLifecycle()
    val resetDone by viewModel.resetDone.collectAsStateWithLifecycle()
    val healthReport by viewModel.healthReport.collectAsStateWithLifecycle()
    val healthRunning by viewModel.healthRunning.collectAsStateWithLifecycle()
    val healthError by viewModel.healthError.collectAsStateWithLifecycle()

    LaunchedEffect(logoutState) { if (logoutState is UiState.Success) onLoggedOut() }
    LaunchedEffect(resetDone) { if (resetDone) onLoggedOut() }

    SettingsContent(
        viewModel = viewModel,
        settings = settings,
        settingsLoadError = settingsLoadError,
        protectedApps = protectedApps,
        appsRefreshing = appsRefreshing,
        children = children,
        logoutState = logoutState,
        healthReport = healthReport,
        healthRunning = healthRunning,
        healthError = healthError,
        onBack = onBack,
        initialTab = initialTab,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel,
    settings: AppSettings?,
    settingsLoadError: Boolean,
    protectedApps: List<ProtectedApp>,
    appsRefreshing: Boolean,
    children: List<ChildProfile>,
    logoutState: UiState,
    healthReport: SystemHealthReport?,
    healthRunning: Boolean,
    healthError: Boolean,
    onBack: () -> Unit,
    initialTab: Int,
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.settings_tab_rules)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.settings_tab_apps)) },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.settings_tab_data)) },
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text(stringResource(R.string.settings_tab_health)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedTab) {
                0 -> RulesTab(viewModel, settings, settingsLoadError, logoutState)
                1 -> AppsTab(
                    apps = protectedApps,
                    isRefreshing = appsRefreshing,
                    onToggle = viewModel::toggleProtectedApp,
                    onRefresh = viewModel::refreshProtectedApps,
                )
                2 -> DataTab(
                    children = children,
                    onDeleteParentFace = viewModel::deleteParentFace,
                    onDeleteChildFace = viewModel::deleteChildFace,
                    onResetAll = viewModel::resetAll,
                )
                else -> HealthTab(
                    report = healthReport,
                    running = healthRunning,
                    error = healthError,
                    onRun = viewModel::runDiagnostics,
                )
            }
        }
    }
}

/**
 * Phase 12: the system-health audit tab.
 *
 * Nothing is evaluated while composing — the parent runs the check explicitly,
 * so opening Settings never reads portals or touches the service in the
 * background. Only the last report is rendered, and the raw machine-readable
 * reason is never shown as UI text.
 */
@Composable
private fun HealthTab(
    report: SystemHealthReport?,
    running: Boolean,
    error: Boolean,
    onRun: () -> Unit,
) {
    Text(stringResource(R.string.health_hint), style = MaterialTheme.typography.bodyMedium)

    OutlinedButton(onClick = onRun, enabled = !running, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (running) R.string.health_running else R.string.health_run))
    }

    if (report == null) {
        SectionCard(title = stringResource(R.string.health_section_title)) {
            Text(
                stringResource(if (error) R.string.health_error else R.string.health_not_run),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    SectionCard(title = stringResource(R.string.health_section_title)) {
        Text(
            stringResource(report.level.labelRes()),
            style = MaterialTheme.typography.titleMedium,
            color = healthColor(report.level),
        )
    }

    report.findings.forEach { finding ->
        SectionCard(title = stringResource(finding.check.labelRes())) {
            Text(
                stringResource(finding.status.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = healthColor(finding.status),
            )
        }
    }
}

private fun healthColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.OK -> Color(0xFF2E7D32)
    DiagnosticStatus.WARNING -> Color(0xFFF9A825)
    DiagnosticStatus.FAILED -> Color(0xFFC62828)
    DiagnosticStatus.UNKNOWN -> Color(0xFF757575)
}

private fun healthColor(level: SystemHealthLevel): Color = when (level) {
    SystemHealthLevel.HEALTHY -> Color(0xFF2E7D32)
    SystemHealthLevel.DEGRADED -> Color(0xFFF9A825)
    SystemHealthLevel.CRITICAL -> Color(0xFFC62828)
    SystemHealthLevel.UNKNOWN -> Color(0xFF757575)
}

@Composable
private fun RulesTab(
    viewModel: SettingsViewModel,
    settings: AppSettings?,
    settingsLoadError: Boolean,
    logoutState: UiState,
) {
    // Language is an app preference, available even while settings are loading, and
    // it goes through the same single store the first-launch picker uses.
    val languageState by viewModel.language.collectAsStateWithLifecycle()
    SectionCard(
        title = stringResource(R.string.language_section),
        subtitle = stringResource(R.string.language_section_hint),
    ) {
        LanguageOptions(
            selected = (languageState as? LanguageState.Selected)?.language,
            onSelect = viewModel::setLanguage,
        )
    }

    if (settings == null) {
        // Null means "not loaded yet" (or a read failure), never "defaults".
        SectionCard(title = stringResource(R.string.settings_title)) {
            Text(
                stringResource(
                    if (settingsLoadError) R.string.settings_load_error else R.string.state_loading,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (settingsLoadError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        AppLoadingButton(
            labelRes = R.string.settings_logout,
            loading = logoutState == UiState.Loading,
            onClick = viewModel::logout,
        )
        return
    }
    SectionCard(
        title = stringResource(R.string.settings_protection),
        subtitle = stringResource(R.string.settings_protection_hint),
    ) {
        SettingSwitchRow(
            label = stringResource(R.string.settings_protection_toggle),
            checked = settings.protectionEnabled,
            onChange = viewModel::setProtectionEnabled,
        )
    }
    SectionCard(
        title = stringResource(R.string.settings_scan_mode),
        subtitle = stringResource(R.string.settings_scan_mode_hint),
    ) {
        ChipRow(
            options = listOf(
                ScanMode.BALANCED to stringResource(R.string.scan_balanced),
                ScanMode.BATTERY_SAVER to stringResource(R.string.scan_battery_saver),
                ScanMode.STRICT to stringResource(R.string.scan_strict),
            ),
            selected = settings.scanMode,
            onSelect = viewModel::setScanMode,
        )
    }
    SectionCard(
        title = stringResource(R.string.settings_recovery_delay),
        subtitle = stringResource(R.string.settings_recovery_delay_hint),
    ) {
        Column {
            Slider(
                value = (settings.recoveryDelayMs / 1000f),
                onValueChange = { viewModel.setRecoveryDelay(it.roundToInt()) },
                valueRange = 5f..60f,
            )
            Text(
                stringResource(
                    R.string.settings_recovery_delay_value,
                    (settings.recoveryDelayMs / 1000L).toString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    SectionCard(
        title = stringResource(R.string.settings_unknown_policy),
        subtitle = stringResource(R.string.settings_unknown_policy_hint),
    ) {
        PolicyChips(
            selected = settings.unknownUserPolicy,
            onSelect = viewModel::setUnknownPolicy,
        )
    }
    SectionCard(
        title = stringResource(R.string.settings_no_face_policy),
        subtitle = stringResource(R.string.settings_no_face_policy_hint),
    ) {
        PolicyChips(
            selected = settings.noFacePolicy,
            onSelect = viewModel::setNoFacePolicy,
        )
    }
    SectionCard(
        title = stringResource(R.string.settings_low_battery),
        subtitle = stringResource(R.string.settings_low_battery_hint),
    ) {
        SettingSwitchRow(
            label = stringResource(R.string.settings_low_battery_toggle),
            checked = settings.lowBatteryBehaviorEnabled,
            onChange = viewModel::setLowBatteryBehavior,
        )
    }
    Spacer(Modifier.height(8.dp))
    AppLoadingButton(
        labelRes = R.string.settings_logout,
        loading = logoutState == UiState.Loading,
        onClick = viewModel::logout,
    )
}

@Composable
private fun AppsTab(
    apps: List<ProtectedApp>,
    isRefreshing: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val protectedCount = apps.count { it.isProtected }
    var query by remember { mutableStateOf("") }

    Text(stringResource(R.string.papps_subtitle), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.papps_selected_count, protectedCount),
        style = MaterialTheme.typography.bodyMedium,
        color = if (protectedCount > 0) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (isRefreshing) R.string.papps_refreshing else R.string.papps_refresh))
    }

    if (apps.isEmpty()) {
        SectionCard(title = stringResource(R.string.papps_title)) {
            Text(
                stringResource(if (isRefreshing) R.string.papps_loading else R.string.papps_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isRefreshing) {
                Text(
                    stringResource(R.string.papps_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    SectionCard(title = stringResource(R.string.papps_title)) {
        if (apps.size > SEARCH_THRESHOLD) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.papps_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val visible = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.appDisplayName.contains(query.trim(), ignoreCase = true) }
        }
        if (visible.isEmpty()) {
            Text(
                stringResource(R.string.papps_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visible.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(app.packageName, !app.isProtected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = app.isProtected,
                        onCheckedChange = { onToggle(app.packageName, it) },
                    )
                    Text(
                        app.appDisplayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (app.isProtected) {
                        Text(
                            stringResource(R.string.papps_marked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private const val SEARCH_THRESHOLD = 15

@Composable
private fun PolicyChips(selected: BlockPolicy, onSelect: (BlockPolicy) -> Unit) {
    ChipRow(
        options = listOf(
            BlockPolicy.ALLOW to stringResource(R.string.policy_allow),
            BlockPolicy.SOFT_BLOCK to stringResource(R.string.policy_soft_block),
            BlockPolicy.HARD_BLOCK to stringResource(R.string.policy_hard_block),
        ),
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}


/** Local data tools: per-subject face deletion + full reset with confirmation. */
@Composable
private fun DataTab(
    children: List<ChildProfile>,
    onDeleteParentFace: () -> Unit,
    onDeleteChildFace: (Long) -> Unit,
    onResetAll: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    SectionCard(stringResource(R.string.data_section_faces)) {
        OutlinedButton(onClick = onDeleteParentFace, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.data_delete_parent_face))
        }
        if (children.isEmpty()) {
            Text(stringResource(R.string.data_no_children), style = MaterialTheme.typography.bodySmall)
        } else {
            children.filter { it.isFaceEnrolled }.forEach { child ->
                OutlinedButton(
                    onClick = { onDeleteChildFace(child.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.data_delete_child_face, child.childName))
                }
            }
            if (children.none { it.isFaceEnrolled }) {
                Text(stringResource(R.string.data_no_faces), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    SectionCard(stringResource(R.string.data_section_reset)) {
        Text(stringResource(R.string.data_reset_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.data_reset_all), color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.data_reset_confirm_title)) },
            text = { Text(stringResource(R.string.data_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetAll()
                }) {
                    Text(stringResource(R.string.data_reset_confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.data_reset_confirm_no))
                }
            },
        )
    }
}
