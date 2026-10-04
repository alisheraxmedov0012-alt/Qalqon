package uz.faceguard.app.protection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionRuntimeState

/**
 * Post-reboot camera limitation.
 *
 * A background (boot) restore cannot claim the camera foreground type, so
 * recognition is limited until the app is next opened. The runtime must report that
 * as its own state — distinct from a missing camera permission — and the foreground
 * service must claim the type on the first foreground moment.
 */
class ProtectionBootCameraTest {

    private val service = read("core/protection/ProtectionForegroundService.kt")
    private val runtime = read("core/protection/ProtectionRuntime.kt")
    private val banner = read("core/ui/qalqon/QalqonDegradedBanner.kt")

    // ------------------------------------------------------------ runtime state

    private fun state(
        enabled: Boolean = true,
        active: Boolean = true,
        cameraGranted: Boolean = true,
        cameraForegroundReady: Boolean = true,
    ) = ProtectionRuntimeState(
        enabled = enabled,
        active = active,
        cameraGranted = cameraGranted,
        cameraForegroundReady = cameraForegroundReady,
    )

    @Test
    fun theStateReportsTheCameraAsLimitedOnlyAfterABackgroundRestore() {
        assertTrue(state(cameraForegroundReady = false).cameraLimitedAfterBoot)
        assertFalse("a ready camera is not limited", state().cameraLimitedAfterBoot)
    }

    @Test
    fun theLimitIsGatedOnEnabledActiveProtectionWithCameraPermission() {
        assertFalse("a switched-off device is not 'limited'", state(enabled = false, cameraForegroundReady = false).cameraLimitedAfterBoot)
        assertFalse("an inactive session is not 'limited'", state(active = false, cameraForegroundReady = false).cameraLimitedAfterBoot)
        assertFalse("no camera permission is a different problem", state(cameraGranted = false, cameraForegroundReady = false).cameraLimitedAfterBoot)
    }

    @Test
    fun aFreshStateIsNeverReportedAsLimited() {
        // Default readiness is optimistic, so the UI never flashes a false warning
        // before the service has reported anything.
        assertTrue(ProtectionRuntimeState().cameraForegroundReady)
        assertFalse(ProtectionRuntimeState().cameraLimitedAfterBoot)
    }

    // ----------------------------------------------------------------- wiring

    @Test
    fun theRuntimeExposesTheCameraReadinessSetter() {
        assertTrue(runtime.contains("fun setCameraForegroundReady(ready: Boolean)"))
        assertTrue(runtime.contains("cameraForegroundReady = ready"))
    }

    @Test
    fun theServiceMarksNotReadyOnABackgroundStartAndReadyOnceClaimed() {
        // A background start (UI not visible) reports the limitation …
        assertTrue(
            "a background start must report the camera as not ready",
            service.contains("if (!runtime.uiForeground.value) runtime.setCameraForegroundReady(false)"),
        )
        // … and a successful claim clears it in the same foreground moment.
        assertTrue(
            "a successful camera-type claim must clear the limitation",
            service.contains("runtime.setCameraForegroundReady(true)"),
        )
    }

    @Test
    fun theBannerShowsThePostBootMessage() {
        assertTrue(
            "the banner must render the post-boot camera message",
            banner.contains("R.string.protection_after_boot_camera"),
        )
        assertTrue(
            "the banner must expose the after-boot flag",
            banner.contains("cameraLimitedAfterBoot"),
        )
    }

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
}
