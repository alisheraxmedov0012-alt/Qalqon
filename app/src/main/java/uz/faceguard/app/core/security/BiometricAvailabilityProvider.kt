package uz.faceguard.app.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports whether the **Android system** biometric prompt can be used.
 *
 * Only the availability question is asked here, because it needs just a `Context`;
 * the prompt itself needs the Activity and lives in `AndroidBiometricPrompt`. Split
 * this way, the gate's decisions stay JVM-testable behind this tiny seam.
 */
interface BiometricAvailabilityProvider {
    fun availability(): BiometricAvailability
}

/**
 * `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` — strong biometrics only
 * (face unlock or fingerprint, chosen by Android), never the device credential: the
 * QALQON PIN stays QALQON's own account authentication.
 *
 * Every failure mode (no hardware, nothing enrolled, hardware busy, unsupported)
 * simply means "offer PIN only"; it is never surfaced as an app error.
 */
@Singleton
class AndroidBiometricAvailabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : BiometricAvailabilityProvider {

    override fun availability(): BiometricAvailability = runCatching {
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.UNSUPPORTED
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.UNSUPPORTED
            else -> BiometricAvailability.UNSUPPORTED
        }
    }.getOrDefault(BiometricAvailability.UNSUPPORTED)

    private companion object {
        /** Strong biometrics only; the QALQON PIN remains the fallback (not the device PIN). */
        const val BIOMETRIC_STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
