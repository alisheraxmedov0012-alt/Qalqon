package uz.faceguard.app.compat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.compat.PlatformCompat

/**
 * Stage 7: the version-specific **permission** matrix (API 26–36).
 *
 * Each protection capability reaches the platform differently — a runtime permission,
 * a special-access app-op, a settings toggle — and the API at which that starts to be
 * required differs per capability. These tests pin the split points and assert the
 * production seams agree, so a permission is never requested on a version that does
 * not have it, nor skipped on one that does.
 */
class Stage7PermissionGateTest {

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }

    private val root = "app/src/main/java/uz/faceguard/app/"

    // --------------------------------------------------------------- overlay

    @Test
    fun everyOverlayPermissionProbeUsesTheApi23Boundary() {
        // Settings.canDrawOverlays exists from API 23; below it draw-over-other-apps
        // needs no permission, so every probe must short-circuit to "granted".
        val probes = listOf(
            "${root}core/protection/OverlayControllerImpl.kt",
            "${root}core/permission/ProtectionCapabilities.kt",
            "${root}core/diagnostics/SystemHealthSnapshotSource.kt",
        )
        probes.forEach { path ->
            val text = read(path)
            assertTrue(
                "$path must gate canDrawOverlays behind API 23",
                text.contains("Build.VERSION_CODES.M") && text.contains("canDrawOverlays"),
            )
        }
    }

    // ---------------------------------------------------------------- usage

    @Test
    fun usageAccessIsReadThroughTheAppOpAtTheApi29Split() {
        val usage = read("${root}core/usage/AndroidUsageAccess.kt")
        assertTrue(usage.contains("Build.VERSION_CODES.Q"))
        assertTrue(usage.contains("OPSTR_GET_USAGE_STATS"))
    }

    // ---------------------------------------------------------------- battery

    @Test
    fun batteryOptimizationIsOnlyReadFromApi23AndIsNullBelow() {
        val battery = read("${root}core/oem/AndroidBatteryOptimization.kt")
        assertTrue(
            "battery optimization does not exist below API 23 and must read as null",
            battery.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.M") &&
                battery.contains("return null"),
        )
        assertTrue("the state read uses PowerManager", battery.contains("isIgnoringBatteryOptimizations"))
    }

    // ----------------------------------------------------------- notifications

    @Test
    fun notificationPermissionIsOnlyARequestFromApi33() {
        // Behavioural check through the pure matrix the requests screen re-exports.
        (26..32).forEach { api ->
            assertTrue(
                "api $api must not ask for POST_NOTIFICATIONS",
                !PlatformCompat.notificationRuntimePermissionRequired(api),
            )
        }
        assertEquals(true, PlatformCompat.notificationRuntimePermissionRequired(33))
    }

    // ------------------------------------------------------------------ camera

    @Test
    fun theCameraForegroundTypeIsClaimedOnlyFromApi30() {
        // The camera FGS type is meaningful from API 30; below it the service keeps
        // running with FOREGROUND_SERVICE only.
        assertTrue(!PlatformCompat.supportsCameraForegroundType(29))
        assertTrue(PlatformCompat.supportsCameraForegroundType(30))
    }

    @Test
    fun theSpecialUseForegroundTypeIsClaimedOnlyFromApi34() {
        assertTrue(!PlatformCompat.supportsSpecialUseForegroundType(33))
        assertTrue(PlatformCompat.supportsSpecialUseForegroundType(34))
    }

    // -------------------------------------------------------------------- boot

    @Test
    fun bootRestoreIsDeclaredForTheWholeSupportedRange() {
        // RECEIVE_BOOT_COMPLETED has existed since API 8; the receiver is declared once
        // and works on every supported level.
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
    }

    // -------------------------------------------------- receiver export (API 34)

    @Test
    fun runtimeReceiversUseTheCompatRegistrationAcrossTheWholeRange() {
        // Android 14 (API 34) requires an explicit export flag for context-registered
        // receivers; ContextCompat supplies it only where needed, so the same call is
        // valid on API 26.
        listOf(
            "${root}core/protection/ProtectionForegroundService.kt",
            "${root}core/scan/ScanScheduler.kt",
        ).forEach { path ->
            val text = read(path)
            assertTrue(
                "$path must register through ContextCompat with RECEIVER_NOT_EXPORTED",
                text.contains("ContextCompat.registerReceiver(") &&
                    text.contains("ContextCompat.RECEIVER_NOT_EXPORTED"),
            )
            assertTrue(
                "$path must not use the un-flagged (receiver, filter) overload",
                !text.contains("registerReceiver(receiver, filter)"),
            )
        }
    }
}
