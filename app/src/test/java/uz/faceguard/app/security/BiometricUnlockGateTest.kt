package uz.faceguard.app.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.security.BiometricAuthResult
import uz.faceguard.app.core.security.BiometricAvailability
import uz.faceguard.app.core.security.BiometricAvailabilityProvider
import uz.faceguard.app.core.security.BiometricPolicy
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
 * Android biometric unlock with the PIN fallback.
 *
 * The biometric result can only come from the OS prompt, so this pins the security
 * property that matters: **only `Success` unlocks**, and every failure mode falls
 * back to the unchanged PIN path. The real PBKDF2 hasher and the real lockout policy
 * are used in the fake repository, so the fallback is genuinely the production one.
 */
class BiometricUnlockGateTest {

    private class FakeAvailability(private val value: BiometricAvailability) :
        BiometricAvailabilityProvider {
        override fun availability(): BiometricAvailability = value
    }

    /** Real PBKDF2 + real lockout, so the PIN fallback under test is the production one. */
    private class FakeAccountRepository(private val pin: String) : AccountRepository {
        private val salt = PinHasher.randomSalt()
        private val hash = PinHasher.hash(pin, salt)
        private var attempts = PinAttemptState()

        override val currentAccountId: Flow<Long?> = MutableStateFlow(1L)

        override suspend fun verifyPin(pin: String): PinVerification {
            if (attempts.isLocked(1_000L)) {
                return PinVerification.LockedOut(attempts.remainingLockMillis(1_000L))
            }
            if (!PinHasher.verify(pin, hash, salt)) {
                attempts = PinAttemptPolicy.onFailure(attempts, 1_000L)
                return PinAttemptPolicy.outcomeForFailure(attempts, 1_000L)
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

    private fun gate(
        availability: BiometricAvailability,
        pin: String = "1234",
    ): Pair<PinUnlockController, AppLockState> {
        val lock = AppLockState()
        return PinUnlockController(FakeAccountRepository(pin), lock, FakeAvailability(availability)) to lock
    }

    // ---------------------------------------------- A. availability ----------

    @Test
    fun biometricIsOfferedOnlyWhenTheOsCanAuthenticate() {
        assertEquals(
            BiometricAvailability.AVAILABLE,
            gate(BiometricAvailability.AVAILABLE).first.biometricAvailability(),
        )
        assertTrue(gate(BiometricAvailability.AVAILABLE).first.shouldOfferBiometric())
    }

    @Test
    fun everyUnusableAvailabilityHidesBiometricAndKeepsThePinPath() {
        listOf(
            BiometricAvailability.NO_HARDWARE,
            BiometricAvailability.HARDWARE_UNAVAILABLE,
            BiometricAvailability.NONE_ENROLLED,
            BiometricAvailability.UNSUPPORTED,
        ).forEach { availability ->
            val (controller, _) = gate(availability)
            assertEquals(availability, controller.biometricAvailability())
            assertFalse("$availability must not offer biometrics", controller.shouldOfferBiometric())
        }
    }

    @Test
    fun theAutoPromptPolicyMatchesTheAvailability() {
        assertTrue(BiometricPolicy.shouldAutoPrompt(BiometricAvailability.AVAILABLE))
        assertFalse(BiometricPolicy.shouldAutoPrompt(BiometricAvailability.NONE_ENROLLED))
        assertFalse(BiometricPolicy.shouldAutoPrompt(BiometricAvailability.NO_HARDWARE))
        assertFalse(BiometricPolicy.shouldAutoPrompt(BiometricAvailability.HARDWARE_UNAVAILABLE))
        assertFalse(BiometricPolicy.shouldAutoPrompt(BiometricAvailability.UNSUPPORTED))
    }

    // ------------------------------------------- B/C/D. results --------------

    @Test
    fun aSuccessfulBiometricUnlocksTheUi() {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)

        val outcome = controller.onBiometricResult(BiometricAuthResult.Success)

        assertEquals(PinUnlockResult.Unlocked, outcome)
        assertTrue(lock.isUnlocked())
    }

    @Test
    fun aFailedBiometricNeverUnlocksAndThePinStillWorks() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)

        assertNull(controller.onBiometricResult(BiometricAuthResult.Failed))
        assertFalse("a failed biometric must not unlock", lock.isUnlocked())

        assertEquals(PinUnlockResult.Unlocked, controller.submit("1234"))
        assertTrue("PIN fallback must still work", lock.isUnlocked())
    }

    @Test
    fun aCancelledBiometricNeverUnlocksAndThePinStillWorks() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)

        assertNull(controller.onBiometricResult(BiometricAuthResult.Cancelled))
        assertFalse("a cancelled prompt must not unlock", lock.isUnlocked())

        assertEquals(PinUnlockResult.Unlocked, controller.submit("1234"))
        assertTrue(lock.isUnlocked())
    }

    @Test
    fun aBiometricErrorNeverUnlocksAndThePinStillWorks() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)

        assertNull(controller.onBiometricResult(BiometricAuthResult.Error(code = 5, message = "hw")))
        assertFalse("an error must not unlock", lock.isUnlocked())

        assertEquals(PinUnlockResult.Unlocked, controller.submit("1234"))
        assertTrue(lock.isUnlocked())
    }

    @Test
    fun biometricsUnavailableMeansThePinPathIsStillTheWayIn() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.NO_HARDWARE)

        assertFalse(controller.shouldOfferBiometric())
        assertEquals(PinUnlockResult.Unlocked, controller.submit("1234"))
        assertTrue(lock.isUnlocked())
    }

    @Test
    fun aWrongPinAfterABiometricFailureStaysLocked() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)

        controller.onBiometricResult(BiometricAuthResult.Failed)
        assertEquals(PinUnlockResult.WrongPin, controller.submit("0000"))

        assertFalse("a wrong PIN must not unlock even after a failed biometric", lock.isUnlocked())
    }

    // ------------------------------------------- D. lock state --------------

    @Test
    fun theUiStartsLockedAndOnlyTheTwoRealSuccessPathsUnlockIt() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)
        assertFalse("the UI must start locked", lock.isUnlocked())

        // Fake results cannot unlock.
        listOf(
            BiometricAuthResult.Cancelled,
            BiometricAuthResult.Failed,
            BiometricAuthResult.Error(1),
        ).forEach { fake ->
            controller.onBiometricResult(fake)
            assertFalse("$fake must not unlock", lock.isUnlocked())
        }

        // The OS success does.
        controller.onBiometricResult(BiometricAuthResult.Success)
        assertTrue(lock.isUnlocked())
    }

    @Test
    fun theExistingPinLockoutIsUnaffectedByTheBiometricPath() = runBlocking {
        val (controller, lock) = gate(BiometricAvailability.AVAILABLE)

        controller.onBiometricResult(BiometricAuthResult.Cancelled)

        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { controller.submit("0000") }
        val lockedOut = controller.submit("1234")

        assertTrue("the existing lockout must still apply", lockedOut is PinUnlockResult.LockedOut)
        assertFalse(lock.isUnlocked())
    }
}
