package uz.faceguard.app.permission

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.permission.ProtectionCapabilities
import uz.faceguard.app.core.permission.ProtectionCapabilitySource
import uz.faceguard.app.core.permission.openWithFallback
import uz.faceguard.app.core.usage.AndroidUsageAccess
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.core.protection.ProtectionRuntimeState

/**
 * Stage 10 special-permission / OEM-compatibility regression suite.
 *
 * The three special accesses (Usage Access, draw-over-other-apps, accessibility service)
 * must be reported "ready" only from their real system state, must refresh after the user
 * returns from Settings, and must never crash or dead-end when a device has no handler
 * for a settings page. These tests cover the pure, deterministic parts of that contract;
 * the Android probes themselves are verified on a physical device.
 */
class SpecialPermissionCompatibilityTest {

    // --------------------------------------------------- readiness mapping

    @Test
    fun capabilitiesAreReadyOnlyWhenAllThreeAreGranted() {
        assertTrue(
            ProtectionCapabilities(
                usageAccessGranted = true,
                overlayGranted = true,
                accessibilityEnabled = true,
            ).allGranted,
        )
    }

    @Test
    fun eachMissingCapabilityMakesTheSnapshotNotFullyReady() {
        val full = ProtectionCapabilities(true, true, true)
        // Every single-capability loss must be detected — no special access is optional.
        assertFalse(full.copy(usageAccessGranted = false).allGranted)
        assertFalse(full.copy(overlayGranted = false).allGranted)
        assertFalse(full.copy(accessibilityEnabled = false).allGranted)
        assertFalse(ProtectionCapabilities(false, false, false).allGranted)
    }

    @Test
    fun eachGrantedCapabilityRemovesExactlyItsDegradedState() {
        // "granted -> ready": with every capability held, the protection state reports no
        // missing capability; each single loss reports exactly that one capability.
        val ready = ProtectionRuntimeState(
            enabled = true,
            active = true,
            overlayGranted = true,
            usageAccessGranted = true,
            accessibilityEnabled = true,
            cameraGranted = true,
        )
        assertTrue(ready.degradedCapabilities.isEmpty())
        assertFalse(ready.degraded)

        assertEquals(
            setOf(ProtectionCapability.USAGE_ACCESS),
            ready.copy(usageAccessGranted = false).degradedCapabilities,
        )
        assertEquals(
            setOf(ProtectionCapability.OVERLAY),
            ready.copy(overlayGranted = false).degradedCapabilities,
        )
        assertEquals(
            setOf(ProtectionCapability.ACCESSIBILITY),
            ready.copy(accessibilityEnabled = false).degradedCapabilities,
        )
    }

    @Test
    fun aFakeSourceDrivesReadinessDeterministically() {
        // The runtime reads through the seam, so a fake source can model each OEM/state
        // (granted, revoked, unavailable) without Android.
        var granted = false
        val source = ProtectionCapabilitySource {
            ProtectionCapabilities(
                usageAccessGranted = granted,
                overlayGranted = granted,
                accessibilityEnabled = granted,
            )
        }
        assertFalse(source.read().allGranted)
        granted = true
        assertTrue(source.read().allGranted)
    }

    // --------------------------------------------------- settings deep-link safety

    @Test
    fun theSettingsFallbackIsUsedOnlyWhenThePrimaryHasNoHandler() {
        val attempted = mutableListOf<String>()
        fun starter(working: Set<String>): (String) -> Boolean = { page ->
            attempted += page
            page in working
        }

        // Primary works -> fallback never attempted.
        attempted.clear()
        assertTrue(openWithFallback("primary", "fallback", starter(setOf("primary"))))
        assertEquals(listOf("primary"), attempted)

        // Primary has no handler -> fallback (app details) is used, no crash.
        attempted.clear()
        assertTrue(openWithFallback("primary", "fallback", starter(setOf("fallback"))))
        assertEquals(listOf("primary", "fallback"), attempted)
    }

    @Test
    fun aMissingHandlerForBothSettingsPagesIsReportedNotThrown() {
        assertFalse(openWithFallback("a", "b") { false })
    }

    // --------------------------------------------------- usage app-op mapping

    @Test
    fun onlyModeAllowedGrantsUsageAccess() {
        // AppOpsManager.MODE_ALLOWED == 3; the others (default/ignored/errored/foreground)
        // must never read as granted.
        assertTrue(AndroidUsageAccess.modeGrantsUsageAccess(3))
        listOf(0, 1, 2, 4, 5).forEach { mode ->
            assertFalse("mode $mode must not grant", AndroidUsageAccess.modeGrantsUsageAccess(mode))
        }
    }

    // --------------------------------------------------- source contracts (no crash paths)

    @Test
    fun everySpecialPermissionEntryPointUsesTheFailureSafeLauncher() {
        // A raw startActivity on a settings intent can throw ActivityNotFoundException on
        // a device without a handler; the UI must route through openSettingsOrFallback.
        listOf(
            "feature/protection/ProtectionScreen.kt",
            "feature/home/HomeScreen.kt",
        ).forEach { path ->
            val source = read(path)
            assertFalse(
                "$path must not start a capability settings intent directly",
                source.contains("context.startActivity(viewModel.capabilitySettingsIntent"),
            )
            assertFalse(
                "$path must not start the accessibility settings intent directly",
                source.contains("context.startActivity(viewModel.accessibilitySettingsIntent"),
            )
            assertTrue(
                "$path must use openSettingsOrFallback for settings deep-links",
                source.contains("openSettingsOrFallback("),
            )
        }
    }

    @Test
    fun theRuntimeReprobesCapabilitiesAfterResumeWithoutClaimingFalseReady() {
        // The post-resume re-probe must read the real capability source each attempt and
        // stop early only when everything is genuinely granted.
        val runtime = read("core/protection/ProtectionRuntime.kt")
        assertTrue(runtime.contains("scheduleCapabilityReprobe()"))
        assertTrue(runtime.contains("capabilities.read().allGranted"))
        assertTrue(
            "an OEM update slightly after resume must be caught by a bounded re-probe",
            runtime.contains("CAPABILITY_REPROBE_ATTEMPTS"),
        )
    }

    @Test
    fun theAccessibilityCheckMatchesOnlyQalqonsOwnComponent() {
        // Another app's accessibility service being enabled must never read as Qalqon's.
        val capability = read("core/accessibility/AccessibilityCapability.kt")
        assertTrue(
            "the enabled-service entry must be resolved to a ComponentName and compared",
            capability.contains("ComponentName(service.packageName, service.name) == expected"),
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
