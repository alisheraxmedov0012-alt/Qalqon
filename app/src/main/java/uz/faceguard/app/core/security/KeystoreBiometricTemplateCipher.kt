package uz.faceguard.app.core.security

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uz.faceguard.app.domain.security.BiometricTemplateCipher
import uz.faceguard.app.domain.security.TemplateRecovery

/**
 * Phase 12: minimal, deterministic security state (§27).
 *
 * - SECURE: encryption is available and all read payloads authenticated.
 * - MIGRATION_PENDING: legacy plaintext biometric data was seen and still needs
 *   re-encryption (the app keeps working; the sweep runs off the main thread).
 * - RECOVERY_REQUIRED: a payload could not be decrypted (invalidated key or broken
 *   ciphertext). The biometric template must not be trusted; re-enrollment is needed.
 * - CORRUPTED: malformed security metadata/payload.
 *
 * RECOVERY_REQUIRED/CORRUPTED are sticky until the user re-enrolls or resets, so a
 * single successful read cannot hide a real integrity problem.
 */
enum class SecurityState { SECURE, MIGRATION_PENDING, RECOVERY_REQUIRED, CORRUPTED }

/** Process-scoped holder; deliberately not persisted (no secrets, no PII). */
class SecurityStateHolder {

    private val _state = MutableStateFlow(SecurityState.SECURE)
    val state: StateFlow<SecurityState> = _state

    @Synchronized
    fun onLegacyDetected() {
        if (_state.value == SecurityState.SECURE) _state.value = SecurityState.MIGRATION_PENDING
    }

    @Synchronized
    fun onRecoveryRequired() {
        _state.value = SecurityState.RECOVERY_REQUIRED
    }

    @Synchronized
    fun onCorrupted() {
        _state.value = SecurityState.CORRUPTED
    }

    @Synchronized
    fun onMigrationComplete() {
        if (_state.value == SecurityState.MIGRATION_PENDING) _state.value = SecurityState.SECURE
    }

    @Synchronized
    fun reset() {
        _state.value = SecurityState.SECURE
    }
}

/**
 * Keystore-backed template cipher.
 *
 * Stored form is `v1:<base64(envelope)>`. Legacy rows hold raw base64 of the
 * float vector and are reported as [TemplateRecovery.LegacyPlaintext] so the
 * migration sweep can encrypt them without losing data — but an *encrypted*
 * payload that fails authentication is never treated as legacy and never
 * downgraded to plaintext.
 */
class KeystoreBiometricTemplateCipher(
    private val crypto: SecureCrypto,
    private val securityState: SecurityStateHolder,
) : BiometricTemplateCipher {

    override fun protect(plainRef: String): String? = when (val result = crypto.encrypt(plainRef.toByteArray())) {
        is CryptoResult.Success -> PREFIX + Base64.getEncoder().encodeToString(result.bytes)
        else -> {
            securityState.onRecoveryRequired()
            null
        }
    }

    override fun recover(storedRef: String): TemplateRecovery {
        if (!isProtected(storedRef)) {
            // Legacy plaintext rows predate Phase 12; they stay readable so no
            // enrollment data is lost, and are re-encrypted by the sweep.
            securityState.onLegacyDetected()
            return TemplateRecovery.LegacyPlaintext(storedRef)
        }
        val payload = runCatching {
            Base64.getDecoder().decode(storedRef.removePrefix(PREFIX))
        }.getOrElse {
            securityState.onCorrupted()
            return TemplateRecovery.Unavailable
        }
        return when (val result = crypto.decrypt(payload)) {
            is CryptoResult.Success -> TemplateRecovery.Recovered(String(result.bytes, StandardCharsets.UTF_8))
            CryptoResult.Malformed -> {
                securityState.onCorrupted()
                TemplateRecovery.Unavailable
            }

            else -> {
                securityState.onRecoveryRequired()
                TemplateRecovery.Unavailable
            }
        }
    }

    override fun isProtected(storedRef: String): Boolean = storedRef.startsWith(PREFIX)

    companion object {
        /** Version prefix of the stored envelope (matches [AesGcmSecureCrypto.VERSION]). */
        const val PREFIX = "v1:"
    }
}
