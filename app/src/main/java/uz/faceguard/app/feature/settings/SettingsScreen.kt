package uz.faceguard.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.debug.DebugFlags
import uz.faceguard.app.core.diagnostics.SystemHealthSnapshotSource
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.LanguageState
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.core.ui.qalqon.QalqonSettingRow
import uz.faceguard.app.core.ui.qalqon.toneColor
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.data.prefs.AppLanguageStore
import uz.faceguard.app.domain.diagnostics.SystemHealthReport
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.ResetRepository
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

/**
 * UI/UX redesign, Phase 6: the Settings top-level destination.
 *
 * A short, scannable list of categories rather than one long settings form. Each row
 * opens the page that owns its settings, so "which setting is where?" is answered by
 * the hub itself. The developer category is present only in a debug build.
 *
 * Settings is a top-level bottom-navigation tab, so it has no back arrow; the screen
 * uses the existing [SettingsStatusViewModel] for the identity block only — the
 * settings themselves remain owned by the existing stores and the existing repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenCategory: (SettingsCategory) -> Unit,
    viewModel: SettingsStatusViewModel = hiltViewModel(),
) {
    val parentName by viewModel.parentName.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshNotifications() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = QalqonDimens.screenPadding,
                end = QalqonDimens.screenPadding,
                top = QalqonDimens.spacing.lg,
                bottom = QalqonDimens.spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
        ) {
            if (parentName != null) {
                item { SettingsIdentityBlock(parentName!!) }
                item { QalqonSectionHeader(title = stringResource(R.string.settings_title)) }
            }
            items(
                items = settingsCategories(DebugFlags.DEBUG_SCREENS_ENABLED),
                key = { it.name },
            ) { category ->
                SettingsCategoryRow(
                    category = category,
                    onClick = { onOpenCategory(category) },
                )
            }
        }
    }
}

/** Compact, non-editable identity block: the parent's own name, no ids or secrets. */
@Composable
private fun SettingsIdentityBlock(parentName: String) {
    QalqonCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = parentName, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    QalqonCard(modifier = Modifier.fillMaxWidth(), contentPadding = QalqonDimens.spacing.xs) {
        QalqonSettingRow(
            title = stringResource(category.labelRes),
            description = stringResource(category.descriptionRes),
            leading = {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = toneColor(QalqonStatusTone.INACTIVE),
                )
            },
            trailing = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = onClick,
        )
    }
}
