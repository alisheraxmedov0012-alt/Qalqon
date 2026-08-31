package uz.faceguard.app.feature.recognition

import androidx.camera.view.PreviewView
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
import androidx.lifecycle.viewModelScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.pipeline.FaceCaptureController
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository

@HiltViewModel
class RecognitionDebugViewModel @Inject constructor(
    private val recognizer: Recognizer,
    private val parentRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private var controller: FaceCaptureController? = null

    fun setController(value: FaceCaptureController) {
        controller = value
    }

    /** Starts preview + analysis and routes face frames into [onFrame]. */
    fun startCamera(previewView: PreviewView) {
        controller?.start(previewView, object : FaceCaptureController.Callback {
            override fun onFaceFrame(frame: FrameEvent) = onFrame(frame)
        })
    } 

    data class Ui(
        val parent: ParentProfile? = null,
        val children: List<ChildProfile> = emptyList(),
        val account: UserAccount? = null,
        val lastResult: RecognitionResult? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui


    fun onFrame(frame: FrameEvent) {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount() ?: return@launch
            val parent = parentRepository.observe(account.id).first() ?: return@launch
            val children = childRepository.observeChildren(account.id).first()
            val result = recognizer.evaluate(frame, parent, children)
            _ui.update { it.copy(account = account, parent = parent, children = children, lastResult = result) }
        }
    }

    fun stop() = controller?.stop()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun RecognitionDebugScreen(
    onBack: () -> Unit,
    viewModel: RecognitionDebugViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val cameraPermission: PermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(cameraPermission.hasPermission) {
        if (cameraPermission.hasPermission) {
            val owned = FaceCaptureController(context)
            owned.setLifecycleOwner(lifecycleOwner)
            viewModel.setController(owned)
        }
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recognition_debug_title)) },
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
            if (!cameraPermission.hasPermission) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.recognition_permission_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.recognition_permission_message))
                        Row {
                            OutlinedButton(
                                onClick = { cameraPermission.launchPermissionRequest() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.permission_grant))
                            }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        AndroidView(
                            factory = { ctx -> PreviewView(ctx) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        ) { view -> viewModel.startCamera(view) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.recognition_status_label),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val status = when (ui.lastResult) {
                            is RecognitionResult.NoFace -> stringResource(R.string.recognition_no_face)
                            is RecognitionResult.Unknown -> stringResource(R.string.recognition_unknown, confidence(ui.lastResult.confidence))
                            is RecognitionResult.ParentRecognized -> stringResource(R.string.recognition_parent, confidence(ui.lastResult.confidence))
                            is RecognitionResult.ChildRecognized -> stringResource(R.string.recognition_child, ui.lastResult.childName, confidence(ui.lastResult.confidence))
                            // obstruction/instability are engine-level signals; show as unknown here
                            else -> stringResource(R.string.recognition_unknown, "")
                        }
                        Text(status, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private fun confidence(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
