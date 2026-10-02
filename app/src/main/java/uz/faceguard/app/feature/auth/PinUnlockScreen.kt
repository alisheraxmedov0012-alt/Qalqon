package uz.faceguard.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.AppPinField
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
    /**
     * Presentation only: whether the PIN entry is currently shown. The screen starts
     * biometric-first when the device can authenticate, and falls back to the PIN
     * entry otherwise. It never affects authentication — only what is on screen.
     */
    val pinMode: Boolean = false,
)

/**
 * Startup unlock gate: Android biometrics first, QALQON PIN as the fallback.
 *
 * The PIN path is unchanged (the existing PBKDF2 + lockout implementation through
 * [PinUnlockController]); the biometric path only accepts a result that came from
 * `BiometricPrompt`, so QALQON's own face recognition can never unlock the UI.
 *
 * The screen opens biometric-first: when the OS can authenticate, the prompt is shown
 * once automatically and the PIN entry stays behind a single, always-visible
 * "use PIN" action. Nothing about biometrics is mandatory: a device without hardware,
 * without an enrolled biometric, or with a temporarily unavailable sensor goes
 * straight to the PIN entry.
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

    private val _ui = MutableStateFlow(
        // Biometric-first when it can actually succeed; PIN entry otherwise.
        PinUnlockUiState(biometricAvailable = available, pinMode = !available),
    )
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

    /** The user tapped the biometric action (first attempt or a retry). */
    fun startBiometric() {
        if (_ui.value.biometricInProgress) return
        _ui.update { it.copy(biometricInProgress = true, biometricFailed = false, pinMode = false) }
    }

    /** The user chose the PIN entry from the biometric-first screen. */
    fun showPin() {
        _ui.update { it.copy(pinMode = true, biometricInProgress = false) }
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
                // Includes the OS dialog's own "use PIN" button: reveal the PIN entry,
                // still locked.
                _ui.update { it.copy(biometricInProgress = false, biometricFailed = false, pinMode = true) }

            is BiometricAuthResult.Error ->
                // A terminal OS error: keep the PIN entry reachable immediately.
                _ui.update { it.copy(biometricInProgress = false, biometricFailed = true, pinMode = true) }
        }
    }

    /** The prompt could not be shown at all (no host activity, etc.). */
    fun onBiometricUnavailable() {
        _ui.update { it.copy(biometricInProgress = false, biometricAvailable = false, pinMode = true) }
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
 * been unlocked. Biometric-first: the OS prompt is offered automatically and the PIN
 * entry stays one tap away through the always-visible "use PIN" action, so a
 * cancelled, failed or unavailable biometric never locks the parent out.
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
    val biometricOffered = ui.biometricAvailable && promptHost != null

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
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LockGlyph()

            if (!ui.pinMode && biometricOffered) {
                BiometricFirstContent(ui = ui, viewModel = viewModel, promptHost = promptHost)
            } else {
                PinContent(ui = ui, viewModel = viewModel, biometricOffered = biometricOffered, promptHost = promptHost)
            }
        }
    }
}

/** The one piece of chrome: a calm, semantically appropriate lock glyph (decorative). */
@Composable
private fun LockGlyph() {
    Box(
        modifier = Modifier
            .size(QalqonDimens.icon.lg)
            .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            // Decorative: the title and supporting text carry the meaning.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(QalqonDimens.icon.xs),
        )
    }
}

/**
 * The biometric-first screen: state-appropriate title and supporting line, the primary
 * biometric action (which the automatic prompt has already offered once) and the
 * always-present PIN fallback.
 */
@Composable
private fun BiometricFirstContent(
    ui: PinUnlockUiState,
    viewModel: PinUnlockViewModel,
    promptHost: FragmentActivity,
) {
    Spacer(Modifier.height(QalqonDimens.spacing.lg))
    Text(
        stringResource(R.string.lock_biometric_prompt_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(QalqonDimens.spacing.sm))
    Text(
        stringResource(R.string.lock_auth_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    if (ui.biometricFailed) {
        Spacer(Modifier.height(QalqonDimens.spacing.md))
        Text(
            stringResource(R.string.lock_biometric_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(QalqonDimens.spacing.xl))
    // The primary action. The prompt is also auto-offered on entry; this is the
    // retry/re-entry path when the parent dismissed or failed it.
    Button(
        onClick = {
            viewModel.startBiometric()
            launchBiometricPrompt(promptHost, viewModel)
        },
        enabled = !ui.biometricInProgress,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.lock_biometric_button))
    }

    Spacer(Modifier.height(QalqonDimens.spacing.sm))
    // The fallback is always one tap away — never hidden behind the biometric path.
    TextButton(onClick = viewModel::showPin, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.lock_pin_alternative))
    }
}

/**
 * The PIN entry: the existing [AppPinField] and verification flow, with the same
 * error and lockout states as before and a way back to biometrics when the device
 * offers it.
 */
@Composable
private fun PinContent(
    ui: PinUnlockUiState,
    viewModel: PinUnlockViewModel,
    biometricOffered: Boolean,
    promptHost: FragmentActivity?,
) {
    Spacer(Modifier.height(QalqonDimens.spacing.lg))
    Text(
        stringResource(R.string.lock_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(QalqonDimens.spacing.sm))
    Text(
        stringResource(R.string.lock_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(QalqonDimens.spacing.xl))

    AppPinField(
        value = ui.pin,
        onValueChange = viewModel::onPinChange,
        labelRes = R.string.label_pin,
        errorRes = if (ui.wrongPin) R.string.lock_wrong_pin else null,
    )

    if (ui.lockoutRemainingMs > 0L) {
        Spacer(Modifier.height(QalqonDimens.spacing.sm))
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
        Spacer(Modifier.height(QalqonDimens.spacing.sm))
        Text(
            stringResource(R.string.lock_biometric_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(QalqonDimens.spacing.lg))
    AppLoadingButton(
        labelRes = R.string.lock_unlock,
        loading = ui.checking,
        onClick = viewModel::unlock,
    )

    // Return to the biometric path when the device can still offer it.
    if (biometricOffered && promptHost != null) {
        Spacer(Modifier.height(QalqonDimens.spacing.sm))
        TextButton(
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
