package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.AppPinField
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.security.formatLockoutRemaining

data class PinUnlockUiState(
    val pin: String = "",
    val wrongPin: Boolean = false,
    val lockoutRemainingMs: Long = 0L,
    /** True once the PIN matched; the screen navigates on it. */
    val unlocked: Boolean = false,
    val checking: Boolean = false,
)

/**
 * Startup PIN gate.
 *
 * QALQON's management UI is sensitive, so a registered account is not enough on a
 * later launch: this screen is shown until the existing PIN check passes. The
 * verification itself is the repository's (`PinUnlockController` → the existing
 * PBKDF2 + lockout implementation); the ViewModel only drives the screen and the
 * lockout countdown.
 *
 * The typed PIN lives only in this state while the screen is open. It is never
 * persisted, never put in a Bundle or navigation argument, and cleared on every
 * attempt.
 */
@HiltViewModel
class PinUnlockViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val appLockState: AppLockState,
) : ViewModel() {

    private val controller = PinUnlockController(accountRepository, appLockState)

    private val _ui = MutableStateFlow(PinUnlockUiState())
    val ui: StateFlow<PinUnlockUiState> = _ui

    private var lockoutJob: Job? = null

    fun onPinChange(value: String) {
        _ui.update { it.copy(pin = value, wrongPin = false) }
    }

    fun unlock() {
        val state = _ui.value
        if (state.checking || state.lockoutRemainingMs > 0L) return

        _ui.update { it.copy(checking = true, wrongPin = false) }
        viewModelScope.launch {
            when (val result = controller.submit(state.pin)) {
                PinUnlockResult.Unlocked ->
                    // Clear the field as soon as it is no longer needed.
                    _ui.update { it.copy(pin = "", checking = false, unlocked = true) }

                PinUnlockResult.WrongPin ->
                    _ui.update { it.copy(pin = "", checking = false, wrongPin = true) }

                is PinUnlockResult.LockedOut -> {
                    _ui.update { it.copy(pin = "", checking = false, wrongPin = false) }
                    startLockoutCountdown(result.remainingMillis)
                }
            }
        }
    }

    /** Ticks the remaining lockout once a second, as the protection screen does. */
    private fun startLockoutCountdown(remainingMillis: Long) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            var remaining = remainingMillis.coerceAtLeast(0L)
            while (remaining > 0L) {
                _ui.update { it.copy(lockoutRemainingMs = remaining) }
                delay(1_000L)
                remaining -= 1_000L
            }
            _ui.update { it.copy(lockoutRemainingMs = 0L) }
        }
    }
}

/**
 * Locks QALQON's parent UI behind the existing PIN on every launch.
 *
 * Shown before Home whenever a registered account exists but this process has not
 * been unlocked. The screen is localized through the same resources as the rest of
 * the app, so it appears in the language the parent selected.
 */
@Composable
fun PinUnlockScreen(
    onUnlocked: () -> Unit,
    viewModel: PinUnlockViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.unlocked) { if (ui.unlocked) onUnlocked() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            AppPinField(
                value = ui.pin,
                onValueChange = viewModel::onPinChange,
                labelRes = R.string.label_pin,
                errorRes = if (ui.wrongPin) R.string.lock_wrong_pin else null,
            )

            if (ui.lockoutRemainingMs > 0L) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.lock_locked_out,
                        formatLockoutRemaining(ui.lockoutRemainingMs),
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))
            AppLoadingButton(
                labelRes = R.string.lock_unlock,
                loading = ui.checking,
                onClick = viewModel::unlock,
            )
        }
    }
}
