package uz.faceguard.app.feature.protection

import android.content.Intent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.security.SecurityState
import uz.faceguard.app.core.ui.qalqon.ProtectionDegradedBanner
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.repository.SettingsRepository
import uz.faceguard.app.domain.security.PinVerification
import uz.faceguard.app.domain.security.formatLockoutRemaining

@HiltViewModel
class ProtectionViewModel @Inject constructor(
    private val runtime: ProtectionRuntime,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<ProtectionRuntimeState> = runtime.state

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * Phase 8 (PIN lockout UX): the remaining temporary lockout, ticked once a
     * second while it lasts so the emergency-unlock UI can show a live countdown
     * instead of a generic failure.
     */
    private val _lockoutRemainingMs = MutableStateFlow(0L)
    val lockoutRemainingMs: StateFlow<Long> = _lockoutRemainingMs

    private var lockoutJob: Job? = null

    fun setProtectionEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setProtectionEnabled(enabled) }

    fun emergencyUnlock(pin: String, onResult: (PinVerification) -> Unit) =
        runtime.emergencyUnlock(pin) { result ->
            if (result is PinVerification.LockedOut) startLockoutCountdown(result.remainingMillis)
            onResult(result)
        }

    /** Counts the remaining lockout down to zero; a newer lockout replaces an older tick. */
    private fun startLockoutCountdown(remainingMillis: Long) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            var remaining = remainingMillis.coerceAtLeast(0L)
            while (remaining > 0L) {
                _lockoutRemainingMs.value = remaining
                delay(1_000L)
                remaining -= 1_000L
            }
            _lockoutRemainingMs.value = 0L
        }
    }

    fun refreshPermissions() = runtime.refreshPermissions()

    fun usageAccessIntent(): Intent = runtime.usageAccessIntent()

    fun overlayPermissionIntent(): Intent = runtime.overlayPermissionIntent()

    fun accessibilitySettingsIntent(): Intent = runtime.accessibilitySettingsIntent()

    /** The system settings page that fixes [capability], for the degraded banner action. */
    fun capabilitySettingsIntent(capability: ProtectionCapability): Intent =
        runtime.capabilitySettingsIntent(capability)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ProtectionScreen(
    onBack: () -> Unit,
    onOpenParentProfile: () -> Unit,
    onOpenProtectedApps: () -> Unit,
    viewModel: ProtectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showTech by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    // Google Play User Data policy: a non-accessibility-tool app that uses the
    // AccessibilityService API must show a clear in-app disclosure and obtain
    // affirmative consent before the user is sent to enable the service.
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    val lockoutRemainingMs by viewModel.lockoutRemainingMs.collectAsStateWithLifecycle()

    // Phase 7.1: the screen no longer owns the camera. The process-scoped camera
    // session (owned by ProtectionForegroundService) is the single owner and keeps
    // feeding the shared recognizer after this screen closes, so recognition is no
    // longer tied to this composable's lifecycle.

    // Refresh permission flags whenever the screen resumes (e.g. after granting
    // usage access or overlay permission in system settings).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.protection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
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
            MasterToggleCard(
                enabled = settings.protectionEnabled,
                onToggle = viewModel::setProtectionEnabled,
            )

            // Persistent degraded warning with a one-tap jump to the right settings page.
            ProtectionDegradedBanner(
                missing = state.degradedCapabilities,
                onFix = { capability ->
                    context.startActivity(viewModel.capabilitySettingsIntent(capability))
                },
                cameraLimitedAfterBoot = state.cameraLimitedAfterBoot,
            )

            StatusCard(state)

            RequirementsCard(
                state = state,
                // Authoritative from the runtime state, so a system-wide revocation is
                // reflected even before the permission dialog's callback lands.
                cameraGranted = state.cameraGranted,
                onGrantCamera = { cameraPermission.launchPermissionRequest() },
                onGrantUsage = { context.startActivity(viewModel.usageAccessIntent()) },
                onGrantOverlay = { context.startActivity(viewModel.overlayPermissionIntent()) },
                onOpenAccessibility = { showAccessibilityDisclosure = true },
                onOpenParentProfile = onOpenParentProfile,
                onOpenProtectedApps = onOpenProtectedApps,
            )

            EmergencyCard(
                pinInput = pinInput,
                pinError = pinError,
                lockoutRemainingMs = lockoutRemainingMs,
                onPinChange = { pinInput = it; pinError = false },
                onUnlock = {
                    viewModel.emergencyUnlock(pinInput) { result ->
                        when (result) {
                            PinVerification.Success -> {
                                pinError = false
                                pinInput = ""
                            }
                            // Wrong PIN: show the generic error. The ViewModel owns the
                            // lockout timer, which is rendered when a lockout is active.
                            PinVerification.InvalidPin -> pinError = true
                            is PinVerification.LockedOut -> pinError = false
                        }
                    }
                },
            )

            Text(
                stringResource(R.string.protection_limit_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val securityWarningRes = when (state.securityState) {
                SecurityState.RECOVERY_REQUIRED, SecurityState.CORRUPTED ->
                    R.string.security_recovery_required

                else -> null
            }
            securityWarningRes?.let { warning ->
                Text(
                    stringResource(warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TechnicalSection(
                state = state,
                expanded = showTech,
                onToggle = { showTech = !showTech },
            )
        }
    }

    // Google Play User Data policy (Accessibility API): clear in-app disclosure +
    // affirmative consent before enabling the service. The disclosure states what the
    // service does and which data it accesses; consent is the explicit confirm action.
    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
            text = { Text(stringResource(R.string.accessibility_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDisclosure = false
                    context.startActivity(viewModel.accessibilitySettingsIntent())
                }) {
                    Text(stringResource(R.string.accessibility_disclosure_agree))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text(stringResource(R.string.accessibility_disclosure_decline))
                }
            },
        )
    }
}

@Composable
private fun MasterToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.protection_toggle_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (enabled) R.string.protection_toggle_on else R.string.protection_toggle_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatusCard(state: ProtectionRuntimeState) {
    val title: String
    val detail: String
    when {
        !state.enabled -> {
            title = stringResource(R.string.protection_status_inactive)
            detail = stringResource(R.string.protection_status_inactive_hint)
        }
        !state.active -> {
            title = stringResource(R.string.protection_status_no_account)
            detail = stringResource(R.string.protection_status_no_account_hint)
        }
        !state.ready -> {
            title = stringResource(R.string.protection_status_setup_needed)
            detail = stringResource(R.string.protection_status_setup_hint)
        }
        else -> {
            title = stringResource(R.string.protection_status_active)
            detail = stringResource(R.string.protection_status_active_hint)
        }
    }

    val stateLabel = when (state.protectionState) {
        ProtectionState.UNPROTECTED -> stringResource(R.string.protection_state_unprotected)
        ProtectionState.SOFT_BLOCKED -> stringResource(R.string.protection_state_soft)
        ProtectionState.HARD_BLOCKED -> stringResource(R.string.protection_state_hard)
        ProtectionState.RECOVERING -> stringResource(R.string.protection_state_recovering)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.protection_status_state, stateLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RequirementsCard(
    state: ProtectionRuntimeState,
    cameraGranted: Boolean,
    onGrantCamera: () -> Unit,
    onGrantUsage: () -> Unit,
    onGrantOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenParentProfile: () -> Unit,
    onOpenProtectedApps: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.protection_requirements_title), style = MaterialTheme.typography.titleMedium)

            RequirementRow(
                label = stringResource(R.string.protection_req_camera),
                satisfied = cameraGranted,
                actionLabel = stringResource(R.string.protection_req_grant),
                onAction = onGrantCamera,
            )
            RequirementRow(
                label = stringResource(R.string.protection_req_usage),
                satisfied = state.usageAccessGranted,
                actionLabel = stringResource(R.string.protection_req_grant),
                onAction = onGrantUsage,
            )
            RequirementRow(
                label = stringResource(R.string.protection_req_overlay),
                satisfied = state.overlayGranted,
                actionLabel = stringResource(R.string.protection_req_grant),
                onAction = onGrantOverlay,
            )
            RequirementRow(
                label = stringResource(R.string.protection_req_accessibility),
                satisfied = state.accessibilityEnabled,
                actionLabel = stringResource(R.string.protection_req_open),
                onAction = onOpenAccessibility,
            )
            RequirementRow(
                label = stringResource(R.string.protection_req_parent_face),
                satisfied = state.parentFaceEnrolled,
                actionLabel = stringResource(R.string.protection_req_open),
                onAction = onOpenParentProfile,
            )
            RequirementRow(
                label = stringResource(R.string.protection_req_protected_apps),
                satisfied = state.protectedCount > 0,
                actionLabel = stringResource(R.string.protection_req_open),
                onAction = onOpenProtectedApps,
            )
        }
    }
}

@Composable
private fun RequirementRow(
    label: String,
    satisfied: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(if (satisfied) R.string.setup_done_mark else R.string.setup_todo_mark),
            color = if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (!satisfied) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        } else {
            Text(
                text = stringResource(R.string.protection_req_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmergencyCard(
    pinInput: String,
    pinError: Boolean,
    lockoutRemainingMs: Long,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    val lockedOut = lockoutRemainingMs > 0L
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.protection_emergency_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.protection_emergency_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = pinInput,
                onValueChange = onPinChange,
                label = { Text(stringResource(R.string.protection_emergency_pin_label)) },
                enabled = !lockedOut,
                modifier = Modifier.fillMaxWidth(),
            )
            if (lockedOut) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.protection_emergency_locked_out,
                        formatLockoutRemaining(lockoutRemainingMs),
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (pinError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.protection_emergency_wrong_pin),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onUnlock,
                enabled = !lockedOut,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.protection_emergency_unlock))
            }
        }
    }
}

@Composable
private fun TechnicalSection(
    state: ProtectionRuntimeState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.protection_tech_title),
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
                    stringResource(R.string.protection_decision_label, state.decision.ifEmpty { "—" }),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.confidence?.let { confidence ->
                    Text(
                        stringResource(R.string.protection_confidence_label, confidence),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(
                        R.string.protection_scan_mode,
                        when (state.scanMode) {
                            ScanMode.BATTERY_SAVER -> stringResource(R.string.scan_mode_battery_saver)
                            ScanMode.BALANCED -> stringResource(R.string.scan_mode_balanced)
                            ScanMode.STRICT -> stringResource(R.string.scan_mode_strict)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(
                        R.string.protection_scan_state,
                        stringResource(
                            if (state.scanning) R.string.protection_scan_on else R.string.protection_scan_off,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.cooldownRemainingMs > 0) {
                    Text(
                        stringResource(R.string.protection_scan_cooldown, state.cooldownRemainingMs / 1000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.lastTrigger.isNotEmpty()) {
                    Text(
                        stringResource(R.string.protection_scan_trigger, state.lastTrigger),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.foregroundApp?.let { foreground ->
                    Text(
                        stringResource(R.string.home_foreground_current, foreground),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
