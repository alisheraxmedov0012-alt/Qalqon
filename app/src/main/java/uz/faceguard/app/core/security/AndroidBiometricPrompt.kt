package uz.faceguard.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import uz.faceguard.app.R

/**
 * Shows the **Android system** biometric dialog (face unlock or fingerprint) for the
 * parent-UI unlock.
 *
 * This is the only place QALQON touches biometric authentication, and it deliberately
 * owns no policy: it shows the OS prompt and reports exactly what the OS says. The
 * system renders and localizes its own dialog; QALQON only supplies the title,
 * subtitle and the "use PIN" negative button (which must be localized in the app).
 *
 * QALQON's own camera/ML Kit recognition is **not** involved in app unlock — that
 * pipeline stays dedicated to child protection and parent recognition.
 *
 * `BiometricPrompt` requires a [FragmentActivity] host; nothing is retained here
 * beyond the prompt call, so there is no Activity leak.
 */
class AndroidBiometricPrompt(
    private val activity: FragmentActivity,
    private val title: String,
    private val subtitle: String,
    private val negativeButtonText: String,
) {

    private var prompt: BiometricPrompt? = null

    /**
     * Launches the OS prompt. [onResult] is invoked once with the OS outcome; a
     * `Failed` callback is non-terminal (the system dialog stays open and lets the
     * user retry), so it is reported as progress, never as a success.
     */
    fun show(onResult: (BiometricAuthResult) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(BiometricAuthResult.Success)
            }

            override fun onAuthenticationFailed() {
                // A presented biometric did not match. The system dialog remains open
                // and may still succeed; the UI must not unlock on this.
                onResult(BiometricAuthResult.Failed)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onResult(
                    if (cancelled) {
                        BiometricAuthResult.Cancelled
                    } else {
                        BiometricAuthResult.Error(errorCode, errString.toString())
                    },
                )
            }
        }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        this.prompt = prompt
        prompt.authenticate(promptInfo())
    }

    /** Dismisses an in-flight prompt (e.g. the screen is going away). */
    fun cancel() {
        runCatching { prompt?.cancelAuthentication() }
        prompt = null
    }

    private fun promptInfo(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // Strong biometric only: the device credential is deliberately not offered
            // here, so "PIN" always means QALQON's own account PIN.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(negativeButtonText)
            .build()

    companion object {
        /**
         * Builds a prompt bound to [activity], with the user-visible strings taken
         * from the app's own resources so they follow the selected language.
         */
        fun from(activity: FragmentActivity): AndroidBiometricPrompt {
            val context = activity
            return AndroidBiometricPrompt(
                activity = activity,
                title = context.getString(R.string.lock_biometric_prompt_title),
                subtitle = context.getString(R.string.lock_biometric_prompt_subtitle),
                negativeButtonText = context.getString(R.string.lock_pin_alternative),
            )
        }
    }
}
