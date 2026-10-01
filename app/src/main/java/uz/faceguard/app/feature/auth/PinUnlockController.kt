package uz.faceguard.app.feature.auth

import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.security.BiometricAuthResult
import uz.faceguard.app.core.security.BiometricAvailability
import uz.faceguard.app.core.security.BiometricAvailabilityProvider
import uz.faceguard.app.core.security.BiometricPolicy
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinVerification

/** What the PIN-unlock screen should show after an attempt. */
sealed interface PinUnlockResult {
    /** The PIN matched: the UI is now unlocked and the caller may continue to Home. */
    data object Unlocked : PinUnlockResult

    /** Wrong PIN, no lockout active. */
    data object WrongPin : PinUnlockResult

    /** Too many attempts: show the remaining wait. */
    data class LockedOut(val remainingMillis: Long) : PinUnlockResult
}

/**
 * Android-free logic for the startup PIN gate.
 *
 * It performs no cryptography and no comparison of its own: the PIN is handed to
 * the **existing** [AccountRepository.verifyPin], which owns PBKDF2 verification,
 * the account salt, constant-time comparison and the escalating lockout. This class
 * only maps that typed outcome onto screen state and, on success, marks the process
 * unlocked via [AppLockState].
 *
 * Deliberately free of Compose/Android so the whole gate is JVM-testable against a
 * fake repository (the Hilt ViewModel is a thin wrapper), matching the existing
 * controller pattern in this project.
 */
class PinUnlockController(
    private val accountRepository: AccountRepository,
    private val appLockState: AppLockState,
    private val biometricAvailability: BiometricAvailabilityProvider,
) {

    /**
     * Verifies [pin] through the existing repository and, when it matches, unlocks
     * the UI for this process. The PIN is never stored, logged or retained here.
     */
    suspend fun submit(pin: String): PinUnlockResult =
        when (val verification = accountRepository.verifyPin(pin)) {
            PinVerification.Success -> {
                appLockState.onAuthenticated()
                PinUnlockResult.Unlocked
            }

            PinVerification.InvalidPin -> PinUnlockResult.WrongPin

            is PinVerification.LockedOut ->
                PinUnlockResult.LockedOut(verification.remainingMillis)
        }

    /** Whether the Android biometric prompt may be offered on this device. */
    fun biometricAvailability(): BiometricAvailability = biometricAvailability.availability()

    /** Whether the biometric button should be shown / auto-prompted. */
    fun shouldOfferBiometric(): Boolean =
        BiometricPolicy.showBiometricButton(biometricAvailability())

    /**
     * Applies an **Android system** biometric outcome.
     *
     * Only [BiometricAuthResult.Success] unlocks — which can only be produced by
     * `BiometricPrompt`, so QALQON's own face recognition can never unlock the UI.
     * Cancellation, failure and errors leave the app locked and the PIN available; a
     * device without biometrics never reaches this at all.
     *
     * Returns the unlock outcome, or `null` when the UI stays locked.
     */
    fun onBiometricResult(result: BiometricAuthResult): PinUnlockResult? =
        when (result) {
            BiometricAuthResult.Success -> {
                appLockState.onAuthenticated()
                PinUnlockResult.Unlocked
            }
            // Never unlock on anything else.
            BiometricAuthResult.Cancelled,
            BiometricAuthResult.Failed,
            is BiometricAuthResult.Error,
            -> null
        }

    /** The remaining wait as `M:SS`, for the lockout message. */
    fun formatRemaining(remainingMillis: Long): String =
        uz.faceguard.app.domain.security.formatLockoutRemaining(remainingMillis)

    /** Exposed for tests/documentation: the lockout threshold is the existing policy. */
    val maxAttempts: Int get() = PinAttemptPolicy.MAX_ATTEMPTS
}
