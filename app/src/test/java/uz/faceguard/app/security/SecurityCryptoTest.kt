package uz.faceguard.app.security

import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.security.AesGcmSecureCrypto
import uz.faceguard.app.core.security.CryptoResult
import uz.faceguard.app.core.security.KeystoreBiometricTemplateCipher
import uz.faceguard.app.core.security.SecurityState
import uz.faceguard.app.core.security.SecurityStateHolder
import uz.faceguard.app.domain.security.TemplateRecovery

/**
 * Phase 12 crypto tests (JVM). The envelope logic is exercised with a real AES key
 * generated locally; the production key comes from the Android Keystore (covered by
 * the instrumented test). No key material is ever printed.
 */
private fun aesKey(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

class SecurityCryptoTest {

    private fun cryptoWith(key: SecretKey?) = AesGcmSecureCrypto({ key })

    private fun encryptOrFail(crypto: AesGcmSecureCrypto, value: String): ByteArray {
        val result = crypto.encrypt(value.toByteArray())
        assertTrue("expected encryption, got $result", result is CryptoResult.Success)
        return (result as CryptoResult.Success).bytes
    }

    @Test
    fun roundTripRecoversThePlaintext() {
        val crypto = cryptoWith(aesKey())
        val payload = encryptOrFail(crypto, "embedding-bytes")

        val decrypted = crypto.decrypt(payload)

        assertTrue(decrypted is CryptoResult.Success)
        assertEquals("embedding-bytes", String((decrypted as CryptoResult.Success).bytes))
    }

    @Test
    fun separateEncryptionsUseAFreshNonce() {
        val crypto = cryptoWith(aesKey())

        val first = encryptOrFail(crypto, "same-value")
        val second = encryptOrFail(crypto, "same-value")

        assertFalse("a reused nonce would leak equality", first.contentEquals(second))
        // The nonce sits after magic+version+algorithm+ivLength.
        val ivOffset = AesGcmSecureCrypto.MAGIC.size + 3
        val firstIv = first.copyOfRange(ivOffset, ivOffset + AesGcmSecureCrypto.IV_LENGTH_BYTES)
        val secondIv = second.copyOfRange(ivOffset, ivOffset + AesGcmSecureCrypto.IV_LENGTH_BYTES)
        assertFalse(firstIv.contentEquals(secondIv))
    }

    @Test
    fun aWrongKeyCannotDecrypt() {
        val payload = encryptOrFail(cryptoWith(aesKey()), "secret")

        assertEquals(CryptoResult.Corrupted, cryptoWith(aesKey()).decrypt(payload))
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val crypto = cryptoWith(aesKey())
        val payload = encryptOrFail(crypto, "secret")
        payload[payload.lastIndex] = (payload[payload.lastIndex] + 1).toByte()

        assertEquals(CryptoResult.Corrupted, crypto.decrypt(payload))
    }

    @Test
    fun modifiedIvFailsAuthentication() {
        val crypto = cryptoWith(aesKey())
        val payload = encryptOrFail(crypto, "secret")
        val ivOffset = AesGcmSecureCrypto.MAGIC.size + 3
        payload[ivOffset] = (payload[ivOffset] + 1).toByte()

        assertEquals(CryptoResult.Corrupted, crypto.decrypt(payload))
    }

    @Test
    fun malformedPayloadIsRejected() {
        val crypto = cryptoWith(aesKey())
        val payload = encryptOrFail(crypto, "secret")

        assertEquals(CryptoResult.Malformed, crypto.decrypt(ByteArray(0)))
        assertEquals(CryptoResult.Malformed, crypto.decrypt(byteArrayOf(1, 2, 3)))
        // Valid magic, but the algorithm byte is unknown.
        val badAlgorithm = payload.copyOf()
        badAlgorithm[AesGcmSecureCrypto.MAGIC.size + 1] = 99
        assertEquals(CryptoResult.Malformed, crypto.decrypt(badAlgorithm))
    }

    @Test
    fun anUnsupportedVersionIsRefusedNotGuessed() {
        val crypto = cryptoWith(aesKey())
        val payload = encryptOrFail(crypto, "secret")
        payload[AesGcmSecureCrypto.MAGIC.size] = 42

        assertEquals(CryptoResult.UnsupportedVersion, crypto.decrypt(payload))
    }

    @Test
    fun aMissingKeyFailsSafelyAndNeverReturnsPlaintext() {
        val payload = encryptOrFail(cryptoWith(aesKey()), "secret")

        assertEquals(CryptoResult.KeyUnavailable, cryptoWith(null).encrypt("secret".toByteArray()))
        assertEquals(CryptoResult.KeyUnavailable, cryptoWith(null).decrypt(payload))
    }

    @Test
    fun corruptedBytesNeverDecryptToSomethingElse() {
        val crypto = cryptoWith(aesKey())
        val payload = encryptOrFail(crypto, "secret")
        val random = SecureRandom()
        repeat(20) {
            val tampered = payload.copyOf()
            tampered[random.nextInt(tampered.size)] = random.nextInt(256).toByte()
            val result = crypto.decrypt(tampered)
            assertTrue("tampering must never yield plaintext", result !is CryptoResult.Success)
        }
    }

    // ---- template cipher ----------------------------------------------------

    private class Harness {
        val state = SecurityStateHolder()
        private val key = aesKey()
        val cipher = KeystoreBiometricTemplateCipher(AesGcmSecureCrypto({ key }), state)
    }

    @Test
    fun aProtectedTemplateIsEncryptedAndRecoverable() {
        val harness = Harness()
        val plain = "ZmFrZS1lbWJlZGRpbmc="

        val stored = harness.cipher.protect(plain)

        assertNotNull(stored)
        assertTrue("stored form must be an envelope", harness.cipher.isProtected(stored!!))
        assertFalse("the stored value must not contain the plaintext", stored.contains(plain))
        assertEquals(TemplateRecovery.Recovered(plain), harness.cipher.recover(stored))
        assertEquals(SecurityState.SECURE, harness.state.state.value)
    }

    @Test
    fun aLegacyPlaintextRowIsDetectedForMigration() {
        val harness = Harness()

        val recovery = harness.cipher.recover("ZmFrZS1sZWdhY3k=")

        assertEquals(TemplateRecovery.LegacyPlaintext("ZmFrZS1sZWdhY3k="), recovery)
        assertEquals(SecurityState.MIGRATION_PENDING, harness.state.state.value)
    }

    @Test
    fun aBrokenEnvelopeRequiresRecoveryAndNeverFallsBackToPlaintext() {
        val harness = Harness()
        val stored = harness.cipher.protect("ZmFrZQ==")!!
        val tampered = stored.dropLast(4) + "AAAA"

        val recovery = harness.cipher.recover(tampered)

        assertEquals(TemplateRecovery.Unavailable, recovery)
        assertEquals(SecurityState.RECOVERY_REQUIRED, harness.state.state.value)
    }

    @Test
    fun recoveryRequiredIsSticky() {
        val state = SecurityStateHolder()
        state.onRecoveryRequired()
        state.onLegacyDetected()
        state.onMigrationComplete()

        assertEquals(SecurityState.RECOVERY_REQUIRED, state.state.value)
        state.reset()
        assertEquals(SecurityState.SECURE, state.state.value)
    }

    @Test
    fun migrationPendingClearsOnlyAfterAMigration() {
        val state = SecurityStateHolder()
        state.onLegacyDetected()
        assertEquals(SecurityState.MIGRATION_PENDING, state.state.value)

        state.onMigrationComplete()

        assertEquals(SecurityState.SECURE, state.state.value)
    }
}
