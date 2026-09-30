package uz.faceguard.app.security

import uz.faceguard.app.domain.security.BiometricTemplateCipher
import uz.faceguard.app.domain.security.TemplateRecovery

/**
 * Test double for suites that are not about encryption: it behaves like the
 * pre-Phase-12 world (templates pass through unchanged) so those tests keep
 * asserting their own behaviour. The real cipher is exercised by
 * [SecurityPersistenceTest] with the actual Android Keystore.
 */
object PassthroughTemplateCipher : BiometricTemplateCipher {
    override fun protect(plainRef: String): String = plainRef

    override fun recover(storedRef: String): TemplateRecovery = TemplateRecovery.LegacyPlaintext(storedRef)

    override fun isProtected(storedRef: String): Boolean = false
}
