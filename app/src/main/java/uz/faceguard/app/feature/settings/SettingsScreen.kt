package uz.faceguard.app.feature.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.UiState
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
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val protectedApps: StateFlow<List<ProtectedApp>> = protectedAppsRepository.protectedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun refreshProtectedApps() = viewModelScope.launch { protectedAppsRepository.refreshFromDevice() }

    private val _children = MutableStateFlow<List<ChildProfile>>(emptyList())
    val children: StateFlow<List<ChildProfile>> = _children

    private val _resetDone = MutableStateFlow(false)
    val resetDone: StateFlow<Boolean> = _resetDone

    init {
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
        _resetDone.value = true
    }

    fun logout() {
        if (_logoutState.value == UiState.Loading) return
        _logoutState.value = UiState.Loading
        viewModelScope.launch {
            accountRepository.logout()
            _logoutState.value = UiState.Success
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val protectedApps by viewModel.protectedApps.collectAsStateWithLifecycle()
    val logoutState by viewModel.logoutState.collectAsStateWithLifecycle()
    val children by viewModel.children.collectAsStateWithLifecycle()
    val resetDone by viewModel.resetDone.collectAsStateWithLifecycle()

    LaunchedEffect(logoutState) { if (logoutState is UiState.Success) onLoggedOut() }
    LaunchedEffect(resetDone) { if (resetDone) onLoggedOut() }

    SettingsContent(
        viewModel = viewModel,
        settings = settings,
        protectedApps = protectedApps,
        children = children,
        logoutState = logoutState,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel,
    settings: AppSettings,
    protectedApps: List<ProtectedApp>,
    children: List<ChildProfile>,
    logoutState: UiState,
    onBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

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
                0 -> RulesTab(viewModel, settings, logoutState)
                1 -> AppsTab(
                    apps = protectedApps,
                    onToggle = viewModel::toggleProtectedApp,
                    onRefresh = viewModel::refreshProtectedApps,
                )
                else -> DataTab(
                    children = children,
                    onDeleteParentFace = viewModel::deleteParentFace,
                    onDeleteChildFace = viewModel::deleteChildFace,
                    onResetAll = viewModel::resetAll,
                )
            }
        }
    }
}

@Composable
private fun RulesTab(
    viewModel: SettingsViewModel,
    settings: AppSettings,
    logoutState: UiState,
) {
    SettingSection(stringResource(R.string.settings_protection)) {
        SettingSwitchRow(
            label = stringResource(R.string.settings_protection_toggle),
            checked = settings.protectionEnabled,
            onChange = viewModel::setProtectionEnabled,
        )
    }
    SettingSection(stringResource(R.string.settings_scan_mode)) {
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
    SettingSection(stringResource(R.string.settings_recovery_delay)) {
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
    SettingSection(stringResource(R.string.settings_unknown_policy)) {
        PolicyChips(
            selected = settings.unknownUserPolicy,
            onSelect = viewModel::setUnknownPolicy,
        )
    }
    SettingSection(stringResource(R.string.settings_no_face_policy)) {
        PolicyChips(
            selected = settings.noFacePolicy,
            onSelect = viewModel::setNoFacePolicy,
        )
    }
    SettingSection(stringResource(R.string.settings_low_battery)) {
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
    onToggle: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Text(stringResource(R.string.papps_subtitle), style = MaterialTheme.typography.bodyMedium)
    androidx.compose.material3.OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.papps_refresh))
    }
    if (apps.isEmpty()) {
        SettingSection(stringResource(R.string.papps_title)) {
            Text(stringResource(R.string.papps_empty))
        }
        return
    }
    apps.forEach { app ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = app.isProtected,
                onCheckedChange = { onToggle(app.packageName, it) },
            )
            Column {
                Text(app.appDisplayName, style = MaterialTheme.typography.bodyLarge)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

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
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
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

    SettingSection(stringResource(R.string.data_section_faces)) {
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
    SettingSection(stringResource(R.string.data_section_reset)) {
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
