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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
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
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (ui.state) {
                is UiState.Loading -> Text(stringResource(R.string.state_loading))
                is UiState.Error -> Text(
                    stringResource((ui.state as UiState.Error).messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    ui.account?.let { account ->
                        ProfileSummaryCard(
                            account = account,
                            protectionEnabled = settings.protectionEnabled,
                        )
                    }

                    SetupChecklistCard(ui, settings)

                    MainActionsCard(
                        onOpenParent = onOpenParent,
                        onOpenChildren = onOpenChildren,
                        onOpenProtectedApps = onOpenProtectedApps,
                        onOpenSettings = onOpenSettings,
                        protectedCount = ui.protectedCount,
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
                            onOpenProtection = onOpenProtection,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSummaryCard(
    account: UserAccount,
    protectionEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_profile_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.home_profile_name, account.fullName),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.home_profile_phone, account.phoneNumber),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.home_profile_protection,
                    stringResource(
                        if (protectionEnabled) R.string.home_status_on else R.string.home_status_off,
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MainActionsCard(
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

/** Seven-step readiness checklist with a simple progress indicator. */
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
    val completed = items.count { it.first }
    val total = items.size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.home_setup_progress, completed, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

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
    onOpenProtection: () -> Unit,
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
                OutlinedButton(onClick = onOpenProtection, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_protection_debug))
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
