package uz.faceguard.app.security

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.security.PinHasher
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinAttemptState

/**
 * Phase 12 PIN tests (JVM): derivation, legacy upgrade, safe handling of malformed
 * stored data, and the lockout policy. No PIN or hash is ever printed.
 */
class PinSecurityTest {

    private val pin = "482913"
    private val salt = "00112233445566778899aabbccddeeff"

    private fun legacySha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- hashing ------------------------------------------------------------

    @Test
    fun theStoredValueIsNotThePlaintextPin() {
        val stored = PinHasher.hash(pin, salt)

        assertFalse(stored.contains(pin))
        assertTrue("versioned envelope expected", stored.startsWith("pbkdf2\$sha256\$"))
    }

    @Test
    fun theSamePinWithDifferentSaltsProducesDifferentHashes() {
        val a = PinHasher.hash(pin, salt)
        val b = PinHasher.hash(pin, PinHasher.randomSalt())

        assertNotEquals(a, b)
    }

    @Test
    fun randomSaltsAreUniqueAndHex() {
        val salts = (1..50).map { PinHasher.randomSalt() }
        assertEquals(50, salts.toSet().size)
        assertTrue(salts.all { it.length == 32 && it.all { c -> c.isDigit() || c in 'a'..'f' } })
    }

    @Test
    fun theCorrectPinVerifiesAndAWrongOneDoesNot() {
        val stored = PinHasher.hash(pin, salt)

        assertTrue(PinHasher.verify(pin, stored, salt))
        assertFalse(PinHasher.verify("000000", stored, salt))
        assertFalse(PinHasher.verify("", stored, salt))
    }

    @Test
    fun aLegacyHashStillVerifiesSoExistingAccountsKeepWorking() {
        val legacyHash = legacySha256(salt + pin)

        assertTrue(PinHasher.verify(pin, legacyHash, salt))
        assertFalse(PinHasher.verify("111111", legacyHash, salt))
        assertTrue("a legacy hash must be flagged for upgrade", PinHasher.needsUpgrade(legacyHash))
        assertFalse(PinHasher.needsUpgrade(PinHasher.hash(pin, salt)))
    }

    @Test
    fun malformedStoredDataIsRejectedWithoutCrashing() {
        listOf(
            "",
            "pbkdf2",
            "pbkdf2\$sha256",
            "pbkdf2\$sha256\$notanumber\$salt\$hash",
            "pbkdf2\$sha256\$0\$salt\$hash",
            "pbkdf2\$sha256\$120000\$\$",
        ).forEach { malformed ->
            assertFalse("malformed '$malformed' must not verify", PinHasher.verify(pin, malformed, salt))
        }
    }

    // ---- attempt policy -----------------------------------------------------

    @Test
    fun failuresBelowTheLimitDoNotLock() {
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS - 1) { state = PinAttemptPolicy.onFailure(state, 1_000L) }

        assertFalse(state.isLocked(1_000L))
        assertEquals(0L, state.lockedUntilMillis)
        assertEquals(PinAttemptPolicy.MAX_ATTEMPTS - 1, state.failedCount)
    }

    @Test
    fun reachingTheLimitStartsATemporaryLockout() {
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 1_000L) }

        assertTrue(state.isLocked(1_000L))
        assertEquals(1_000L + PinAttemptPolicy.LOCKOUT_STEPS_MS.first(), state.lockedUntilMillis)
        assertFalse("the lockout is temporary", state.isLocked(state.lockedUntilMillis))
    }

    @Test
    fun repeatedLockoutsEscalateButAreCapped() {
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS * 10) { state = PinAttemptPolicy.onFailure(state, 1_000L) }

        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, state.lockedUntilMillis - 1_000L)
        assertFalse("escalation must never be permanent", state.isLocked(1_000_000_000L))
    }

    @Test
    fun aSuccessfulAuthenticationResetsTheCounter() {
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 1_000L) }

        val reset = PinAttemptPolicy.onSuccess()

        assertEquals(0, reset.failedCount)
        assertEquals(0L, reset.lockedUntilMillis)
        assertFalse(reset.isLocked(1_000L))
    }

    @Test
    fun remainingAttemptsNeverGoNegative() {
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 1_000L) }

        assertTrue(PinAttemptPolicy.remainingAttempts(state) in 1..PinAttemptPolicy.MAX_ATTEMPTS)
    }
}
