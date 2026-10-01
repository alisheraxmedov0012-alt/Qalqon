package uz.faceguard.app.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.security.BiometricAvailability
import uz.faceguard.app.core.security.BiometricAvailabilityProvider
import uz.faceguard.app.core.security.PinHasher
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinAttemptState
import uz.faceguard.app.domain.security.PinVerification
import uz.faceguard.app.feature.auth.PinUnlockController
import uz.faceguard.app.feature.auth.PinUnlockResult

/**
 * The startup PIN gate.
 *
 * The controller delegates verification to the existing [AccountRepository]
 * implementation, so this test drives the *gate* (lock/unlock state and the typed
 * outcome mapping) while reusing the real PBKDF2 hasher and the real lockout policy
 * in the fake — wrong PIN must never unlock, and the existing escalating lockout must
 * reach the screen.
 */
class PinUnlockGateTest {

    /**
     * Minimal repository double that keeps the production security semantics: the
     * hash is a real PBKDF2 envelope, comparison goes through [PinHasher], and
     * failures are counted with the real [PinAttemptPolicy].
     */
    private class FakeAccountRepository(
        private val pin: String,
        private var nowMillis: Long = 1_000L,
    ) : AccountRepository {
        private val salt = PinHasher.randomSalt()
        private val hash = PinHasher.hash(pin, salt)
        private var attempts = PinAttemptState()
        var verifyCalls = 0
            private set

        override val currentAccountId: Flow<Long?> = MutableStateFlow(1L)

        override suspend fun verifyPin(pin: String): PinVerification {
            verifyCalls++
            if (attempts.isLocked(nowMillis)) {
                return PinVerification.LockedOut(attempts.remainingLockMillis(nowMillis))
            }
            if (!PinHasher.verify(pin, hash, salt)) {
                attempts = PinAttemptPolicy.onFailure(attempts, nowMillis)
                return PinAttemptPolicy.outcomeForFailure(attempts, nowMillis)
            }
            attempts = PinAttemptPolicy.onSuccess()
            return PinVerification.Success
        }

        override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
            throw UnsupportedOperationException("not used")

        override suspend fun login(phoneNumber: String, pin: String): AuthResult =
            throw UnsupportedOperationException("not used")

        override suspend fun getCurrentAccount(): UserAccount? = null

        override suspend fun logout() = Unit
    }

    /** No device biometrics in this test; the biometric path is covered separately. */
    private val noBiometric = object : BiometricAvailabilityProvider {
        override fun availability(): BiometricAvailability = BiometricAvailability.NO_HARDWARE
    }

    private fun gate(pin: String = "1234"): Triple<PinUnlockController, AppLockState, FakeAccountRepository> {
        val repository = FakeAccountRepository(pin)
        val lock = AppLockState()
        return Triple(PinUnlockController(repository, lock, noBiometric), lock, repository)
    }

    // The UI starts locked on every process start.
    @Test
    fun theUiStartsLocked() {
        assertFalse(AppLockState().isUnlocked())
    }

    // B. Correct PIN unlocks; the outcome asks the caller to continue to Home.
    @Test
    fun theCorrectPinUnlocksTheUi() = runBlocking {
        val (controller, lock, _) = gate()

        val result = controller.submit("1234")

        assertEquals(PinUnlockResult.Unlocked, result)
        assertTrue("the UI must be unlocked after a correct PIN", lock.isUnlocked())
    }

    // B. Wrong PIN stays locked and does not unlock the UI.
    @Test
    fun aWrongPinLeavesTheUiLocked() = runBlocking {
        val (controller, lock, _) = gate()

        val result = controller.submit("9999")

        assertEquals(PinUnlockResult.WrongPin, result)
        assertFalse("a wrong PIN must not unlock the UI", lock.isUnlocked())
    }

    @Test
    fun severalWrongPinsStillLeaveTheUiLocked() = runBlocking {
        val (controller, lock, _) = gate()

        repeat(4) { assertEquals(PinUnlockResult.WrongPin, controller.submit("0000")) }

        assertFalse(lock.isUnlocked())
    }

    // B. The existing lockout is preserved and surfaces on the screen.
    @Test
    fun theExistingLockoutIsReportedAndBlocksFurtherAttempts() = runBlocking {
        val (controller, lock, repository) = gate()

        // The lockout trips on the fifth failure (PinAttemptPolicy.MAX_ATTEMPTS).
        val outcomes = (1..PinAttemptPolicy.MAX_ATTEMPTS).map { controller.submit("0000") }

        assertTrue(
            "the tipping attempt must report the lockout",
            outcomes.last() is PinUnlockResult.LockedOut,
        )
        assertTrue((outcomes.last() as PinUnlockResult.LockedOut).remainingMillis > 0L)
        assertFalse(lock.isUnlocked())

        // While locked, even the correct PIN cannot unlock.
        val whileLocked = controller.submit("1234")
        assertTrue(whileLocked is PinUnlockResult.LockedOut)
        assertFalse("a locked account must not unlock the UI", lock.isUnlocked())
    }

    @Test
    fun theGateDelegatesToTheRepositoryAndNeverComparesThePinItself() = runBlocking {
        val (controller, _, repository) = gate()

        controller.submit("1234")

        assertEquals("the existing verification must be used exactly once", 1, repository.verifyCalls)
    }

    @Test
    fun theLockCanBeClearedAgainOnLogout() {
        val lock = AppLockState()
        lock.onAuthenticated()
        assertTrue(lock.isUnlocked())

        lock.lock()

        assertFalse("logout must re-lock the UI", lock.isUnlocked())
    }

    @Test
    fun theUnlockFlagIsNotPersistedAnywhereItCouldBeRestored() {
        // A fresh instance is locked; there is no storage behind it, so process
        // death cannot restore an unlocked UI.
        assertFalse(AppLockState().isUnlocked())
    }
}
