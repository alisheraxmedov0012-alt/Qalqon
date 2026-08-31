package uz.faceguard.app.feature.home

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.debug.DebugFlags
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository

data class HomeUiState(
    val state: UiState = UiState.Idle,
    val account: UserAccount? = null,
    val parentProfile: ParentProfile? = null,
    val children: List<ChildProfile> = emptyList(),
    val protectedCount: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val parentProfileRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    private val settingsRepository: SettingsRepository,
    protectedAppsRepository: ProtectedAppsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState(state = UiState.Loading))
    val ui: StateFlow<HomeUiState> = _ui

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount()
            if (account == null) {
                _ui.update { it.copy(state = UiState.Error(R.string.error_invalid_credentials)) }
            } else {
                _ui.update { it.copy(account = account, state = UiState.Success) }
                launch {
                    childRepository.observeChildren(account.id).collect { children ->
                        _ui.update { it.copy(children = children) }
                    }
                }
                launch {
                    parentProfileRepository.observe(account.id).collect { profile ->
                        _ui.update { it.copy(parentProfile = profile) }
                    }
                }
                launch {
                    protectedAppsRepository.protectedApps.collect { apps ->
                        _ui.update { it.copy(protectedCount = apps.count { a -> a.isProtected }) }
                    }
                }
            }
        }
    }

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setProtectionEnabled(enabled) }
    }
}

@Composable
fun HomeScreen(
    onOpenParent: () -> Unit,
    onOpenChildren: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecognition: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenActivity: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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

            when (ui.state) {
                is UiState.Loading -> Text(stringResource(R.string.state_loading))
                is UiState.Error -> Text(
                    stringResource((ui.state as UiState.Error).messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    ui.account?.let { account ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(account.fullName, style = MaterialTheme.typography.titleMedium)
                                Text(account.phoneNumber, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    SetupChecklistCard(ui, settings)
                    ProtectionCard(settings, ui.protectedCount, viewModel::setProtectionEnabled)

                    Button(onClick = onOpenParent, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_menu_parent))
                    }
                    Button(onClick = onOpenChildren, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_menu_children))
                    }
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_menu_settings))
                    }
                    OutlinedButton(onClick = onOpenActivity, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_menu_activity))
                    }
                    OutlinedButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_menu_privacy))
                    }
                    OutlinedButton(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_menu_help))
                    }
                    if (DebugFlags.DEBUG_SCREENS_ENABLED) {
                        OutlinedButton(onClick = onOpenRecognition, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.home_recognition_debug))
                        }
                        OutlinedButton(onClick = onOpenProtection, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.home_protection_debug))
                        }
                        ForegroundDebugCard()
                    }
                }
            }
        }
    }
}

/** Seven-step readiness checklist; each row turns primary when done. */
@Composable
private fun SetupChecklistCard(ui: HomeUiState, settings: AppSettings) {
    val items = listOf(
        (ui.account != null) to R.string.setup_account,
        (ui.parentProfile != null) to R.string.setup_parent_profile,
        (ui.parentProfile?.isFaceEnrolled == true) to R.string.setup_parent_face,
        (ui.children.isNotEmpty()) to R.string.setup_child_added,
        (ui.children.any { it.isFaceEnrolled }) to R.string.setup_child_face,
        (ui.protectedCount > 0) to R.string.setup_protected_apps,
        settings.protectionEnabled to R.string.setup_protection_enabled,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            items.forEach { (done, labelRes) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(if (done) R.string.setup_done_mark else R.string.setup_todo_mark),
                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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

@Composable
private fun ProtectionCard(
    settings: AppSettings,
    protectedCount: Int,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_protection), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.protectionEnabled, onCheckedChange = onToggle)
                Text(
                    stringResource(
                        if (settings.protectionEnabled) R.string.home_status_on else R.string.home_status_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.home_scan_mode_label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (settings.scanMode) {
                        ScanMode.BALANCED -> R.string.scan_balanced
                        ScanMode.BATTERY_SAVER -> R.string.scan_battery_saver
                        ScanMode.STRICT -> R.string.scan_strict
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.home_protected_count, protectedCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.home_unknown_policy_label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (settings.unknownUserPolicy) {
                        BlockPolicy.ALLOW -> R.string.policy_allow
                        BlockPolicy.SOFT_BLOCK -> R.string.policy_soft_block
                        BlockPolicy.HARD_BLOCK -> R.string.policy_hard_block
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ForegroundDebugCard() {
    val context = LocalContext.current
    val monitor = remember { ForegroundAppMonitor(context) }
    var hasAccess by remember { mutableStateOf(monitor.hasUsageAccess()) }
    val foreground by monitor.current.collectAsStateWithLifecycle()

    // scoped to the composable; cancelled automatically on dispose
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
