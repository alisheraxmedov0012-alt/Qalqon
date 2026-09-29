package uz.faceguard.app.protection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionBootRestorer
import uz.faceguard.app.core.protection.ProtectionServiceLauncher
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.SettingsRepository

/** Stateful settings fake: the "persisted protection intent" the restorer reads. */
private class FakeSettingsRepository(enabled: Boolean = false, private val fail: Boolean = false) :
    SettingsRepository {
    private val state = MutableStateFlow(AppSettings(protectionEnabled = enabled))

    override val settings: Flow<AppSettings> =
        if (fail) flow { throw IllegalStateException("settings unavailable") } else state

    fun persist(enabled: Boolean) {
        state.value = state.value.copy(protectionEnabled = enabled)
    }

    override suspend fun setProtectionEnabled(enabled: Boolean) = persist(enabled)
    override suspend fun setScanMode(mode: ScanMode) = Unit
    override suspend fun setRecoveryDelayMs(delayMs: Long) = Unit
    override suspend fun setUnknownUserPolicy(policy: BlockPolicy) = Unit
    override suspend fun setNoFacePolicy(policy: BlockPolicy) = Unit
    override suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) = Unit
}

/** Stateful account fake: the signed-in session the restorer requires. */
private class FakeAccountRepository(
    accountId: Long? = null,
    private val fail: Boolean = false,
) : AccountRepository {
    private val state = MutableStateFlow(accountId)

    override val currentAccountId: Flow<Long?> =
        if (fail) flow { throw IllegalStateException("session unavailable") } else state

    fun signIn(id: Long?) { state.value = id }

    override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
        throw UnsupportedOperationException("not used")

    override suspend fun login(phoneNumber: String, pin: String): AuthResult =
        throw UnsupportedOperationException("not used")

    override suspend fun getCurrentAccount(): UserAccount? = null

    override suspend fun logout() = Unit

    override suspend fun verifyPin(pin: String): Boolean = false
}

private class RecordingServiceLauncher : ProtectionServiceLauncher {
    var startCalls = 0
    var stopCalls = 0
    var failStart = false

    override fun start() {
        startCalls++
        if (failStart) throw IllegalStateException("foreground service start rejected")
    }

    override fun stop() {
        stopCalls++
    }
}

/**
 * Phase 7.4: restoration of protection after process (re)start / reboot.
 *
 * Only the persistent protection *intent* is restored — the restorer holds no
 * recognition, engine, camera or overlay state, and re-runs the existing service
 * startup path. Nothing here can create a second runtime, service, camera session
 * or overlay, because the restorer only ever calls the existing launcher.
 */
class ProtectionBootRestorerTest {

    private val launcher = RecordingServiceLauncher()

    private fun restorer(
        settings: SettingsRepository,
        account: AccountRepository,
    ) = ProtectionBootRestorer(settings, account, launcher)

    private fun restorer(enabled: Boolean, accountId: Long?) =
        restorer(FakeSettingsRepository(enabled), FakeAccountRepository(accountId))

    // ---- persistent intent -------------------------------------------------

    @Test
    fun protectionOn_isRestoredOnStartup() = runBlocking {
        val settings = FakeSettingsRepository(enabled = false)
        settings.persist(true) // user turned protection on; state is persistent
        val restorer = restorer(settings, FakeAccountRepository(7L))

        assertTrue(restorer.restore())
        assertEquals(1, launcher.startCalls)
        assertEquals("restoration must never tear anything down", 0, launcher.stopCalls)
    }

    @Test
    fun protectionOff_isNotRestored() = runBlocking {
        val restorer = restorer(enabled = false, accountId = 7L)

        assertFalse(restorer.restore())
        assertEquals("a protection-OFF device must never be auto-protected", 0, launcher.startCalls)
    }

    @Test
    fun aRebootWithTheOnIntentRestores_andTheOffIntentStaysOff() = runBlocking {
        // The same persisted store survives a "recreation": what is read back is
        // exactly what the user last set.
        val settings = FakeSettingsRepository(enabled = true)
        val account = FakeAccountRepository(7L)
        val restorer = restorer(settings, account)

        assertTrue("ON intent restored", restorer.restore())

        // User explicitly disables protection; that is persisted too.
        settings.persist(false)
        assertFalse("OFF intent is not auto-enabled on the next boot", restorer.restore())
        assertEquals(1, launcher.startCalls)
    }

    // ---- boot decision -----------------------------------------------------

    @Test
    fun bootWithProtectionOnAndASession_requestsStartup() = runBlocking {
        assertTrue(restorer(enabled = true, accountId = 1L).restore())
        assertEquals(1, launcher.startCalls)
    }

    @Test
    fun bootWithProtectionOnButSignedOut_requestsNothing() = runBlocking {
        assertFalse(restorer(enabled = true, accountId = null).restore())
        assertEquals(0, launcher.startCalls)
    }

    @Test
    fun bootWithProtectionOffAndASession_requestsNothing() = runBlocking {
        assertFalse(restorer(enabled = false, accountId = 1L).restore())
        assertEquals(0, launcher.startCalls)
    }

    // ---- idempotency / lifecycle ------------------------------------------

    @Test
    fun duplicateBootAndStartupSignals_neverTearDownOrDuplicate() = runBlocking {
        val restorer = restorer(enabled = true, accountId = 1L)

        repeat(5) { assertTrue(restorer.restore()) }

        // Each trigger requests the same (already-running) service; the platform
        // keeps one instance, and the restorer never stops anything.
        assertEquals(5, launcher.startCalls)
        assertEquals("no duplicate teardown", 0, launcher.stopCalls)
    }

    @Test
    fun repeatedRestore_isStableAndNeverThrows() = runBlocking {
        val restorer = restorer(enabled = false, accountId = null)
        repeat(3) { assertFalse(restorer.restore()) }
        assertEquals(0, launcher.startCalls)
    }

    // ---- failure isolation -------------------------------------------------

    @Test
    fun settingsReadFailure_isIsolated() = runBlocking {
        val restorer = restorer(FakeSettingsRepository(fail = true), FakeAccountRepository(1L))

        assertFalse(restorer.restore())
        assertEquals(0, launcher.startCalls)
    }

    @Test
    fun sessionReadFailure_isIsolated() = runBlocking {
        val restorer = restorer(FakeSettingsRepository(enabled = true), FakeAccountRepository(fail = true))

        assertFalse(restorer.restore())
        assertEquals(0, launcher.startCalls)
    }

    @Test
    fun aRejectedServiceStart_isReportedNotThrown() = runBlocking {
        launcher.failStart = true
        val restorer = restorer(enabled = true, accountId = 1L)

        assertFalse("a rejected foreground-service start must not crash boot", restorer.restore())
    }

    // ---- structural guarantees --------------------------------------------

    /**
     * The restorer must restore *only* the persisted intent. These guards fail if
     * anyone later wires recognition, engine, camera, overlay or accessibility state
     * into restoration — which would mean stale transient state (or duplicated
     * components) could be revived after a restart.
     */
    @Test
    fun restorationDependsOnNoRecognitionEngineCameraOrOverlayState() {
        val injected = ProtectionBootRestorer::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.simpleName }
            .toSet()

        val forbidden = listOf(
            "Recognizer", "ProtectionEngine", "ProtectionRuntime", "ProtectionCameraSession",
            "CameraSessionBinding", "FaceCaptureController", "ProtectionActionExecutor",
            "AccessibilityOverlayRegistry", "ProtectionAccessibilityService", "ScanScheduler",
            "ForegroundAppMonitor", "OverlayControllerImpl", "ProtectionEngine" + "OverlayController",
        )
        forbidden.forEach { type ->
            assertFalse("restoration must not depend on $type", injected.contains(type))
        }

        // It restores through the existing service-start seam, and nothing else.
        assertTrue("restoration reuses the existing launcher", injected.contains("ProtectionServiceLauncher"))
    }
}
