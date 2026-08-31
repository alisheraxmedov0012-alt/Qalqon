package uz.faceguard.app.feature.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FaceCaptureController
import uz.faceguard.app.core.protection.OverlayControllerImpl
import uz.faceguard.app.core.protection.ProtectionEngine
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class ProtectionDebugViewModel @Inject constructor(
    private val recognizer: Recognizer,
    private val parentRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    private val protectedAppsRepository: ProtectedAppsRepository,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val activityLog: ActivityLogRepository,
    @ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val monitor = ForegroundAppMonitor(appContext)
    private val overlay = OverlayControllerImpl(appContext)
    private val audio = appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private val scheduler = ScanScheduler(appContext)
    private val engine = ProtectionEngine(recognizer, monitor, audio, overlay).also { it.attachScheduler(scheduler) }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    data class Ui(
        val account: UserAccount? = null,
        val parent: ParentProfile? = null,
        val children: List<ChildProfile> = emptyList(),
        val protectedApps: List<String> = emptyList(),
        val state: ProtectionState = ProtectionState.UNPROTECTED,
        val decision: String = "",
        val foreground: String? = null,
        val overlayGranted: Boolean = false,
        val scanMode: ScanMode = ScanMode.BALANCED,
        val scanning: Boolean = false,
        val cooldownRemaining: Long = 0L,
        val lastScanTrigger: String = "",
        val lastResult: RecognitionResult? = null,
        val lastConfidence: Double? = null,
        val fallbackPolicy: BlockPolicy = BlockPolicy.ALLOW,
    )

    private var controller: FaceCaptureController? = null

    fun setController(value: FaceCaptureController) {
        controller = value
        value.setRecognizer(recognizer)
    }

    /** Starts the headless analyzer once camera permission is granted. */
    fun startCamera() {
        controller?.startAnalyzerOnly()
    }

    init {
        engine.onEvent = { type, detail ->
            viewModelScope.launch { activityLog.log(type, detail) }
        }
        viewModelScope.launch {
            engine.attach(viewModelScope)
            val account = accountRepository.getCurrentAccount() ?: return@launch
            val parent = parentRepository.observe(account.id).first()
            val children = childRepository.observeChildren(account.id).first()
            val protectedApps = protectedAppsRepository.protectedApps.first().filter { it.isProtected }.map { it.packageName }
            val settings = settingsRepository.settings.first()
            engine.updateContext(parent, children, protectedApps.toSet())
            _ui.update { it.copy(account = account, parent = parent, children = children, protectedApps = protectedApps, overlayGranted = overlay.hasPermission(), scanMode = settings.scanMode) }
            monitor.start(viewModelScope)
            engine.start(viewModelScope, { settings.unknownUserPolicy }, { settings.scanMode })
            viewModelScope.launch {
                scheduler.scanning.collect { scanning -> _ui.update { it.copy(scanning = scanning) } }
            }
            viewModelScope.launch {
                scheduler.cooldownRemaining.collect { ms -> _ui.update { it.copy(cooldownRemaining = ms) } }
            }
            viewModelScope.launch {
                scheduler.lastEvent.collect { event -> _ui.update { it.copy(lastScanTrigger = event?.trigger?.name ?: "") } }
            }
            viewModelScope.launch {
                engine.decision.collect { d ->
                    _ui.update { it.copy(
                        state = d?.state ?: it.state,
                        decision = d?.reason ?: "",
                        lastConfidence = d?.confidence,
                        fallbackPolicy = settings.unknownUserPolicy,
                    ) }
                }
            }
        }
    }

    /** Async salted-PIN check; result delivered via callback. */
    fun emergencyUnlock(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = accountRepository.verifyPin(pin)
            if (ok) {
                engine.emergencyUnlock()
                _ui.update { it.copy(state = ProtectionState.UNPROTECTED) }
            }
            onResult(ok)
        }
    }

    fun stop() {
        controller?.stop()
        monitor.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ProtectionDebugScreen(
    onBack: () -> Unit,
    viewModel: ProtectionDebugViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    DisposableEffect(cameraPermission.status.isGranted) {
        val owned = FaceCaptureController(context)
        owned.setLifecycleOwner(lifecycleOwner)
        viewModel.setController(owned)
        if (cameraPermission.status.isGranted) viewModel.startCamera()
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.protection_debug_title)) },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!cameraPermission.status.isGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.recognition_permission_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.recognition_permission_message), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { cameraPermission.launchPermissionRequest() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.permission_grant))
                        }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.protection_state_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (ui.state) {
                            ProtectionState.UNPROTECTED -> stringResource(R.string.protection_state_unprotected)
                            ProtectionState.SOFT_BLOCKED -> stringResource(R.string.protection_state_soft)
                            ProtectionState.HARD_BLOCKED -> stringResource(R.string.protection_state_hard)
                            ProtectionState.RECOVERING -> stringResource(R.string.protection_state_recovering)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.protection_decision_label, ui.decision.ifEmpty { "—" }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.protection_result_label, when (ui.lastResult) {
                            is RecognitionResult.ParentRecognized -> stringResource(R.string.recognition_parent, "")
                            is RecognitionResult.ChildRecognized -> stringResource(R.string.recognition_child, ui.lastResult.childName, "")
                            is RecognitionResult.Unknown -> stringResource(R.string.recognition_unknown, "")
                            is RecognitionResult.CameraPossiblyObstructed -> stringResource(R.string.protection_result_obstructed)
                            is RecognitionResult.UnstableRecognition -> stringResource(R.string.protection_result_unstable)
                            RecognitionResult.NoFace -> stringResource(R.string.recognition_no_face)
                            null -> stringResource(R.string.recognition_idle)
                        }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ui.lastConfidence != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.protection_confidence_label, ui.lastConfidence),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.protection_fallback_label, when (ui.fallbackPolicy) {
                            BlockPolicy.ALLOW -> stringResource(R.string.policy_allow)
                            BlockPolicy.SOFT_BLOCK -> stringResource(R.string.policy_soft_block)
                            BlockPolicy.HARD_BLOCK -> stringResource(R.string.policy_hard_block)
                        }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.protection_scan_debug_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.protection_scan_mode, when (ui.scanMode) {
                            ScanMode.BATTERY_SAVER -> stringResource(R.string.scan_mode_battery_saver)
                            ScanMode.BALANCED -> stringResource(R.string.scan_mode_balanced)
                            ScanMode.STRICT -> stringResource(R.string.scan_mode_strict)
                        }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.protection_scan_state, if (ui.scanning) stringResource(R.string.protection_scan_on) else stringResource(R.string.protection_scan_off)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ui.cooldownRemaining > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.protection_scan_cooldown, ui.cooldownRemaining / 1000),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (ui.lastScanTrigger.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.protection_scan_trigger, ui.lastScanTrigger),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.protection_emergency_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    TextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text(stringResource(R.string.protection_emergency_pin_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pinError) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.protection_emergency_wrong_pin), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.emergencyUnlock(pinInput) { ok ->
                                pinError = !ok
                                if (ok) pinInput = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.protection_emergency_unlock))
                    }
                }
            }
        }
    }
}

