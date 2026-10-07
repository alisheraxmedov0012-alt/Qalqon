package uz.faceguard.app.compat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7: the manifest audit from a version-compatibility standpoint.
 *
 * The same merged manifest ships to API 26 (Android 8.0) and API 36 (Android 16), so
 * every declaration must be valid and *intentional* across that range: required
 * permissions present, no dangerous/version-specific permission added by accident,
 * every component's `exported` correct (mandatory since API 31), the foreground
 * service typed for API 34+, and the offline-first privacy contract intact.
 */
class Stage7ManifestAuditTest {

    private val manifest = read("app/src/main/AndroidManifest.xml")

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

    // ------------------------------------------------------------- permissions

    @Test
    fun theRequiredPermissionsAreDeclaredOnEverySupportedVersion() {
        listOf(
            "android.permission.CAMERA",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ).forEach { permission ->
            assertTrue("manifest must declare $permission", manifest.contains(permission))
        }
    }

    @Test
    fun theOfflineFirstContractIsPreservedAcrossEveryVersion() {
        // INTERNET/ACCESS_NETWORK_STATE are only ever *removed* (ML Kit's datatransport
        // injects them through manifest merging), never requested.
        assertTrue(
            "INTERNET must be explicitly removed from the merged manifest",
            manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""),
        )
        assertTrue(
            "ACCESS_NETWORK_STATE must be explicitly removed",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""),
        )
    }

    @Test
    fun noDangerousOrVersionSpecificPermissionIsAddedByAccident() {
        listOf(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.RECORD_AUDIO",
            "android.permission.REQUEST_INSTALL_PACKAGES",
        ).forEach { permission ->
            assertFalse(
                "manifest must not request $permission",
                manifest.contains("android:name=\"$permission\""),
            )
        }
    }

    @Test
    fun theCameraFeatureIsDeclaredOptional() {
        // A device without a camera must still install; protection then fails closed.
        assertTrue(manifest.contains("<uses-feature android:name=\"android.hardware.camera\""))
        assertTrue(manifest.contains("android:required=\"false\""))
    }

    @Test
    fun packageVisibilityIsScopedToTheLauncherSetOnly() {
        // Android 11+ package visibility: only the launchable set is queried, never the
        // broad QUERY_ALL_PACKAGES.
        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }

    // ---------------------------------------------------------------- components

    @Test
    fun theLauncherActivityIsExportedAndTyped() {
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue("the launcher activity must be exported", manifest.contains("android:exported=\"true\""))
    }

    @Test
    fun theForegroundServiceKeepsItsVersionCorrectDeclaration() {
        assertTrue(manifest.contains("android:name=\".core.protection.ProtectionForegroundService\""))
        assertTrue("the protection service must not be exported", manifest.contains("android:exported=\"false\""))
        assertTrue("Recents removal must not stop it", manifest.contains("android:stopWithTask=\"false\""))
        assertTrue(
            "the FGS must declare camera|specialUse for API 34+",
            manifest.contains("android:foregroundServiceType=\"camera|specialUse\""),
        )
        assertTrue(
            "specialUse requires a documented subtype",
            manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"),
        )
    }

    @Test
    fun theBootReceiverIsExportedForBootCompleted() {
        assertTrue(manifest.contains("android:name=\".core.protection.ProtectionBootReceiver\""))
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun theAccessibilityServiceKeepsItsSystemOnlyPermission() {
        assertTrue(manifest.contains("android:name=\".core.accessibility.ProtectionAccessibilityService\""))
        assertTrue(
            "only the system may bind the accessibility service",
            manifest.contains("android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\""),
        )
        assertTrue(
            "the accessibility config must stay wired",
            manifest.contains("android:name=\"android.accessibilityservice\""),
        )
    }

    @Test
    fun theTelemetryBackendStaysRemovedFromTheMergedManifest() {
        // The datatransport backend ML Kit pulls in is removed on every version.
        assertTrue(manifest.contains("tools:node=\"remove\""))
        assertTrue(manifest.contains("TransportBackendDiscovery"))
    }

    // ------------------------------------------------------------------ theme

    @Test
    fun theApplicationUsesTheUnchangedLegacyThemeName() {
        // The internal theme identifier is part of the "do not rename" contract.
        assertTrue(manifest.contains("android:theme=\"@style/Theme.FaceGuard\""))
    }
}
