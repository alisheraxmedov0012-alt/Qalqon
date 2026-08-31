package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.AppPhoneField
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.AppTextField
import uz.faceguard.app.core.util.Validation

data class RegisterUiState(
    val fullName: String = "",
    val phoneNumber: String = "",
    val nameErrorRes: Int? = null,
    val phoneErrorRes: Int? = null,
    val proceed: Boolean = false,
)

/** Form scaffolding: validates and forwards name/phone to the PIN step via RegisterDraft. */
@HiltViewModel
class RegisterViewModel @Inject constructor() : ViewModel() {

    private val _ui = MutableStateFlow(RegisterUiState())
    val ui: StateFlow<RegisterUiState> = _ui

    fun onFullNameChange(value: String) =
        _ui.update { it.copy(fullName = value, nameErrorRes = null) }

    fun onPhoneChange(value: String) =
        _ui.update { it.copy(phoneNumber = value, phoneErrorRes = null) }

    fun submit() {
        val state = _ui.value
        val nameOk = Validation.isValidFullName(state.fullName)
        val phoneOk = Validation.isValidPhone(state.phoneNumber)
        if (!nameOk || !phoneOk) {
            _ui.update {
                it.copy(
                    nameErrorRes = if (nameOk) null else R.string.error_invalid_name,
                    phoneErrorRes = if (phoneOk) null else R.string.error_invalid_phone,
                )
            }
            return
        }
        RegisterDraft.fullName = state.fullName.trim()
        RegisterDraft.phoneNumber = Validation.normalizePhone(state.phoneNumber)
        _ui.update { it.copy(proceed = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.proceed) { if (ui.proceed) onNext() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.register_title)) },
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
            Text(stringResource(R.string.register_subtitle), style = MaterialTheme.typography.bodyLarge)

            AppTextField(
                value = ui.fullName,
                onValueChange = viewModel::onFullNameChange,
                labelRes = R.string.label_full_name,
                errorRes = ui.nameErrorRes,
            )
            AppPhoneField(
                value = ui.phoneNumber,
                onValueChange = viewModel::onPhoneChange,
                labelRes = R.string.label_phone,
                errorRes = ui.phoneErrorRes,
            )
            AppLoadingButton(
                labelRes = R.string.btn_continue,
                loading = false,
                onClick = viewModel::submit,
            )
        }
    }
}
