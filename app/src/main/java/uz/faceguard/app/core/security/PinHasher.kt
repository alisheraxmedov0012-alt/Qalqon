package uz.faceguard.app.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Phase 12 PIN hardening.
 *
 * The previous scheme was a single SHA-256 round over `salt + pin`, which is fast
 * enough to brute-force offline. This uses PBKDF2-HMAC-SHA256 (JCA, no custom
 * cryptography) with a per-account random salt, and verifies *legacy* hashes too so
 * existing accounts keep working — a legacy hash is transparently upgraded to the
 * PBKDF2 envelope on the next successful verification.
 *
 * Stored value: `pbkdf2$sha256$<iterations>$<saltHex>$<hashHex>` in the existing
 * `pinHash` column (no schema change). The raw PIN is never stored, logged or
 * included in events.
 */
object PinHasher {

    private const val SCHEME = "pbkdf2"
    private const val DIGEST = "sha256"
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SEPARATOR = "\$"

    /** Deliberate cost: slow for attackers, acceptable for a single local unlock. */
    const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun randomSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    /** PBKDF2 envelope for a PIN and its (hex) salt. */
    fun hash(pin: String, saltHex: String): String {
        val derived = derive(pin, saltHex, ITERATIONS)
            ?: throw IllegalArgumentException(
                "salt is not valid hex; a malformed salt is a programming error, never zero-filled",
            )
        return listOf(SCHEME, DIGEST, ITERATIONS.toString(), saltHex, derived.toHex()).joinToString(SEPARATOR)
    }

    /**
     * Verifies [pin] against a stored hash. Supports both the PBKDF2 envelope and
     * the legacy salted SHA-256 value (compared in constant time).
     *
     * Malformed stored data (including a malformed salt in the envelope) can only
     * ever return `false` — verification of untrusted input never throws and never
     * substitutes a zero-filled salt.
     */
    fun verify(pin: String, storedHash: String, legacySaltHex: String): Boolean {
        if (!storedHash.startsWith("$SCHEME$SEPARATOR")) {
            // Legacy single-round hash (upgraded after a successful verification).
            return constantTimeEquals(legacySha256(legacySaltHex + pin), storedHash)
        }
        val parts = storedHash.split(SEPARATOR)
        if (parts.size != 5) return false
        val iterations = parts[2].toIntOrNull() ?: return false
        val salt = parts[3]
        if (iterations <= 0 || salt.isBlank() || parts[4].isBlank()) return false
        val derived = derive(pin, salt, iterations) ?: return false
        return constantTimeEquals(derived.toHex(), parts[4])
    }

    /** True when [storedHash] is still the legacy scheme and should be upgraded. */
    fun needsUpgrade(storedHash: String): Boolean = !storedHash.startsWith("$SCHEME$SEPARATOR")

    /**
     * PBKDF2 derivation, or `null` when [saltHex] is not valid hex.
     *
     * Returning null (rather than substituting zero bytes) is what keeps a malformed
     * salt from silently producing a guessable hash.
     */
    private fun derive(pin: String, saltHex: String, iterations: Int): ByteArray? {
        val salt = fromHex(saltHex) ?: return null
        if (salt.isEmpty()) return null
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Exact legacy scheme (single SHA-256 over `saltHex + pin`), for upgrade only. */
    private fun legacySha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    /** Byte-wise comparison that does not leak where a mismatch happened. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Strict hex decoding: `null` for an empty/odd-length string or any character
     * outside `[0-9a-fA-F]`. Deliberately **no** zero-fill — a malformed salt must
     * never be silently replaced with a predictable value.
     */
    private fun fromHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
