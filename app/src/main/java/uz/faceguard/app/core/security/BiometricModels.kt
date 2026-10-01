package uz.faceguard.app.core.security

/**
 * Whether QALQON can offer the **Android system** biometric prompt on this device.
 *
 * Mirrors the meaningful `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
 * outcomes, but as a plain enum so the gate's decisions are JVM-testable without
 * Android. "Not available" is never an error state for the user: the PIN fallback
 * always remains.
 */
enum class BiometricAvailability {
    /** A strong biometric (face unlock or fingerprint) is enrolled and usable. */
    AVAILABLE,

    /** The device has no biometric hardware. */
    NO_HARDWARE,

    /** Hardware exists but is temporarily unusable (sensor busy, screen off, …). */
    HARDWARE_UNAVAILABLE,

    /** Hardware exists but the user has not enrolled any biometric. */
    NONE_ENROLLED,

    /** The device/OS cannot support the requested biometric class. */
    UNSUPPORTED;

    /** True only when the OS prompt may actually be shown. */
    val isUsable: Boolean get() = this == AVAILABLE
}

/**
 * The outcome of an **Android system** biometric authentication.
 *
 * Produced only by `BiometricPrompt`'s callbacks — QALQON never fabricates a
 * success, and never uses its own face recognition for this. Cancellation, failure
 * and errors all leave the UI locked and the PIN available.
 */
sealed interface BiometricAuthResult {

    /** The OS verified the user. The only result that may unlock the UI. */
    data object Success : BiometricAuthResult

    /** The user dismissed the prompt (including "use PIN"). Not an error. */
    data object Cancelled : BiometricAuthResult

    /** A presented biometric did not match; the prompt may still be usable. */
    data object Failed : BiometricAuthResult

    /** A terminal OS error (hardware, lockout, unsupported). PIN stays available. */
    data class Error(val code: Int, val message: String? = null) : BiometricAuthResult
}

/**
 * The pure decision for the biometric button and the automatic prompt.
 *
 * Kept out of the screen so the "when do we offer / auto-show biometrics" rule is
 * unit-testable and cannot drift.
 */
object BiometricPolicy {

    /** The button is shown only when the OS can actually authenticate. */
    fun showBiometricButton(availability: BiometricAvailability): Boolean = availability.isUsable

    /**
     * Auto-show the OS prompt once, only where it can succeed. A device without an
     * enrolled biometric goes straight to the PIN instead of flashing a prompt.
     */
    fun shouldAutoPrompt(availability: BiometricAvailability): Boolean = availability.isUsable
}
