package uz.faceguard.app.protection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionRuntimeState

/**
 * Camera-permission tracking in the protection runtime.
 *
 * A revoked camera must be visible in the protection state itself — not only in the
 * Protection screen's local permission state — so background logic and diagnostics
 * can tell "recognition is running" from "the camera is gone".
 */
class CameraPermissionTrackingTest {

    private val runtime = read("core/protection/ProtectionRuntime.kt")

    @Test
    fun theStateExposesTheCameraFlagAndDefaultsToNotGranted() {
        // Fail safe: without a probe result we never claim the camera is available.
        assertFalse(ProtectionRuntimeState().cameraGranted)
        assertTrue(runtime.contains("val cameraGranted: Boolean = false"))
    }

    @Test
    fun theRuntimeProbesTheCameraPermissionInRefreshPermissions() {
        assertTrue(
            "refreshPermissions must refresh the camera flag",
            runtime.contains("cameraGranted = hasCameraPermission()"),
        )
        assertTrue(
            "the probe must check the CAMERA permission on the app context",
            runtime.contains("Manifest.permission.CAMERA") &&
                runtime.contains("PackageManager.PERMISSION_GRANTED"),
        )
    }

    @Test
    fun theProtectionStateIsTheAuthorityForTheCameraRequirementRow() {
        val screen = read("feature/protection/ProtectionScreen.kt")
        assertTrue(
            "the requirement row must read the runtime state, not a screen-local value",
            screen.contains("cameraGranted = state.cameraGranted"),
        )
    }

    @Test
    fun theCameraIsDeliberatelyNotPartOfTheReadyGate() {
        // Blocking needs the overlay, not the camera; the fail-closed no-face policy
        // still protects without a frame, so `ready` must not require the camera.
        val ready = runtime.substringAfter("val ready: Boolean").substringBefore("}")
        assertTrue("ready must still require overlay + usage access", ready.contains("overlayGranted"))
        assertFalse("ready must not depend on the camera", ready.contains("cameraGranted"))
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
