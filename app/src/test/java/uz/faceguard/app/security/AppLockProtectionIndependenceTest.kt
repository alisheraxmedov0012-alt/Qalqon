package uz.faceguard.app.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN gate protects *the parent UI only*, never the background protection.
 *
 * A locked screen must not stop the protection runtime, its foreground service, the
 * camera session, the accessibility service or boot restoration — protection has to
 * keep enforcing while the app sits on the PIN screen (or is closed entirely).
 *
 * The gate is therefore a routing concern: none of the protection components may
 * depend on, read or mutate the UI lock. This guard fails if that ever changes.
 */
class AppLockProtectionIndependenceTest {

    private val protectionSources = listOf(
        "core/protection/ProtectionRuntime.kt",
        "core/protection/ProtectionEngine.kt",
        "core/protection/ProtectionForegroundService.kt",
        "core/protection/ProtectionBootRestorer.kt",
        "core/protection/ProtectionBootReceiver.kt",
        "core/protection/ProtectionCameraSession.kt",
        "core/protection/CameraBindingCoordinator.kt",
        "core/protection/CameraSessionBinding.kt",
        "core/protection/OverlayControllerImpl.kt",
        "core/protection/ProtectionServiceLauncher.kt",
        "core/accessibility/ProtectionAccessibilityService.kt",
        "core/monitor/ForegroundAppMonitor.kt",
        "core/scan/ScanScheduler.kt",
    )

    @Test
    fun noProtectionComponentDependsOnTheUiLock() {
        protectionSources.forEach { relative ->
            val source = read(relative)
            assertFalse(
                "$relative must not depend on the parent-UI lock: protection has to keep " +
                    "running while the UI is locked",
                source.contains("AppLockState") || source.contains("appLockState"),
            )
            // Checked against the unlock *types*, not the bare word: "biometric
            // data" already appears in prose about face-template encryption.
            listOf(
                "BiometricAvailabilityProvider",
                "BiometricAuthResult",
                "BiometricPolicy",
                "AndroidBiometricPrompt",
                "BiometricPrompt",
                "BiometricManager",
            ).forEach { biometricApi ->
                assertFalse(
                    "$relative must not depend on the biometric unlock API ($biometricApi)",
                    source.contains(biometricApi),
                )
            }
        }
    }

    @Test
    fun neitherTheLockNorTheBiometricPathControlsProtection() {
        listOf(
            "core/security/AppLockState.kt",
            "core/security/BiometricModels.kt",
            "core/security/BiometricAvailabilityProvider.kt",
            "core/security/AndroidBiometricPrompt.kt",
            "feature/auth/PinUnlockController.kt",
            "feature/auth/PinUnlockScreen.kt",
        ).forEach { relative ->
            val source = read(relative)
            listOf(
                "ProtectionRuntime",
                "ProtectionEngine",
                "ProtectionForegroundService",
                "ProtectionAccessibilityService",
                "ProtectionCameraSession",
                "ProtectionBootRestorer",
                "serviceLauncher",
                "stopService",
                "startForegroundService",
            ).forEach { forbidden ->
                assertFalse(
                    "$relative must not reference $forbidden",
                    source.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun theAppLockNeverUsesQalqonsOwnFaceRecognitionForUnlock() {
        listOf(
            "core/security/AppLockState.kt",
            "core/security/AndroidBiometricPrompt.kt",
            "feature/auth/PinUnlockController.kt",
            "feature/auth/PinUnlockScreen.kt",
        ).forEach { relative ->
            val source = read(relative)
            listOf("Recognizer", "FaceCaptureController", "FaceEmbeddable", "MobileFaceNet", "enrollment", "LivenessEvaluator")
                .forEach { forbidden ->
                    assertFalse(
                        "$relative must not use QALQON's own recognition ($forbidden) for app unlock",
                        source.contains(forbidden),
                    )
                }
        }
    }

    @Test
    fun theBiometricPathUsesOnlyTheAndroidSystemPrompt() {
        val prompt = read("core/security/AndroidBiometricPrompt.kt")
        assertTrue(
            "the unlock must be driven by the AndroidX system prompt",
            prompt.contains("BiometricPrompt") && prompt.contains("BiometricManager.Authenticators.BIOMETRIC_STRONG"),
        )
        assertFalse(
            "the device credential must not be used; the QALQON PIN stays the account fallback",
            prompt.contains("DEVICE_CREDENTIAL"),
        )
    }

    @Test
    fun theLockStateHoldsNoProtectionOrServiceControl() {
        val lock = read("core/security/AppLockState.kt")

        listOf(
            "ProtectionRuntime",
            "ProtectionForegroundService",
            "ProtectionEngine",
            "ProtectionAccessibilityService",
            "serviceLauncher",
            "stopService",
            "startForegroundService",
        ).forEach { forbidden ->
            assertFalse(
                "the UI lock must not reference $forbidden",
                lock.contains(forbidden),
            )
        }
    }

    @Test
    fun theAppStillStartsProtectionAtProcessStartIndependentOfTheUi() {
        // FaceGuardApp starts the runtime on process start; it must not consult the
        // UI lock (which is locked at that moment by definition).
        val app = read("../FaceGuardApp.kt")
        assertTrue(app.contains("protectionRuntime.start()"))
        assertFalse(app.contains("AppLockState"))
    }

    @Test
    fun theUiLockStoresNoSecret() {
        val lock = read("core/security/AppLockState.kt")

        listOf("pin", "Pin", "password", "hash", "salt", "token").forEach { forbidden ->
            assertFalse(
                "the UI lock must not handle '$forbidden' material",
                lock.contains(forbidden),
            )
        }
    }

    private fun read(relative: String): String {
        val base = File(repoRoot(), "app/src/main/java/uz/faceguard/app")
        val file = if (relative.startsWith("../")) {
            File(base, relative.removePrefix("../"))
        } else {
            File(base, relative)
        }
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
}
