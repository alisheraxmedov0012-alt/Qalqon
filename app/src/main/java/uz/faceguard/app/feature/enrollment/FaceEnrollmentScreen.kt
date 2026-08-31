package uz.faceguard.app.feature.enrollment

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.camera.view.PreviewView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isPermanentlyDenied
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import uz.faceguard.app.R
import uz.faceguard.app.core.embed.FaceEmbeddable
import uz.faceguard.app.core.embed.PrivateStorageEmbeddable
import uz.faceguard.app.core.embed.PrivateStorageEmbeddable
import uz.faceguard.app.core.pipeline.EnrollmentSteps
import uz.faceguard.app.core.pipeline.FaceCaptureController
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository

const val SUBJECT_PARENT = "ota-ona"
const val SUBJECT_CHILD = "bola"

/**
 * Runs faces through `FaceCaptureController` and persists metadata once all
 * four steps have captured. `FaceEmbeddable` collects the
 * accepted frames (later the ML kit embedding output).
 */
@HiltViewModel
class FaceEnrollmentViewModel @Inject constructor(
    private val parentRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    private val accountRepository: AccountRepository,
    val recognizer: Recognizer,
) : ViewModel() {

    enum class Phase { IDLE, CAPTURING, SAVED, FAILED, CANCELED }

    data class Ui(
        val phase: Phase = Phase.CAPTURING,
        val stepIndex: Int = 0,
        val collected: Int = 0,
        val required: Int = EnrollmentSteps.size(),
        val template: String? = null,
        val errorRes: Int? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val frames = mutableListOf<FrameEvent>()
    var controller: FaceCaptureController? = null
    fun setController(value: FaceCaptureController) { controller = value }

    private var embeddable: FaceEmbeddable = PrivateStorageEmbeddable()
    fun setEmbeddable(value: FaceEmbeddable) { embeddable = value }

    /** Starts preview + analysis and routes accepted frames into [onFrame]. */
    fun startCamera(previewView: androidx.camera.view.PreviewView) {
        controller?.start(previewView, object : FaceCaptureController.Callback {
            override fun onFaceFrame(frame: FrameEvent) = onFrame(frame)
        })
    }

    fun onFrame(frame: FrameEvent) {
        if (_ui.value.phase != Phase.CAPTURING) return
        frames.add(frame)
        val collected = frames.size
        val needed = (_ui.value.stepIndex + 1).coerceAtLeast(1) * 3 /* arbitrary multiple */
        if (collected >= needed) advance()
        _ui.update { it.copy(collected = collected) }
    }

    fun advance() {
        val next = _ui.value.stepIndex + 1
        if (next >= EnrollmentSteps.size()) {
            viewModelScope.launch {
                val template = embeddable.collect(frames)
                if (template.isNotEmpty()) {
                    // parent enrollment keys off the account id, not the nav arg
                    val accountId = accountRepository.getCurrentAccount()?.id
                    when (subject) {
                        SUBJECT_PARENT -> accountId?.let { parentRepository.setFaceEnrolled(it, true) }
                        SUBJECT_CHILD -> if (subjectId > 0) childRepository.setFaceEnrolled(subjectId, true)
                        else -> Unit
                    }
                    frames.clear()
                    _ui.update { it.copy(phase = Phase.SAVED, template = template, stepIndex = EnrollmentSteps.size()) }
                } else {
                    _ui.update { it.copy(phase = Phase.FAILED, errorRes = R.string.enroll_failure_message) }
                }
            }
        } else {
            _ui.update { it.copy(stepIndex = next, collected = 0, phase = Phase.CAPTURING) }
        }
    }

    fun restart() = _ui.update { it.copy(phase = Phase.CAPTURING, stepIndex = 0, collected = 0, template = null, errorRes = null) }
    fun cancel() = _ui.update { it.copy(phase = Phase.CANCELED) }

    /** Bound by the screen from navigation args before capture starts. */
    var subject: String = SUBJECT_PARENT
        internal set
    var subjectId: Long = -1L
        internal set


}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun FaceEnrollmentScreen(
    onBack: () -> Unit,
    subject: String,
    childId: Long,
    viewModel: FaceEnrollmentViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val cameraPermission: PermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(cameraPermission.hasPermission) {
        if (cameraPermission.hasPermission) {
            viewModel.subject = subject
            viewModel.subjectId = childId
            val owned = FaceCaptureController(context)
            owned.setLifecycleOwner(lifecycleOwner)
            owned.setRecognizer(viewModel.recognizer)
            viewModel.setController(owned)
            viewModel.setEmbeddable(PrivateStorageEmbeddable())
        }
        onDispose { viewModel.controller?.stop() }
    }

    ScreenHeader(
        subjectLabel = subject,
        ui = ui,
        viewModel = viewModel,
        onBack = onBack,
        permission = cameraPermission,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenHeader(
    subjectLabel: String,
    ui: FaceEnrollmentViewModel.Ui,
    viewModel: FaceEnrollmentViewModel,
    onBack: () -> Unit,
    permission: com.google.accompanist.permissions.PermissionState,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enroll_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancel(); onBack() }) {
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
            Text(
                stringResource(if (subjectLabel == SUBJECT_PARENT) R.string.enroll_parent_hint else R.string.enroll_child_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!permission.hasPermission) {
                PermissionCard(permission)
            } else {
                PreviewCard(viewModel, ui)
                RemainingButtons(viewModel, ui, onBack)
            }
        }
    }
}

@Composable
private fun PermissionCard(permission: PermissionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.permission_message))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { permission.launchPermissionRequest() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permission_grant))
            }
            if (permission.status.isPermanentlyDenied) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.permission_denied),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(viewModel: FaceEnrollmentViewModel, ui: FaceEnrollmentViewModel.Ui) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) { view -> viewModel.startCamera(view) }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (ui.phase == FaceEnrollmentViewModel.Phase.SAVED) 1f else (ui.stepIndex / EnrollmentSteps.size()).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (ui.phase) {
                FaceEnrollmentViewModel.Phase.CAPTURING -> {
                    val step = ui.stepIndex
                    stringResource(EnrollmentSteps.stepRes(step))
                }
                FaceEnrollmentViewModel.Phase.CANCELED -> stringResource(R.string.enroll_canceled_message)
                FaceEnrollmentViewModel.Phase.FAILED -> stringResource(R.string.enroll_failure_message)
                FaceEnrollmentViewModel.Phase.SAVED -> stringResource(R.string.enroll_success_message)
                else -> stringResource(R.string.enroll_step_placeholder)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun RemainingButtons(
    viewModel: FaceEnrollmentViewModel,
    ui: FaceEnrollmentViewModel.Ui,
    onBack: () -> Unit,
) {
    Row {
        OutlinedButton(
            onClick = { viewModel.restart() },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.enroll_retry))
        }
        OutlinedButton(
            onClick = { viewModel.cancel(); onBack() },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.btn_cancel))
        }
    }
}
