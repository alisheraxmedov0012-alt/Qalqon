package uz.faceguard.app.security

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.security.BiometricAuthResult
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
import uz.faceguard.app.feature.auth.PinUnlockViewModel

/**
 * The biometric-first unlock screen: presentation and flow wiring.
 *
 * The security semantics themselves are unchanged and stay covered by
 * [PinUnlockGateTest] / [BiometricUnlockGateTest]; this test pins the *refinement* —
 * that the screen opens biometric-first only where the OS can actually authenticate,
 * that a cancelled/failed/errored or unavailable biometric never unlocks and always
 * leaves the PIN reachable, and that the presentation stays inside the design system.
 */
class BiometricFirstUnlockUiTest {

    /** Real PBKDF2 semantics, so a "correct PIN" really is verified. */
    private class FakeAccountRepository(private val pin: String = "1234") : AccountRepository {
        private val salt = PinHasher.randomSalt()
        private val hash = PinHasher.hash(pin, salt)
        private var attempts = PinAttemptState()

        override val currentAccountId: Flow<Long?> = MutableStateFlow(1L)

        override suspend fun verifyPin(pin: String): PinVerification = when {
            attempts.isLocked(NOW) -> PinVerification.LockedOut(attempts.remainingLockMillis(NOW))
            !PinHasher.verify(pin, hash, salt) -> {
                attempts = PinAttemptPolicy.onFailure(attempts, NOW)
                PinAttemptPolicy.outcomeForFailure(attempts, NOW)
            }
            else -> {
                attempts = PinAttemptPolicy.onSuccess()
                PinVerification.Success
            }
        }

        override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
            throw UnsupportedOperationException("not used")

        override suspend fun login(phoneNumber: String, pin: String): AuthResult =
            throw UnsupportedOperationException("not used")

        override suspend fun getCurrentAccount(): UserAccount? = null

        override suspend fun logout() = Unit
    }

    private fun provider(availability: BiometricAvailability) = object : BiometricAvailabilityProvider {
        override fun availability(): BiometricAvailability = availability
    }

    private fun viewModel(availability: BiometricAvailability) = PinUnlockViewModel(
        accountRepository = FakeAccountRepository(),
        appLockState = AppLockState(),
        biometricAvailability = provider(availability),
    )

    // ---------------------------------------------------- biometric-first entry

    @Test
    fun withAUsableBiometricTheScreenOpensBiometricFirst() {
        val ui = viewModel(BiometricAvailability.AVAILABLE).ui.value
        assertTrue("biometric must be offered", ui.biometricAvailable)
        assertFalse("the PIN entry must not be shown first", ui.pinMode)
    }

    @Test
    fun withoutABiometricTheScreenOpensDirectlyOnThePinEntry() {
        listOf(
            BiometricAvailability.NO_HARDWARE,
            BiometricAvailability.NONE_ENROLLED,
            BiometricAvailability.HARDWARE_UNAVAILABLE,
            BiometricAvailability.UNSUPPORTED,
        ).forEach { availability ->
            val ui = viewModel(availability).ui.value
            assertFalse("$availability must not offer biometrics", ui.biometricAvailable)
            assertTrue("$availability must show the PIN entry", ui.pinMode)
        }
    }

    @Test
    fun theAutomaticPromptIsOfferedOnceAndOnlyWhereItCanSucceed() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)
        assertTrue("the first entry must show the OS prompt", vm.consumeAutoPrompt())
        assertFalse("it must never be offered twice on the same screen", vm.consumeAutoPrompt())
    }

    @Test
    fun anUnusableBiometricIsNeverAutoPrompted() {
        listOf(
            BiometricAvailability.NO_HARDWARE,
            BiometricAvailability.NONE_ENROLLED,
            BiometricAvailability.HARDWARE_UNAVAILABLE,
            BiometricAvailability.UNSUPPORTED,
        ).forEach { availability ->
            assertFalse("$availability must not flash a prompt", viewModel(availability).consumeAutoPrompt())
        }
    }

    // ------------------------------------------------------------ PIN fallback

    @Test
    fun choosingThePinFallbackRevealsThePinEntryWithoutUnlocking() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)

        vm.showPin()

        assertTrue("the PIN entry must be shown", vm.ui.value.pinMode)
        assertFalse("showing the PIN must never itself unlock", vm.ui.value.unlocked)
    }

    // ------------------------------------------------------- biometric outcomes

    @Test
    fun aSuccessfulBiometricUnlocksTheScreen() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)
        vm.startBiometric()

        vm.onBiometricResult(BiometricAuthResult.Success)

        assertTrue("only success may unlock", vm.ui.value.unlocked)
        assertFalse("the prompt must be finished", vm.ui.value.biometricInProgress)
    }

    @Test
    fun aCancelledBiometricNeverUnlocksAndRevealsThePinFallback() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)

        vm.onBiometricResult(BiometricAuthResult.Cancelled)

        assertFalse("a cancelled prompt must not unlock", vm.ui.value.unlocked)
        assertTrue("the PIN fallback must be reachable immediately", vm.ui.value.pinMode)
    }

    @Test
    fun aFailedBiometricNeverUnlocksAndKeepsBothOptionsAvailable() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)

        vm.onBiometricResult(BiometricAuthResult.Failed)

        assertFalse("a failed biometric must not unlock", vm.ui.value.unlocked)
        assertTrue("the parent must be told the check failed", vm.ui.value.biometricFailed)
        assertTrue("biometric may still be retried", vm.ui.value.biometricAvailable)
    }

    @Test
    fun aBiometricErrorNeverUnlocksAndFallsBackToThePinEntry() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)

        vm.onBiometricResult(BiometricAuthResult.Error(code = 5, message = "hardware"))

        assertFalse("an error must not unlock", vm.ui.value.unlocked)
        assertTrue("the PIN entry must be reachable", vm.ui.value.pinMode)
    }

    @Test
    fun aWithdrawnPromptLeavesThePinEntryAndNeverUnlocks() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)

        vm.onBiometricUnavailable()

        assertFalse("a withdrawn prompt must not unlock", vm.ui.value.unlocked)
        assertFalse("biometric must no longer be offered", vm.ui.value.biometricAvailable)
        assertTrue("the PIN entry must be shown instead", vm.ui.value.pinMode)
    }

    @Test
    fun theBiometricRetryLeavesThePinEntryForThePrompt() {
        val vm = viewModel(BiometricAvailability.AVAILABLE)
        vm.showPin()
        assertTrue(vm.ui.value.pinMode)

        vm.startBiometric()

        assertFalse("retrying biometrics returns to the biometric screen", vm.ui.value.pinMode)
        assertTrue(vm.ui.value.biometricInProgress)
    }

    // --------------------------------------------- the unchanged PIN semantics

    @Test
    fun theCorrectPinStillReachesTheAuthenticatedPath() = runBlocking {
        val lock = AppLockState()
        val controller = PinUnlockController(FakeAccountRepository(), lock, provider(BiometricAvailability.AVAILABLE))

        assertEquals(PinUnlockResult.Unlocked, controller.submit("1234"))
        assertTrue(lock.isUnlocked())
    }

    @Test
    fun aWrongPinStillLeavesTheUiLocked() = runBlocking {
        val lock = AppLockState()
        val controller = PinUnlockController(FakeAccountRepository(), lock, provider(BiometricAvailability.AVAILABLE))

        assertEquals(PinUnlockResult.WrongPin, controller.submit("9999"))
        assertFalse(lock.isUnlocked())
    }

    @Test
    fun theBiometricAvailabilityGateStillDrivesWhetherBiometricIsOffered() = runBlocking {
        val lock = AppLockState()
        val available = PinUnlockController(FakeAccountRepository(), lock, provider(BiometricAvailability.AVAILABLE))
        val noneEnrolled = PinUnlockController(FakeAccountRepository(), lock, provider(BiometricAvailability.NONE_ENROLLED))

        assertTrue(available.shouldOfferBiometric())
        assertFalse(noneEnrolled.shouldOfferBiometric())
    }

    // ------------------------------------------------------- source invariants

    @Test
    fun theScreenStaysInsideTheDesignSystem() {
        val source = read("feature/auth/PinUnlockScreen.kt")
        assertFalse("no raw color literal", source.contains("Color(0x"))
        assertFalse("no android.graphics.Color", source.contains("android.graphics.Color"))
        assertFalse("no raw dp literal", Regex("""\b\d+\.dp\b""").containsMatchIn(source))
        assertTrue("the screen must use the spacing tokens", source.contains("QalqonDimens."))
    }

    @Test
    fun theScreenWiresTheAutomaticPromptAndThePinFallback() {
        val source = read("feature/auth/PinUnlockScreen.kt")
        // Biometric-first: the OS prompt is invoked automatically on entry…
        assertTrue(source.contains("viewModel.consumeAutoPrompt()"))
        assertTrue(source.contains("launchBiometricPrompt("))
        assertTrue(source.contains("AndroidBiometricPrompt.from("))
        // …and the PIN fallback is always reachable.
        assertTrue(source.contains("R.string.lock_pin_alternative"))
        assertTrue(source.contains("AppPinField("))
    }

    @Test
    fun theGlyphIsDecorativeSoTheTitleCarriesTheMeaning() {
        val source = read("feature/auth/PinUnlockScreen.kt")
        assertTrue(source.contains("Icons.Filled.Lock"))
        assertTrue(source.contains("contentDescription = null"))
    }

    @Test
    fun noAuthenticationArchitectureWasRewritten() {
        // The screen still drives the existing controller/biometric seam, and nothing
        // in the refinement duplicates or bypasses it.
        val source = read("feature/auth/PinUnlockScreen.kt")
        assertTrue(source.contains("PinUnlockController("))
        assertTrue(source.contains("appLockState"))
        assertFalse("must not fabricate a success", source.contains("BiometricAuthResult.Success ::"))
    }

    // ------------------------------------------------------------ localization

    @Test
    fun theNewSubtitleExistsInEveryLocaleAndIsUsed() {
        val locales = listOf("values", "values-en", "values-ru")
        val keys = locales.associateWith { keysIn(it) }
        locales.forEach { locale ->
            assertTrue("$locale is missing lock_auth_subtitle", "lock_auth_subtitle" in keys.getValue(locale))
        }
        assertTrue(
            "the screen must reference the new subtitle",
            read("feature/auth/PinUnlockScreen.kt").contains("R.string.lock_auth_subtitle"),
        )
        // Locale parity over the whole lock_* block.
        val reference = keys.getValue("values").filter { it.startsWith("lock_") }.toSet()
        locales.forEach { locale ->
            assertEquals(
                "$locale lock_* keys drifted",
                reference,
                keys.getValue(locale).filter { it.startsWith("lock_") }.toSet(),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun keysIn(locale: String): Set<String> =
        Regex("""name="([a-z0-9_]+)"""")
            .findAll(File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath")
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val NOW = 1_000L
    }
}
