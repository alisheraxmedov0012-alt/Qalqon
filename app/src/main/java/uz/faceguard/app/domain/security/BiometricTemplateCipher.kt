package uz.faceguard.app.domain.security

/**
 * Phase 12: the seam between the biometric pipeline and encrypted storage.
 *
 * Repositories keep the *domain* template reference in its plaintext form (the
 * recognizer needs it transiently) and use this interface to protect it at rest.
 * Nothing else in the app knows about the envelope or the key.
 */
sealed interface TemplateRecovery {
    /** Already-plaintext legacy reference: usable, but it must be re-encrypted. */
    data class LegacyPlaintext(val plainRef: String) : TemplateRecovery

    /** Successfully decrypted; [plainRef] is only held transiently by the caller. */
    data class Recovered(val plainRef: String) : TemplateRecovery

    /**
     * The stored template cannot be used (unusable key or broken/tampered payload).
     * Callers must fail closed: never fall back to plaintext, never guess.
     */
    data object Unavailable : TemplateRecovery
}

interface BiometricTemplateCipher {

    /** Encrypts a plaintext reference for storage; null when protection is unavailable. */
    fun protect(plainRef: String): String?

    /** Reads a stored reference (encrypted or legacy plaintext) safely. */
    fun recover(storedRef: String): TemplateRecovery

    /** True when [storedRef] is already an encrypted envelope. */
    fun isProtected(storedRef: String): Boolean
}
