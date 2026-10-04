package uz.faceguard.app.feature.enrollment

import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.embed.FaceEmbeddable
import uz.faceguard.app.core.embed.FaceEmbeddingModel
import uz.faceguard.app.core.embed.FaceFeatureExtractor
import uz.faceguard.app.core.embed.MeanFaceEmbeddingCollector
import uz.faceguard.app.core.enrollment.EnrollmentFrameMapper
import uz.faceguard.app.core.pipeline.FaceCaptureController
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.protection.CameraBindingCoordinator
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.enrollment.EnrollmentFrame
import uz.faceguard.app.domain.enrollment.EnrollmentProgress
import uz.faceguard.app.domain.enrollment.EnrollmentQualityConfig
import uz.faceguard.app.domain.enrollment.EnrollmentQualityGate
import uz.faceguard.app.domain.enrollment.EnrollmentRejection
import uz.faceguard.app.domain.enrollment.EnrollmentStage
import uz.faceguard.app.domain.enrollment.FaceEmbeddingValidator
import uz.faceguard.app.domain.enrollment.FrontalEnrollmentCollector
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository

const val SUBJECT_PARENT = "ota-ona"
const val SUBJECT_CHILD = "bola"

/**
 * Guided, robust frontal face enrollment.
 *
 * The user does one thing: face the camera naturally for a moment. Every frame
 * is quality-checked (single face, size, position, frontal pose, light, focus,
 * occlusion, valid embedding) and only frames that pass are accumulated. A
 * template is stored **only** once enough quality frames have been held over a
 * short span and their embeddings agree with each other — never because a single
 * good-looking frame appeared or merely because the camera opened.
 *
 * The user is never asked to turn left/right/up/down, and never taps to capture.
 */
@HiltViewModel
class FaceEnrollmentViewModel @Inject constructor(
    private val parentRepository: ParentProfileRepository,
    private val childRepository: ChildProfileRepository,
    private val accountRepository: AccountRepository,
    val recognizer: Recognizer,
    val embeddingModel: FaceEmbeddingModel,
    private val cameraCoordinator: CameraBindingCoordinator,
) : ViewModel() {

    enum class Phase { IDLE, CAPTURING, SAVED, FAILED, CANCELED }

    data class Ui(
        val phase: Phase = Phase.CAPTURING,
        val stage: EnrollmentStage = EnrollmentStage.SEARCHING,
        val hintRes: Int = R.string.enroll_hint_initial,
        val holdProgress: Float = 0f,
        val captured: Int = 0,
        val required: Int = 0,
        val template: String? = null,
        val errorRes: Int? = null,
    )

    private val qualityConfig = buildQualityConfig()
    private val collector = FrontalEnrollmentCollector(
        config = qualityConfig,
        gate = EnrollmentQualityGate(
            config = qualityConfig,
            validator = FaceEmbeddingValidator(
                expectedDimension = qualityConfig.expectedEmbeddingDimension,
                requireUnitNorm = qualityConfig.requireUnitNorm,
                unitNormTolerance = qualityConfig.unitNormTolerance,
            ),
        ),
    )

    private val _ui = MutableStateFlow(Ui(required = qualityConfig.framesRequired))
    val ui: StateFlow<Ui> = _ui

    /** Last failure, surfaced as a Toast; a resource id so no technical detail leaks. */
    private val _errorMessage = MutableStateFlow<Int?>(null)
    val errorMessage: StateFlow<Int?> = _errorMessage

    /**
     * The live frames behind the collector's selected samples. EnrollmentFrame is
     * identity-keyed, so this maps each selected sample back to the FrameEvent the
     * existing [FaceEmbeddable] aggregates. Bounded by the collector's window.
     */
    private val capturedFrames = HashMap<EnrollmentFrame, FrameEvent>()

    private var finishing = false

    var controller: FaceCaptureController? = null
        private set

    private var startedPreview: PreviewView? = null
    private var pendingPreview: PreviewView? = null

    fun setController(value: FaceCaptureController) {
        controller = value
        value.setErrorListener { reportError(it) }
        // Compose runs AndroidView's update lambda (which reports the preview)
        // before DisposableEffect creates this controller, so the preview can
        // arrive first. Attach it now that the controller exists.
        pendingPreview?.let { attach(it, force = true) }
    }

    private var embeddable: FaceEmbeddable = MeanFaceEmbeddingCollector()
    fun setEmbeddable(value: FaceEmbeddable) { embeddable = value }

    /** Bound by the screen from navigation args before capture starts. */
    var subject: String = SUBJECT_PARENT
        internal set
    var subjectId: Long = -1L
        internal set

    fun bindSubject(subject: String, subjectId: Long) {
        this.subject = subject
        this.subjectId = subjectId
    }

    /**
     * Attaches the preview and starts the camera. Safe to call before the
     * controller exists: the request is remembered and honoured by
     * [setController], and a PreviewView never starts the camera twice.
     */
    fun startCamera(previewView: PreviewView) {
        pendingPreview = previewView
        attach(previewView, force = false)
    }

    private fun attach(previewView: PreviewView, force: Boolean) {
        if (!force && startedPreview === previewView) return
        val owned = controller ?: return
        startedPreview = previewView
        try {
            owned.start(previewView, object : FaceCaptureController.Callback {
                override fun onFaceFrame(frame: FrameEvent) = onFrame(frame)
            })
        } catch (t: Throwable) {
            reportError(t)
        }
    }

    fun stopCamera() {
        val hadCamera = controller != null
        startedPreview = null
        pendingPreview = null
        try {
            controller?.stop()
        } catch (t: Throwable) {
            reportError(t)
        }
        // Q-1 fix: this transient screen has taken and now released the shared
        // camera. If protection is still active it must get its binding back —
        // only announce when this screen actually held the camera.
        if (hadCamera) cameraCoordinator.onTransientCameraReleased()
    }

    fun onFrame(frame: FrameEvent) {
        try {
            if (_ui.value.phase != Phase.CAPTURING || finishing) return

            val mapped = EnrollmentFrameMapper.map(
                quality = frame.quality,
                faceCount = frame.faceCount,
                embedding = frame.features,
                timestampMs = frame.timestamp,
            )
            if (mapped.embedding != null) capturedFrames[mapped] = frame

            val progress = collector.onFrame(mapped)
            if (progress.complete) {
                finishCapture(collector.selected())
                return
            }
            _ui.update {
                it.copy(
                    stage = progress.stage,
                    hintRes = hintResFor(progress),
                    holdProgress = progress.progressFraction,
                    captured = progress.acceptedFrames,
                    required = progress.requiredFrames,
                )
            }
        } catch (t: Throwable) {
            reportError(t)
        }
    }

    /**
     * Aggregates the selected best frames into the stored template — only reached
     * after the collector has confirmed enough consistent, quality-checked samples.
     */
    private fun finishCapture(selected: List<EnrollmentFrame>) {
        if (finishing) return
        finishing = true
        _ui.update {
            it.copy(
                stage = EnrollmentStage.VALIDATING,
                hintRes = R.string.enroll_hint_preparing,
                holdProgress = 1f,
            )
        }

        val frames = selected.mapNotNull { capturedFrames[it] }
        viewModelScope.launch {
            try {
                val template = embeddable.collect(frames)
                if (template.isEmpty()) {
                    // No usable embedding survived: keep guiding instead of saving.
                    clearCollection(R.string.enroll_hint_quality_retry, EnrollmentStage.QUALITY_CHECK)
                    return@launch
                }

                val accountId = accountRepository.getCurrentAccount()?.id
                when (subject) {
                    SUBJECT_PARENT -> accountId?.let { parentRepository.saveFaceEnrollment(it, template) }
                    SUBJECT_CHILD -> if (subjectId > 0) {
                        accountId?.let { childRepository.saveFaceEnrollment(it, subjectId, template) }
                    }
                    else -> Unit
                }
                clearCollection()
                _ui.update {
                    it.copy(
                        phase = Phase.SAVED,
                        stage = EnrollmentStage.SUCCESS,
                        hintRes = R.string.enroll_success_message,
                        template = template,
                        holdProgress = 1f,
                    )
                }
            } catch (t: Throwable) {
                clearCollection()
                reportError(t)
                _ui.update {
                    it.copy(
                        phase = Phase.FAILED,
                        stage = EnrollmentStage.FAILED,
                        errorRes = R.string.enroll_failure_message,
                    )
                }
            }
        }
    }

    fun restart() {
        clearCollection(R.string.enroll_hint_initial, EnrollmentStage.SEARCHING)
        _ui.update {
            it.copy(
                phase = Phase.CAPTURING,
                hintRes = R.string.enroll_hint_initial,
                template = null,
                errorRes = null,
            )
        }
    }

    fun cancel() = _ui.update { it.copy(phase = Phase.CANCELED) }

    fun consumeError() {
        _errorMessage.value = null
    }

    /**
     * Reports a failure without exposing internal detail to the user.
     *
     * The exception (message / class) is written to logcat for diagnosis, but the
     * UI only receives a generic, localized message — a parent must never be shown
     * a raw exception string.
     */
    fun reportError(error: Throwable) {
        Log.w(TAG, "face enrollment operation failed", error)
        _errorMessage.value = R.string.error_unexpected
    }

    private fun clearCollection(hintRes: Int? = null, stage: EnrollmentStage? = null) {
        collector.reset()
        capturedFrames.clear()
        finishing = false
        _ui.update {
            it.copy(
                stage = stage ?: EnrollmentStage.STABILIZING,
                hintRes = hintRes ?: R.string.enroll_hint_initial,
                holdProgress = 0f,
                captured = 0,
                required = qualityConfig.framesRequired,
            )
        }
    }

    /**
     * The expected embedding shape follows whichever extractor is actually
     * running: MobileFaceNet's L2-normalized vectors when the model is ready,
     * otherwise the fixed-size, non-normalized geometry fallback.
     */
    private fun buildQualityConfig(): EnrollmentQualityConfig {
        val ready = runCatching { embeddingModel.isReady() }.getOrDefault(false)
        return if (ready) {
            EnrollmentQualityConfig(
                expectedEmbeddingDimension = runCatching { embeddingModel.embeddingSize() }.getOrDefault(0),
                requireUnitNorm = true,
            )
        } else {
            EnrollmentQualityConfig(expectedEmbeddingDimension = FaceFeatureExtractor.DIM)
        }
    }

    private fun hintResFor(progress: EnrollmentProgress): Int =
        when (progress.rejection) {
            EnrollmentRejection.NO_FACE -> R.string.enroll_hint_no_face
            EnrollmentRejection.MULTIPLE_FACES -> R.string.enroll_hint_multiple_faces
            EnrollmentRejection.TOO_SMALL -> R.string.enroll_hint_too_far
            EnrollmentRejection.TOO_CLOSE -> R.string.enroll_hint_too_close
            EnrollmentRejection.OFF_CENTER -> R.string.enroll_hint_off_center
            EnrollmentRejection.NOT_FRONTAL -> R.string.enroll_hint_look_straight
            EnrollmentRejection.TOO_DARK -> R.string.enroll_hint_too_dark
            EnrollmentRejection.TOO_BRIGHT -> R.string.enroll_hint_too_bright
            EnrollmentRejection.BLURRY -> R.string.enroll_hint_hold_still
            EnrollmentRejection.OCCLUDED -> R.string.enroll_hint_occluded
            EnrollmentRejection.INVALID_EMBEDDING -> R.string.enroll_hint_quality_retry
            EnrollmentRejection.INCONSISTENT_SAMPLES -> R.string.enroll_hint_unstable
            null -> when (progress.stage) {
                EnrollmentStage.STABILIZING -> R.string.enroll_hint_hold
                EnrollmentStage.VALIDATING -> R.string.enroll_hint_preparing
                EnrollmentStage.SUCCESS -> R.string.enroll_success_message
                EnrollmentStage.FAILED -> R.string.enroll_failure_message
                EnrollmentStage.SEARCHING -> R.string.enroll_hint_initial
                EnrollmentStage.POSITIONING -> R.string.enroll_hint_off_center
                EnrollmentStage.FRONTAL_REQUIRED -> R.string.enroll_hint_look_straight
                EnrollmentStage.QUALITY_CHECK -> R.string.enroll_hint_quality_retry
            }
        }

    private companion object {
        const val TAG = "FaceEnrollment"
    }
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
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(errorMessage) {
        val messageRes = errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
        viewModel.consumeError()
    }

    DisposableEffect(cameraPermission.status.isGranted) {
        if (cameraPermission.status.isGranted) {
            try {
                viewModel.bindSubject(subject, childId)
                val owned = FaceCaptureController(context)
                owned.setLifecycleOwner(lifecycleOwner)
                owned.setRecognizer(viewModel.recognizer)
                owned.setEmbeddingModel(viewModel.embeddingModel)
                viewModel.setController(owned)
                viewModel.setEmbeddable(MeanFaceEmbeddingCollector())
            } catch (t: Throwable) {
                viewModel.reportError(t)
            }
        }
        onDispose { viewModel.stopCamera() }
    }

    ScreenHeader(
        subjectLabel = subject,
        ui = ui,
        viewModel = viewModel,
        onBack = onBack,
        permission = cameraPermission,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
private fun ScreenHeader(
    subjectLabel: String,
    ui: FaceEnrollmentViewModel.Ui,
    viewModel: FaceEnrollmentViewModel,
    onBack: () -> Unit,
    permission: PermissionState,
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
                stringResource(
                    if (subjectLabel == SUBJECT_PARENT) R.string.enroll_parent_hint
                    else R.string.enroll_child_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!permission.status.isGranted) {
                PermissionCard(permission)
            } else {
                PreviewCard(viewModel, ui)
                RemainingButtons(viewModel, ui, onBack)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
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
            if (!permission.status.isGranted && !permission.status.shouldShowRationale) {
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
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        // TextureView-based rendering composites correctly inside
                        // Compose; the default SurfaceView mode shows up black.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) { view -> viewModel.startCamera(view) }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (ui.phase == FaceEnrollmentViewModel.Phase.SAVED) 1f else ui.holdProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when (ui.phase) {
                FaceEnrollmentViewModel.Phase.SAVED -> stringResource(R.string.enroll_success_message)
                FaceEnrollmentViewModel.Phase.CANCELED -> stringResource(R.string.enroll_canceled_message)
                FaceEnrollmentViewModel.Phase.FAILED -> stringResource(R.string.enroll_failure_message)
                else -> stringResource(ui.hintRes)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (ui.phase == FaceEnrollmentViewModel.Phase.CAPTURING && ui.required > 0) {
            Spacer(Modifier.height(8.dp))
            CaptureProgressDots(captured = ui.captured, required = ui.required)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.enroll_progress, ui.captured, ui.required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The "dot dot dot circle circle" progress the enrollment spec asks for. */
@Composable
private fun CaptureProgressDots(captured: Int, required: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(required) { index ->
            Spacer(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < captured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

@Composable
private fun RemainingButtons(
    viewModel: FaceEnrollmentViewModel,
    ui: FaceEnrollmentViewModel.Ui,
    onBack: () -> Unit,
) {
    if (ui.phase == FaceEnrollmentViewModel.Phase.SAVED) {
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.enroll_done))
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
