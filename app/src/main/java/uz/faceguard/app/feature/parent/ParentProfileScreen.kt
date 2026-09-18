package uz.faceguard.app.feature.parent

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.AppTextField
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository

data class ParentProfileUiState(
    val uiState: UiState = UiState.Idle,
    val accountId: Long? = null,
    val accountPhone: String = "",
    val profile: ParentProfile? = null,
    val displayNameInput: String = "",
    val faceEnrolled: Boolean = false,
    val savedMessageVisible: Boolean = false,
) {
    val hasProfile: Boolean get() = profile != null
}

@HiltViewModel
class ParentProfileViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val parentProfileRepository: ParentProfileRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ParentProfileUiState(uiState = UiState.Loading))
    val ui: StateFlow<ParentProfileUiState> = _ui

    /** Last failure text, surfaced as a Toast; cleared by [consumeError]. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    init {
        viewModelScope.launch {
            try {
                val account = accountRepository.getCurrentAccount()
                if (account == null) {
                    _ui.update { it.copy(uiState = UiState.Error(R.string.error_invalid_credentials)) }
                } else {
                    _ui.update { it.copy(accountId = account.id, accountPhone = account.phoneNumber) }
                    parentProfileRepository.observe(account.id).collect { profile ->
                        val current = _ui.value
                        _ui.update {
                            it.copy(
                                uiState = UiState.Success,
                                profile = profile,
                                displayNameInput = current.displayNameInput.ifBlank { profile?.displayName ?: "" },
                                faceEnrolled = profile?.isFaceEnrolled ?: false,
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                reportError(t)
                _ui.update { it.copy(uiState = UiState.Error(R.string.error_invalid_credentials)) }
            }
        }
    }

    fun onDisplayNameChange(value: String) =
        _ui.update { it.copy(displayNameInput = value, savedMessageVisible = false) }

    /** create if absent, otherwise just renames display name. */
    fun save() {
        val accountId = _ui.value.accountId ?: return
        val name = _ui.value.displayNameInput.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            try {
                if (_ui.value.profile == null) {
                    parentProfileRepository.createIfMissing(accountId, name)
                } else {
                    parentProfileRepository.updateDisplayName(accountId, name)
                }
                _ui.update { it.copy(savedMessageVisible = true) }
            } catch (t: Throwable) {
                reportError(t)
            }
        }
    }

    fun consumeError() {
        _errorMessage.value = null
    }

    /** Surfaces a failure to the UI instead of letting it reach the crash handler. */
    fun reportError(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        _errorMessage.value = detail
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentProfileScreen(
    onBack: () -> Unit,
    onEnroll: () -> Unit,
    viewModel: ParentProfileViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(errorMessage) {
        val detail = errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(R.string.error_generic, detail), Toast.LENGTH_LONG).show()
        viewModel.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parent_title)) },
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
            when (ui.uiState) {
                is UiState.Loading ->
                    Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)

                is UiState.Error ->
                    Text(
                        stringResource((ui.uiState as UiState.Error).messageRes),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )

                else -> {
                    SectionCard(
                        title = stringResource(R.string.parent_details_title),
                        subtitle = stringResource(R.string.parent_details_subtitle),
                    ) {
                        AppTextField(
                            value = ui.displayNameInput,
                            onValueChange = viewModel::onDisplayNameChange,
                            labelRes = R.string.parent_display_name_hint,
                        )
                        if (ui.accountPhone.isNotBlank()) {
                            Text(
                                stringResource(R.string.parent_phone_label, ui.accountPhone),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppLoadingButton(
                            labelRes = if (ui.hasProfile) R.string.btn_save else R.string.parent_create,
                            loading = ui.uiState == UiState.Loading,
                            onClick = viewModel::save,
                        )
                        if (ui.savedMessageVisible) {
                            Text(
                                stringResource(R.string.parent_saved),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    SectionCard(
                        title = stringResource(R.string.parent_face_title),
                        subtitle = stringResource(R.string.parent_face_note),
                    ) {
                        Text(
                            stringResource(
                                if (ui.faceEnrolled) R.string.parent_face_enrolled
                                else R.string.parent_face_not_enrolled,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (ui.faceEnrolled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = {
                                try {
                                    onEnroll()
                                } catch (t: Throwable) {
                                    viewModel.reportError(t)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (ui.faceEnrolled) R.string.parent_face_reenroll
                                    else R.string.parent_face_placeholder,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
