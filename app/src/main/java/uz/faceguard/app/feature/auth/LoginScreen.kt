package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import uz.faceguard.app.core.ui.AppPhoneField
import uz.faceguard.app.core.ui.AppPinField
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.util.Validation
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.repository.AccountRepository

data class LoginUiState(
    val phoneNumber: String = "",
    val pin: String = "",
    val phoneErrorRes: Int? = null,
    val pinErrorRes: Int? = null,
    val state: UiState = UiState.Idle,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui

    fun onPhoneChange(value: String) {
        _ui.update { it.copy(phoneNumber = value, phoneErrorRes = null, state = UiState.Idle) }
    }

    fun onPinChange(value: String) {
        _ui.update { it.copy(pin = value, pinErrorRes = null, state = UiState.Idle) }
    }

    fun login() {
        val state = _ui.value
        if (state.state == UiState.Loading) return

        val phoneOk = Validation.isValidPhone(state.phoneNumber)
        val pinOk = Validation.isValidPin(state.pin)
        if (!phoneOk || !pinOk) {
            _ui.update {
                it.copy(
                    phoneErrorRes = if (phoneOk) null else R.string.error_invalid_phone,
                    pinErrorRes = if (pinOk) null else R.string.error_pin_length,
                )
            }
            return
        }
        _ui.update { it.copy(state = UiState.Loading) }

        viewModelScope.launch {
            val result = accountRepository.login(state.phoneNumber, state.pin)
            when (result) {
                is AuthResult.Success ->
                    _ui.update { it.copy(state = UiState.Success) }
                is AuthResult.Failure ->
                    _ui.update {
                        it.copy(
                            state = UiState.Error(R.string.error_invalid_credentials),
                            pin = "",
                        )
                    }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLogin: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.state) { if (ui.state is UiState.Success) onLogin() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            AppPhoneField(
                value = ui.phoneNumber,
                onValueChange = viewModel::onPhoneChange,
                labelRes = R.string.label_phone,
                errorRes = ui.phoneErrorRes,
            )
            AppPinField(
                value = ui.pin,
                onValueChange = viewModel::onPinChange,
                labelRes = R.string.label_pin,
                errorRes = ui.pinErrorRes,
            )
            if (ui.state is UiState.Error) {
                Text(
                    stringResource((ui.state as UiState.Error).messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AppLoadingButton(
                labelRes = R.string.btn_login,
                loading = ui.state == UiState.Loading,
                onClick = viewModel::login,
            )
        }
    }
}
