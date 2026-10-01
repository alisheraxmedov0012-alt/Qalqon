package uz.faceguard.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import uz.faceguard.app.R
import uz.faceguard.app.core.i18n.LanguageState
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonTheme
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.core.ui.qalqon.QalqonSettingRow
import uz.faceguard.app.core.ui.qalqon.QalqonStatusCard
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.core.ui.qalqon.toneColor
import uz.faceguard.app.domain.diagnostics.DiagnosticStatus
import uz.faceguard.app.domain.diagnostics.SystemHealthLevel
import uz.faceguard.app.domain.diagnostics.SystemHealthReport
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.feature.language.LanguageOptions

/**
 * UI/UX redesign, Phase 6: the Settings category pages.
 *
 * Each page groups the settings that belong to one functional area and is reached from
 * the Settings hub. Every page reuses the **existing** [SettingsViewModel] (or the
 * read-only [SettingsStatusViewModel]) and the existing components/stores — no setting
 * is duplicated, and nothing here invents a control the product does not already have.
 */

/** The shared back-arrow page scaffold for a Settings category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun CategoryColumn(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(QalqonDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.lg),
        content = { content() },
    )
}

// ------------------------------------------------------------------ Protection

/**
 * Protection: the global protection configuration (toggle, checking mode, release
 * delay, unknown/no-face policy, low battery) plus the protected-apps entry. Child
 * policies stay on Child Detail, so nothing child-scoped is repeated here.
 */
@Composable
fun ProtectionSettingsScreen(
    onBack: () -> Unit,
    onOpenProtectedApps: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsLoadError by viewModel.settingsLoadError.collectAsStateWithLifecycle()

    CategoryScaffold(titleRes = R.string.settings_category_protection, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            val current = settings
            if (current == null) {
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
                return@CategoryColumn
            }
            SectionCard(
                title = stringResource(R.string.settings_protection),
                subtitle = stringResource(R.string.settings_protection_hint),
            ) {
                SettingSwitchRow(
                    label = stringResource(R.string.settings_protection_toggle),
                    checked = current.protectionEnabled,
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
                    selected = current.scanMode,
                    onSelect = viewModel::setScanMode,
                )
            }
            SectionCard(
                title = stringResource(R.string.settings_recovery_delay),
                subtitle = stringResource(R.string.settings_recovery_delay_hint),
            ) {
                Column {
                    Slider(
                        value = (current.recoveryDelayMs / 1000f),
                        onValueChange = { viewModel.setRecoveryDelay(it.roundToInt()) },
                        valueRange = 5f..60f,
                    )
                    Text(
                        stringResource(
                            R.string.settings_recovery_delay_value,
                            (current.recoveryDelayMs / 1000L).toString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            SectionCard(
                title = stringResource(R.string.settings_unknown_policy),
                subtitle = stringResource(R.string.settings_unknown_policy_hint),
            ) {
                PolicyChips(selected = current.unknownUserPolicy, onSelect = viewModel::setUnknownPolicy)
            }
            SectionCard(
                title = stringResource(R.string.settings_no_face_policy),
                subtitle = stringResource(R.string.settings_no_face_policy_hint),
            ) {
                PolicyChips(selected = current.noFacePolicy, onSelect = viewModel::setNoFacePolicy)
            }
            SectionCard(
                title = stringResource(R.string.settings_low_battery),
                subtitle = stringResource(R.string.settings_low_battery_hint),
            ) {
                SettingSwitchRow(
                    label = stringResource(R.string.settings_low_battery_toggle),
                    checked = current.lowBatteryBehaviorEnabled,
                    onChange = viewModel::setLowBatteryBehavior,
                )
            }
            QalqonSettingRow(
                title = stringResource(R.string.papps_title),
                description = stringResource(R.string.settings_row_protected_apps_desc),
                onClick = onOpenProtectedApps,
            )
        }
    }
}

// ---------------------------------------------------------------------- Family

/** Family: the parent profile and the children hub (both existing destinations). */
@Composable
fun FamilySettingsScreen(
    onBack: () -> Unit,
    onOpenParentProfile: () -> Unit,
    onOpenChildren: () -> Unit,
) {
    CategoryScaffold(titleRes = R.string.settings_category_family, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            QalqonSettingRow(
                title = stringResource(R.string.settings_row_parent_profile),
                description = stringResource(R.string.settings_row_parent_profile_desc),
                onClick = onOpenParentProfile,
            )
            QalqonSettingRow(
                title = stringResource(R.string.settings_row_children),
                description = stringResource(R.string.settings_row_children_desc),
                onClick = onOpenChildren,
            )
        }
    }
}

// -------------------------------------------------------------------- Security

/**
 * Security: the process-scoped app lock (informational — it is always enforced) and
 * sign-out. PIN and biometric semantics are untouched; this only surfaces them.
 */
@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val logoutState by viewModel.logoutState.collectAsStateWithLifecycle()
    LaunchedEffect(logoutState) { if (logoutState is UiState.Success) onLoggedOut() }

    CategoryScaffold(titleRes = R.string.settings_category_security, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            QalqonSettingRow(
                title = stringResource(R.string.settings_row_app_lock),
                description = stringResource(R.string.settings_row_app_lock_desc),
            )
            AppLoadingButton(
                labelRes = R.string.settings_logout,
                loading = logoutState == UiState.Loading,
                onClick = viewModel::logout,
            )
        }
    }
}

// ------------------------------------------------------------------ Appearance

/** Appearance: the application language, through the existing single store. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val languageState by viewModel.language.collectAsStateWithLifecycle()

    CategoryScaffold(titleRes = R.string.settings_category_appearance, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            SectionCard(
                title = stringResource(R.string.language_section),
                subtitle = stringResource(R.string.language_section_hint),
            ) {
                LanguageOptions(
                    selected = (languageState as? LanguageState.Selected)?.language,
                    onSelect = viewModel::setLanguage,
                )
            }
        }
    }
}

// --------------------------------------------------------------- Notifications

/** Notifications: the real OS delivery status. The app owns no notification toggle. */
@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsStatusViewModel = hiltViewModel(),
) {
    val enabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshNotifications() }

    CategoryScaffold(titleRes = R.string.settings_category_notifications, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            val tone = if (enabled) QalqonStatusTone.ACTIVE else QalqonStatusTone.MISSING
            QalqonStatusCard(
                title = stringResource(
                    if (enabled) R.string.settings_notifications_enabled
                    else R.string.settings_notifications_disabled,
                ),
                statusColor = toneColor(tone),
                supportingText = stringResource(R.string.settings_notifications_hint),
            ) {}
        }
    }
}

// --------------------------------------------------------------------- Privacy

/**
 * Privacy: the honest on-device statement plus the existing local data tools
 * (per-subject face deletion and the full reset). No cloud/analytics claim is made —
 * the app has none.
 */
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val children by viewModel.children.collectAsStateWithLifecycle()
    val resetDone by viewModel.resetDone.collectAsStateWithLifecycle()
    LaunchedEffect(resetDone) { if (resetDone) onLoggedOut() }

    CategoryScaffold(titleRes = R.string.settings_category_privacy, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            SectionCard(title = stringResource(R.string.privacy_title)) {
                listOf(
                    R.string.privacy_point_offline,
                    R.string.privacy_point_on_device,
                    R.string.privacy_point_no_upload,
                    R.string.privacy_point_battery,
                ).forEach { point ->
                    Text(stringResource(point), style = MaterialTheme.typography.bodyMedium)
                }
            }
            LocalDataSection(
                children = children,
                onDeleteParentFace = viewModel::deleteParentFace,
                onDeleteChildFace = viewModel::deleteChildFace,
                onResetAll = viewModel::resetAll,
            )
        }
    }
}

/** Local data tools: per-subject face deletion + full reset with confirmation. */
@Composable
private fun LocalDataSection(
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
        Spacer(Modifier.height(QalqonDimens.spacing.sm))
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

// --------------------------------------------------------------------- Support

/** Support: the existing help destination. */
@Composable
fun SupportSettingsScreen(
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    CategoryScaffold(titleRes = R.string.settings_category_support, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            QalqonSettingRow(
                title = stringResource(R.string.help_title),
                description = stringResource(R.string.settings_row_help_desc),
                onClick = onOpenHelp,
            )
        }
    }
}

// ------------------------------------------------------------------- Developer

/**
 * Developer: debug-only diagnostics. Reached only from the developer category, which
 * the hub shows only in a debug build; the retention of the `BuildConfig.DEBUG` gate
 * is what keeps these tools out of production.
 */
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    onOpenRecognition: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val healthReport by viewModel.healthReport.collectAsStateWithLifecycle()
    val healthRunning by viewModel.healthRunning.collectAsStateWithLifecycle()
    val healthError by viewModel.healthError.collectAsStateWithLifecycle()

    CategoryScaffold(titleRes = R.string.settings_category_developer, onBack = onBack) { padding ->
        CategoryColumn(padding) {
            // The diagnostics area, labelled with the app's existing "Health" title.
            QalqonSectionHeader(title = stringResource(R.string.settings_tab_health))
            QalqonSettingRow(
                title = stringResource(R.string.home_recognition_debug),
                description = stringResource(R.string.settings_row_recognition_debug_desc),
                onClick = onOpenRecognition,
            )
            SystemHealthSection(
                report = healthReport,
                running = healthRunning,
                error = healthError,
                onRun = viewModel::runDiagnostics,
            )
        }
    }
}

/**
 * The system-health audit. Nothing is evaluated while composing — the parent runs the
 * check explicitly, so opening the section never reads portals or touches the service
 * in the background. Only the last report is rendered, and no raw machine reason is
 * shown as UI text.
 */
@Composable
private fun SystemHealthSection(
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

/**
 * Health-level colors come from the design-system semantic tokens, so the developer
 * diagnostics agree with the rest of the app in both light and dark themes.
 */
@Composable
private fun healthColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.OK -> QalqonTheme.colors.success
    DiagnosticStatus.WARNING -> QalqonTheme.colors.warning
    DiagnosticStatus.FAILED -> MaterialTheme.colorScheme.error
    DiagnosticStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun healthColor(level: SystemHealthLevel): Color = when (level) {
    SystemHealthLevel.HEALTHY -> QalqonTheme.colors.success
    SystemHealthLevel.DEGRADED -> QalqonTheme.colors.warning
    SystemHealthLevel.CRITICAL -> MaterialTheme.colorScheme.error
    SystemHealthLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ------------------------------------------------------------ shared controls

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
    Row(horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
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
