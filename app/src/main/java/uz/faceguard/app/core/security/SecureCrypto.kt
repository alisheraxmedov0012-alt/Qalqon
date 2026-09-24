package uz.faceguard.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phase 12: at-rest protection for sensitive biometric material.
 *
 * The key lives in the Android Keystore (never in Room, never in DataStore, never
 * hardcoded, never logged). The payload format is explicit and versioned, uses a
 * fresh random nonce per encryption, and is authenticated (AES-GCM), so corruption
 * or tampering is detected instead of silently accepted.
 *
 * There is deliberately **no plaintext fallback**: a decryption failure is a
 * data-integrity/security failure that the caller must surface.
 */

/** Outcome of a cryptographic operation. Never contains key material. */
sealed interface CryptoResult {
    data class Success(val bytes: ByteArray) : CryptoResult

    /** No usable key (e.g. Keystore invalidated it). Requires re-enrollment. */
    data object KeyUnavailable : CryptoResult

    /** Authentication/decryption failed: wrong key, tampered or corrupt payload. */
    data object Corrupted : CryptoResult

    /** A newer/unknown payload version than this build understands. */
    data object UnsupportedVersion : CryptoResult

    /** Structurally invalid payload (bad magic/lengths). */
    data object Malformed : CryptoResult
}

interface SecureCrypto {
    fun encrypt(plaintext: ByteArray): CryptoResult
    fun decrypt(payload: ByteArray): CryptoResult
}

/** Supplies the AES key; the Keystore implementation is the only production one. */
fun interface SecureKeyProvider {
    /** Null when no usable key exists. */
    fun key(): SecretKey?
}

/**
 * AES-GCM envelope:
 * `magic(4) | version(1) | algorithm(1) | ivLength(1) | iv(12) | ciphertext+tag`.
 *
 * Only JCA/JDK primitives are used (no custom cryptography), which also makes the
 * envelope logic unit-testable on the JVM with an injected AES key.
 */
class AesGcmSecureCrypto(
    private val keyProvider: SecureKeyProvider,
) : SecureCrypto {

    override fun encrypt(plaintext: ByteArray): CryptoResult {
        val key = keyProvider.key() ?: return CryptoResult.KeyUnavailable
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // AndroidKeyStore AES/GCM keys require randomized encryption: the
            // platform generates a fresh nonce (Java's SunJCE does the same), so the
            // nonce is always random and never caller-chosen.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return CryptoResult.KeyUnavailable
            val ciphertext = cipher.doFinal(plaintext)

            val out = ByteArray(MAGIC.size + 3 + iv.size + ciphertext.size)
            MAGIC.copyInto(out, 0)
            out[MAGIC.size] = VERSION
            out[MAGIC.size + 1] = ALGORITHM_AES_GCM
            out[MAGIC.size + 2] = iv.size.toByte()
            iv.copyInto(out, MAGIC.size + 3)
            ciphertext.copyInto(out, MAGIC.size + 3 + iv.size)
            CryptoResult.Success(out)
        } catch (t: Throwable) {
            // Never log key material or the failing payload.
            CryptoResult.KeyUnavailable
        }
    }

    override fun decrypt(payload: ByteArray): CryptoResult {
        val headerSize = MAGIC.size + 3
        if (payload.size < headerSize + IV_LENGTH_BYTES) return CryptoResult.Malformed
        for (i in MAGIC.indices) if (payload[i] != MAGIC[i]) return CryptoResult.Malformed
        if (payload[MAGIC.size] != VERSION) return CryptoResult.UnsupportedVersion
        if (payload[MAGIC.size + 1] != ALGORITHM_AES_GCM) return CryptoResult.Malformed

        val ivLength = payload[MAGIC.size + 2].toInt()
        if (ivLength <= 0 || payload.size < headerSize + ivLength) return CryptoResult.Malformed
        val iv = payload.copyOfRange(headerSize, headerSize + ivLength)
        val ciphertext = payload.copyOfRange(headerSize + ivLength, payload.size)

        val key = keyProvider.key() ?: return CryptoResult.KeyUnavailable
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            CryptoResult.Success(cipher.doFinal(ciphertext))
        } catch (t: Throwable) {
            // Wrong key / tampered tag / truncated ciphertext.
            CryptoResult.Corrupted
        }
    }

    companion object {
        val MAGIC = byteArrayOf('Q'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
        const val VERSION: Byte = 1
        const val ALGORITHM_AES_GCM: Byte = 1
        const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Android Keystore-backed AES-256-GCM key.
 *
 * The key is generated on first use and never leaves the Keystore. If the platform
 * invalidates it (e.g. device credential reset), [key] just returns null — this
 * class never regenerates a key that could decrypt nothing, and never logs it.
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = DEFAULT_KEY_ALIAS,
) : SecureKeyProvider {

    override fun key(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate(alias)
    }.getOrNull()

    /** Removes the key (explicit, scoped wipe only). */
    fun deleteKey(): Boolean = runCatching {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            true
        } else {
            false
        }
    }.getOrDefault(false)

    private fun generate(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "qalqon.biometric.v1"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
