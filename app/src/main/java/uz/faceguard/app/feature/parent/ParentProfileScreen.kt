package uz.faceguard.app.feature.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

    init {
        viewModelScope.launch {
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
            if (_ui.value.profile == null) {
                parentProfileRepository.createIfMissing(accountId, name)
            } else {
                parentProfileRepository.updateDisplayName(accountId, name)
            }
            _ui.update { it.copy(savedMessageVisible = true) }
        }
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
                    Text(stringResource((ui.uiState as UiState.Error).messageRes), color = MaterialTheme.colorScheme.error)
                else -> {
                    if (!ui.hasProfile) {
                        EmptyCard(stringResource(R.string.parent_empty))
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(if (ui.hasProfile) R.string.parent_edit_hint else R.string.parent_create_hint),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ui.displayNameInput,
                                onValueChange = viewModel::onDisplayNameChange,
                                label = { Text(stringResource(R.string.parent_display_name_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(ui.accountPhone, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            AppLoadingButton(
                                labelRes = if (ui.hasProfile) R.string.btn_save else R.string.parent_create,
                                loading = ui.uiState == UiState.Loading,
                                onClick = viewModel::save,
                            )
                            if (ui.savedMessageVisible) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.parent_saved),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(
                                    if (ui.faceEnrolled) R.string.parent_face_enrolled
                                    else R.string.parent_face_not_enrolled,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (ui.faceEnrolled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                                                        OutlinedButton(onClick = onEnroll) {
                                Text(stringResource(R.string.parent_face_placeholder))
                            }
                            Text(
                                stringResource(R.string.parent_face_note),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
