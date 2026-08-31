package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import uz.faceguard.app.core.ui.AppPinField
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.util.Validation
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository

data class CreatePinUiState(
    val pin: String = "",
    val pinConfirm: String = "",
    val pinErrorRes: Int? = null,
    val confirmErrorRes: Int? = null,
    val state: UiState = UiState.Idle,
)

/**
 * Creates the local account from RegisterDraft (name/phone) + the PIN typed here.
 * Duplicate-phone failure is surfaced as a field error and stays on screen.
 */
@HiltViewModel
class CreatePinViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val parentProfileRepository: ParentProfileRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CreatePinUiState())
    val ui: StateFlow<CreatePinUiState> = _ui

    fun onPinChange(value: String) {
        _ui.update { it.copy(pin = value, pinErrorRes = null, state = UiState.Idle) }
    }

    fun onPinConfirmChange(value: String) {
        _ui.update { it.copy(pinConfirm = value, confirmErrorRes = null, state = UiState.Idle) }
    }

    fun create() {
        val state = _ui.value
        if (state.state == UiState.Loading) return

        if (!Validation.isValidPin(state.pin)) {
            _ui.update { it.copy(pinErrorRes = R.string.error_pin_length) }
            return
        }
        if (state.pin != state.pinConfirm) {
            _ui.update { it.copy(confirmErrorRes = R.string.error_pin_mismatch) }
            return
        }
        _ui.update { it.copy(state = UiState.Loading) }

        viewModelScope.launch {
            val name = RegisterDraft.fullName.trim()
            val phone = RegisterDraft.phoneNumber
            when (val result = accountRepository.register(name, phone, state.pin)) {
                is AuthResult.Success -> {
                    parentProfileRepository.createIfMissing(result.account.id, name)
                    _ui.update { it.copy(state = UiState.Success) }
                }
                is AuthResult.Failure -> {
                    val res = when (result.reason) {
                        AuthResult.Reason.DUPLICATE_PHONE -> R.string.error_duplicate_phone
                        else -> R.string.error_invalid_credentials
                    }
                    _ui.update { it.copy(state = UiState.Error(res)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePinScreen(
    onCreated: () -> Unit,
    viewModel: CreatePinViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.state) { if (ui.state is UiState.Success) onCreated() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.createpin_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.createpin_subtitle), style = MaterialTheme.typography.bodyLarge)
            AppPinField(
                value = ui.pin,
                onValueChange = viewModel::onPinChange,
                labelRes = R.string.label_pin,
                errorRes = ui.pinErrorRes,
            )
            AppPinField(
                value = ui.pinConfirm,
                onValueChange = viewModel::onPinConfirmChange,
                labelRes = R.string.label_pin_confirm,
                errorRes = ui.confirmErrorRes,
            )
            if (ui.state is UiState.Error) {
                Text(
                    stringResource((ui.state as UiState.Error).messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AppLoadingButton(
                labelRes = R.string.btn_create,
                loading = ui.state == UiState.Loading,
                onClick = viewModel::create,
            )
        }
    }
}
