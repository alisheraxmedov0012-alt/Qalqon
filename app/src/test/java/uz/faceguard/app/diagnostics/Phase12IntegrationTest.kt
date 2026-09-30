package uz.faceguard.app.diagnostics

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.diagnostics.PlatformCapabilities
import uz.faceguard.app.core.diagnostics.RuntimeHealthMapper
import uz.faceguard.app.core.protection.ProtectionBootRestorer
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.core.protection.ProtectionServiceLauncher
import uz.faceguard.app.domain.diagnostics.DiagnosticCheck
import uz.faceguard.app.domain.diagnostics.DiagnosticStatus
import uz.faceguard.app.domain.diagnostics.ScheduleSyncMechanism
import uz.faceguard.app.domain.diagnostics.SystemHealthEvaluator
import uz.faceguard.app.domain.diagnostics.SystemHealthLevel
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.SettingsRepository
import uz.faceguard.app.domain.security.PinVerification

/**
 * Phase 12: the health audit against the real wiring.
 *
 * The unit tests elsewhere drive one rule each. This suite pins the two
 * background mechanisms the audit claims to verify — BOOT_COMPLETED restoration
 * and the event-driven schedule/scan sync — to the actual manifest and Gradle
 * metadata, so removing either fails the JVM build instead of silently degrading
 * the audit. It then walks a restored intent through the audit end to end.
 */
class Phase12IntegrationTest {

    private val evaluator = SystemHealthEvaluator()

    // --------------------------------------------------------- BOOT_COMPLETED

    @Test
    fun manifestDeclaresBootCompletedRestoration() {
        val manifest = read("app/src/main/AndroidManifest.xml")

        assertTrue(
            "RECEIVE_BOOT_COMPLETED permission is missing",
            manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"),
        )
        assertTrue(
            "the boot receiver is not declared",
            manifest.contains(".core.protection.ProtectionBootReceiver"),
        )
        assertTrue(
            "the boot receiver does not listen for BOOT_COMPLETED",
            manifest.contains("android.intent.action.BOOT_COMPLETED"),
        )
    }

    @Test
    fun manifestDeclaresTheOverlayAndAccessibilityGuardrails() {
        val manifest = read("app/src/main/AndroidManifest.xml")

        assertTrue("SYSTEM_ALERT_WINDOW is missing", manifest.contains("SYSTEM_ALERT_WINDOW"))
        assertTrue(
            "the accessibility service is not declared",
            manifest.contains(".core.accessibility.ProtectionAccessibilityService"),
        )
        assertTrue(
            "the accessibility service is not bind-guarded",
            manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"),
        )
    }

    // ------------------------------------------------------------- schedule sync

    @Test
    fun scheduleSyncIsEventDrivenAndWorkManagerIsDeliberatelyAbsent() {
        assertEquals(
            ScheduleSyncMechanism.EVENT_DRIVEN,
            PlatformCapabilities.SCHEDULE_SYNC_MECHANISM,
        )

        val gradleFiles = listOf(
            "app/build.gradle.kts",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle/libs.versions.toml",
        )
        gradleFiles.forEach { path ->
            val text = read(path)
            assertFalse(
                "$path unexpectedly depends on WorkManager",
                text.contains("androidx.work") || text.contains("work-runtime"),
            )
        }
    }

    @Test
    fun theAuditReportsTheEventDrivenMechanismAsOk() {
        val report = evaluator.evaluate(healthySnapshot())
        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.SCHEDULE_SYNC))
        assertEquals(DiagnosticStatus.OK, report.statusOf(DiagnosticCheck.BOOT_RESTORE))
    }

    // ------------------------------------------------------------- end to end

    @Test
    fun aRestoredProtectionIntentAuditsHealthy() = runBlocking {
        val settings = FakeSettingsRepository(enabled = true)
        val account = FakeAccountRepository(7L)
        val launcher = RecordingLauncher()

        val restored = ProtectionBootRestorer(settings, account, launcher).restore()

        assertTrue("the persisted ON intent must be restored", restored)
        assertEquals(1, launcher.startCalls)

        // The launcher now owns a running foreground service; the runtime mirrors
        // the persisted intent and the granted capabilities.
        val runtime = ProtectionRuntimeState(
            enabled = true,
            active = true,
            overlayGranted = true,
            usageAccessGranted = true,
            accessibilityEnabled = true,
            notificationsEnabled = true,
        )
        val report = evaluator.evaluate(
            RuntimeHealthMapper.snapshot(
                runtime = runtime,
                signedIn = true,
                foregroundServiceRunning = launcher.startCalls > 0,
            ),
        )

        assertEquals(SystemHealthLevel.HEALTHY, report.level)
        assertTrue(report.blocker.isEmpty())
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun aProtectionOffDeviceIsNotBlamedByTheAudit() = runBlocking {
        val settings = FakeSettingsRepository(enabled = false)
        val launcher = RecordingLauncher()

        val restored = ProtectionBootRestorer(settings, FakeAccountRepository(7L), launcher).restore()

        assertFalse("protection OFF must never be auto-started", restored)
        assertEquals(0, launcher.startCalls)

        val report = evaluator.evaluate(
            RuntimeHealthMapper.snapshot(
                runtime = ProtectionRuntimeState(enabled = false),
                signedIn = true,
                foregroundServiceRunning = false,
            ),
        )

        assertEquals(SystemHealthLevel.DEGRADED, report.level)
        assertTrue("a deliberate OFF state must not read as a failure", report.blocker.isEmpty())
        assertEquals(DiagnosticStatus.WARNING, report.statusOf(DiagnosticCheck.PROTECTION_INTENT))
    }

    @Test
    fun aSignedOutDeviceIsUnknownEvenWhenEverythingElseLooksOn() {
        val report = evaluator.evaluate(healthySnapshot(signedIn = false))
        assertEquals(SystemHealthLevel.UNKNOWN, report.level)
        assertTrue(report.blocker.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun healthySnapshot(signedIn: Boolean = true) = RuntimeHealthMapper.snapshot(
        signedIn = signedIn,
        protectionEnabled = true,
        foregroundServiceRunning = true,
        overlayGranted = true,
        accessibilityEnabled = true,
        usageAccessGranted = true,
        notificationsEnabled = true,
    )

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("missing file: $relativePath", file.isFile)
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
}

/** Stateful settings fake: the persisted intent the restorer reads. */
private class FakeSettingsRepository(enabled: Boolean) : SettingsRepository {
    private val state = MutableStateFlow(AppSettings(protectionEnabled = enabled))
    override val settings: Flow<AppSettings> = state
    override suspend fun setProtectionEnabled(enabled: Boolean) { state.value = state.value.copy(protectionEnabled = enabled) }
    override suspend fun setScanMode(mode: ScanMode) = Unit
    override suspend fun setRecoveryDelayMs(delayMs: Long) = Unit
    override suspend fun setUnknownUserPolicy(policy: BlockPolicy) = Unit
    override suspend fun setNoFacePolicy(policy: BlockPolicy) = Unit
    override suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) = Unit
}

private class FakeAccountRepository(private val accountId: Long?) : AccountRepository {
    override val currentAccountId: Flow<Long?> = MutableStateFlow(accountId)
    override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult =
        throw UnsupportedOperationException("not used")
    override suspend fun login(phoneNumber: String, pin: String): AuthResult =
        throw UnsupportedOperationException("not used")
    override suspend fun getCurrentAccount(): UserAccount? = null
    override suspend fun logout() = Unit
    override suspend fun verifyPin(pin: String): PinVerification = PinVerification.InvalidPin
}

private class RecordingLauncher : ProtectionServiceLauncher {
    var startCalls = 0
    override fun start() { startCalls++ }
    override fun stop() = Unit
}
