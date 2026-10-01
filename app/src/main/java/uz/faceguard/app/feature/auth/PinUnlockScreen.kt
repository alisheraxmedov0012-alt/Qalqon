package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import androidx.fragment.app.FragmentActivity
import uz.faceguard.app.core.security.AndroidBiometricPrompt
import uz.faceguard.app.core.i18n.findComponentActivity
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.security.BiometricAuthResult
import uz.faceguard.app.core.security.BiometricAvailabilityProvider
import uz.faceguard.app.core.security.BiometricPolicy
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.AppPinField
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.security.formatLockoutRemaining

data class PinUnlockUiState(
    val pin: String = "",
    val wrongPin: Boolean = false,
    val lockoutRemainingMs: Long = 0L,
    /** True once the PIN or the OS biometrics matched; the screen navigates on it. */
    val unlocked: Boolean = false,
    val checking: Boolean = false,
    /** Whether this device can offer the Android biometric prompt at all. */
    val biometricAvailable: Boolean = false,
    /** A biometric prompt is currently showing. */
    val biometricInProgress: Boolean = false,
    /** The last biometric attempt did not match (a hint; PIN stays available). */
    val biometricFailed: Boolean = false,
    /** The one-shot automatic prompt has already been offered for this screen. */
    val autoPromptConsumed: Boolean = false,
)

/**
 * Startup unlock gate: Android biometrics first, QALQON PIN as the fallback.
 *
 * The PIN path is unchanged (the existing PBKDF2 + lockout implementation through
 * [PinUnlockController]); the biometric path only accepts a result that came from
 * `BiometricPrompt`, so QALQON's own face recognition can never unlock the UI.
 *
 * Nothing about biometrics is mandatory: a device without hardware, without an
 * enrolled biometric, or with a temporarily unavailable sensor simply shows the PIN.
 *
 * The typed PIN lives only in this state while the screen is open. It is never
 * persisted, never put in a Bundle or navigation argument, and cleared on every
 * attempt.
 */
@HiltViewModel
class PinUnlockViewModel @Inject constructor(
    accountRepository: AccountRepository,
    private val appLockState: AppLockState,
    biometricAvailability: BiometricAvailabilityProvider,
) : ViewModel() {

    private val controller = PinUnlockController(accountRepository, appLockState, biometricAvailability)

    private val available = controller.shouldOfferBiometric()

    private val _ui = MutableStateFlow(PinUnlockUiState(biometricAvailable = available))
    val ui: StateFlow<PinUnlockUiState> = _ui

    private var lockoutJob: Job? = null

    fun onPinChange(value: String) {
        _ui.update { it.copy(pin = value, wrongPin = false) }
    }

    /**
     * The OS prompt is shown exactly once automatically, and only where it can
     * succeed, so a device without biometrics never flashes a dialog.
     */
    fun consumeAutoPrompt(): Boolean {
        val state = _ui.value
        val shouldPrompt = !state.autoPromptConsumed &&
            state.biometricAvailable &&
            BiometricPolicy.shouldAutoPrompt(controller.biometricAvailability())
        if (shouldPrompt) _ui.update { it.copy(autoPromptConsumed = true, biometricInProgress = true) }
        return shouldPrompt
    }

    /** The user tapped the biometric button. */
    fun startBiometric() {
        if (_ui.value.biometricInProgress) return
        _ui.update { it.copy(biometricInProgress = true, biometricFailed = false) }
    }

    /** The OS reported an outcome; only success unlocks. */
    fun onBiometricResult(result: BiometricAuthResult) {
        when (result) {
            BiometricAuthResult.Success -> {
                _ui.update { it.copy(biometricInProgress = false, unlocked = true) }
                controller.onBiometricResult(result)
            }
            BiometricAuthResult.Failed ->
                // Non-terminal: the system dialog may still succeed. Just hint.
                _ui.update { it.copy(biometricFailed = true) }

            BiometricAuthResult.Cancelled ->
                // Includes "use PIN": return to the PIN form, still locked.
                _ui.update { it.copy(biometricInProgress = false, biometricFailed = false) }

            is BiometricAuthResult.Error ->
                _ui.update { it.copy(biometricInProgress = false, biometricFailed = true) }
        }
    }

    /** The prompt could not be shown at all (no host activity, etc.). */
    fun onBiometricUnavailable() {
        _ui.update { it.copy(biometricInProgress = false, biometricAvailable = false) }
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
    val context = LocalContext.current

    LaunchedEffect(ui.unlocked) { if (ui.unlocked) onUnlocked() }

    // The Android system dialog needs a FragmentActivity host. Obtained from the
    // composition, never retained: the prompt is created per attempt.
    val promptHost = context.findComponentActivity() as? FragmentActivity

    // Auto-offer the OS prompt once, when biometrics can actually succeed.
    LaunchedEffect(ui.biometricAvailable, promptHost) {
        if (promptHost == null) return@LaunchedEffect
        if (!viewModel.consumeAutoPrompt()) return@LaunchedEffect
        launchBiometricPrompt(promptHost, viewModel)
    }

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

            if (ui.biometricFailed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.lock_biometric_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))
            AppLoadingButton(
                labelRes = R.string.lock_unlock,
                loading = ui.checking,
                onClick = viewModel::unlock,
            )

            // Biometric is offered only when the OS can authenticate; it is an
            // alternative to the PIN, never a requirement.
            if (ui.biometricAvailable && promptHost != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.lock_pin_alternative),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.startBiometric()
                        launchBiometricPrompt(promptHost, viewModel)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.lock_biometric_button))
                }
            }
        }
    }
}

/**
 * Shows the Android system biometric dialog and forwards the OS outcome to the
 * ViewModel. If the prompt cannot be constructed at all (no host, unexpected
 * failure), the biometric option is withdrawn and the PIN remains — the app never
 * crashes and never locks the user out.
 */
private fun launchBiometricPrompt(
    host: FragmentActivity,
    viewModel: PinUnlockViewModel,
) {
    runCatching { AndroidBiometricPrompt.from(host).show(viewModel::onBiometricResult) }
        .onFailure { viewModel.onBiometricUnavailable() }
}
