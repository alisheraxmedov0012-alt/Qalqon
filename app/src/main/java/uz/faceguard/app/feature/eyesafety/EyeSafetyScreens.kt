package uz.faceguard.app.feature.eyesafety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.repository.AccountRepository

/** Navigation contract for the per-child eye-safety route. */
object EyeSafetyArgs {
    const val CHILD_ID = "childId"
}

/**
 * Phase 6 Step 5: the per-child eye-safety configuration screen.
 *
 * The child comes from the route, so the screen is always scoped to one child; the controller is
 * built with that account + child and can therefore never read or write another child's settings.
 * The ViewModel touches only [EyeSafetyRepository] — no Room, no DAO, no protection runtime.
 */
@HiltViewModel
class EyeSafetyConfigViewModel @Inject constructor(
    accountRepository: AccountRepository,
    eyeSafetyRepository: EyeSafetyRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val childId: Long = savedStateHandle.get<Long>(EyeSafetyArgs.CHILD_ID) ?: -1L

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _ui = MutableStateFlow(EyeSafetyUiState())
    val ui: StateFlow<EyeSafetyUiState> = _ui.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val controller = MutableStateFlow<EyeSafetyConfigController?>(null)

    init {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount()
            if (account == null || childId <= 0L) {
                _state.value = UiState.Error(R.string.error_invalid_credentials)
                return@launch
            }
            val created = EyeSafetyConfigController(
                repository = eyeSafetyRepository,
                accountId = account.id,
                childId = childId,
                clock = System::currentTimeMillis,
                scope = viewModelScope,
            )
            controller.value = created
            _state.value = UiState.Success
            created.state.collect { _ui.value = it }
        }
    }

    fun onEnabledChange(value: Boolean) = controller.value?.onEnabledChange(value)

    fun onWarningEnterChange(value: String) = controller.value?.onWarningEnterChange(value)

    fun onWarningExitChange(value: String) = controller.value?.onWarningExitChange(value)

    fun onDangerEnterChange(value: String) = controller.value?.onDangerEnterChange(value)

    fun onDangerExitChange(value: String) = controller.value?.onDangerExitChange(value)

    fun onConfirmFramesChange(value: String) = controller.value?.onConfirmFramesChange(value)

    fun onWarningActionChange(value: ProtectionAction) = controller.value?.onWarningActionChange(value)

    fun onDangerActionChange(value: ProtectionAction) = controller.value?.onDangerActionChange(value)

    fun onErrorShown() = controller.value?.onErrorShown()

    fun save() {
        val target = controller.value ?: return
        viewModelScope.launch { if (target.save()) _saved.value = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EyeSafetyScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EyeSafetyConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    // Which values are on screen is a presentation concern and stays local to the composable.
    var advancedOpen by remember { mutableStateOf(false) }

    // Leave the screen only after the write actually succeeded.
    LaunchedEffect(saved) { if (saved) onSaved() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.eye_safety_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (val screen = state) {
            is UiState.Loading, is UiState.Idle -> StatusText(padding) {
                Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Error -> StatusText(padding) {
                Text(
                    stringResource(screen.messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is UiState.Success -> {
                val editor = ui.editor
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 24.dp),
                    contentPadding = PaddingValues(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.eye_safety_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ui.loadErrorMessageRes?.let { messageRes ->
                        item {
                            Text(
                                stringResource(messageRes),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.eye_safety_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    item {
                        SectionCard(
                            title = stringResource(R.string.eye_safety_enable),
                            subtitle = stringResource(
                                statusLabelRes(configured = ui.configured, enabled = editor.enabled),
                            ),
                        ) {
                            Switch(
                                checked = editor.enabled,
                                onCheckedChange = viewModel::onEnabledChange,
                                enabled = !editor.busy,
                            )
                            Text(
                                stringResource(R.string.eye_safety_enable_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!ui.configured) {
                                Text(
                                    stringResource(R.string.eye_safety_unconfigured_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item {
                        PercentField(
                            value = editor.warningEnterPercentText,
                            onValueChange = viewModel::onWarningEnterChange,
                            labelRes = R.string.eye_safety_warning_enter_label,
                            enabled = !editor.busy,
                        )
                    }
                    item {
                        PercentField(
                            value = editor.dangerEnterPercentText,
                            onValueChange = viewModel::onDangerEnterChange,
                            labelRes = R.string.eye_safety_danger_enter_label,
                            enabled = !editor.busy,
                        )
                    }

                    item {
                        SectionCard(
                            title = stringResource(R.string.eye_safety_warning_action),
                            subtitle = stringResource(R.string.eye_safety_warning_action_hint),
                        ) {
                            ActionChipRow(
                                selected = editor.warningAction,
                                enabled = !editor.busy,
                                onSelect = viewModel::onWarningActionChange,
                            )
                            Text(
                                stringResource(actionDescriptionRes(editor.warningAction)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        SectionCard(
                            title = stringResource(R.string.eye_safety_danger_action),
                            subtitle = stringResource(R.string.eye_safety_danger_action_hint),
                        ) {
                            ActionChipRow(
                                selected = editor.dangerAction,
                                enabled = !editor.busy,
                                onSelect = viewModel::onDangerActionChange,
                            )
                            Text(
                                stringResource(actionDescriptionRes(editor.dangerAction)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = { advancedOpen = !advancedOpen },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (advancedOpen) {
                                        R.string.eye_safety_advanced_hide
                                    } else {
                                        R.string.eye_safety_advanced_show
                                    },
                                ),
                            )
                        }
                    }

                    if (advancedOpen) {
                        item {
                            Text(
                                stringResource(R.string.eye_safety_hysteresis_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            PercentField(
                                value = editor.warningExitPercentText,
                                onValueChange = viewModel::onWarningExitChange,
                                labelRes = R.string.eye_safety_warning_exit_label,
                                enabled = !editor.busy,
                            )
                        }
                        item {
                            PercentField(
                                value = editor.dangerExitPercentText,
                                onValueChange = viewModel::onDangerExitChange,
                                labelRes = R.string.eye_safety_danger_exit_label,
                                enabled = !editor.busy,
                            )
                        }
                        item {
                            SectionCard(
                                title = stringResource(R.string.eye_safety_confirm_frames_label),
                                subtitle = stringResource(R.string.eye_safety_confirm_frames_hint),
                            ) {
                                OutlinedTextField(
                                    value = editor.confirmFramesText,
                                    onValueChange = viewModel::onConfirmFramesChange,
                                    label = { Text(stringResource(R.string.eye_safety_confirm_frames_label)) },
                                    singleLine = true,
                                    enabled = !editor.busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    editor.errorMessageRes?.let { messageRes ->
                        item {
                            Text(
                                stringResource(messageRes),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    item {
                        AppLoadingButton(
                            labelRes = R.string.btn_save,
                            loading = editor.saving,
                            onClick = viewModel::save,
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = onBack,
                            enabled = !editor.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.btn_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        content()
    }
}

/** A whole-percent field: a number keyboard and a localized label that states the unit. */
@Composable
private fun PercentField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A row of single-select chips over [EYE_SAFETY_ACTIONS], labelled from resources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionChipRow(
    selected: ProtectionAction,
    enabled: Boolean,
    onSelect: (ProtectionAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EYE_SAFETY_ACTIONS.forEach { action ->
            FilterChip(
                selected = action == selected,
                onClick = { onSelect(action) },
                enabled = enabled,
                label = { Text(stringResource(actionLabelRes(action))) },
            )
        }
    }
}
