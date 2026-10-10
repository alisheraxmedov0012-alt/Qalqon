package uz.faceguard.app.compliance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release Block 3 (Objective C/E): the Accessibility service contract and the
 * no-network / no-content-access invariants the disclosure claims rest on.
 *
 * These verify structural, architectural invariants rather than one keyword: e.g. "no
 * node-tree/content API is referenced anywhere", "exactly one AccessibilityService exists",
 * and "the shipped app cannot transmit" (INTERNET removed + no network dependency). A file
 * that happened to mention a keyword elsewhere cannot satisfy them.
 *
 * Real-device rendering/behaviour is NOT TESTED here.
 */
class AccessibilityServiceContractTest {

    private val config by lazy { read("app/src/main/res/xml/accessibility_service_config.xml") }
    private val manifest by lazy { read("app/src/main/AndroidManifest.xml") }
    private val service by lazy {
        read("app/src/main/java/uz/faceguard/app/core/accessibility/ProtectionAccessibilityService.kt")
    }
    private val eventFilter by lazy {
        read("app/src/main/java/uz/faceguard/app/core/accessibility/AccessibilityEventFilter.kt")
    }
    private val accessibilityOverlayWindow by lazy {
        read("app/src/main/java/uz/faceguard/app/core/accessibility/AccessibilityOverlayWindow.kt")
    }
    private val legacyOverlayController by lazy {
        read("app/src/main/java/uz/faceguard/app/core/protection/OverlayControllerImpl.kt")
    }
    private val appGradle by lazy { read("app/build.gradle.kts") }
    private val catalog by lazy { read("gradle/libs.versions.toml") }

    // --------------------------------------------------- exactly one service

    @Test
    fun exactlyOneAccessibilityServiceIsDeclared() {
        val subclasses = mainKotlinFiles()
            .filter { it.readText().contains(": AccessibilityService()") || it.readText().contains("extends AccessibilityService") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("ProtectionAccessibilityService.kt"), subclasses)
    }

    // ------------------------------------------- no content / node-tree access

    @Test
    fun noScreenContentOrGestureApiIsReferencedAnywhereInTheApp() {
        val banned = listOf(
            "getRootInActiveWindow",
            "getWindows(",
            "AccessibilityNodeInfo",
            "AccessibilityWindowInfo",
            "performGlobalAction",
            "AccessibilityNodeProvider",
            "takeScreenshot",
            "FLAG_RETRIEVE_INTERACTIVE_WINDOWS",
            "FLAG_REQUEST_FILTER_KEY_EVENTS",
            "FLAG_REQUEST_TOUCH_EXPLORATION_MODE",
        )
        val offenders = mainKotlinFiles().flatMap { file ->
            banned.filter { file.readText().contains(it) }.map { "${file.name}: $it" }
        }
        assertTrue("no window-content/gesture API may be used: $offenders", offenders.isEmpty())
    }

    @Test
    fun theServiceConfigOnlyAllowsForegroundWindowTransitions() {
        assertTrue(config.contains("android:canRetrieveWindowContent=\"false\""))
        assertTrue(config.contains("android:accessibilityFlags=\"flagDefault\""))
        assertTrue(config.contains("android:accessibilityFeedbackType=\"feedbackGeneric\""))
        assertTrue(config.contains("typeWindowStateChanged"))
        assertTrue(config.contains("typeWindowsChanged"))
        // Not flagged as an accessibility tool, and no gesture capability requested.
        assertFalse(config.contains("isAccessibilityTool"))
        assertFalse(config.contains("canPerformGestures"))
        // Exactly the two transition events, no other event family.
        val eventAttr = Regex("""android:accessibilityEventTypes="([^"]*)"""").find(config)?.groupValues?.get(1).orEmpty()
        assertEquals(
            setOf("typeWindowStateChanged", "typeWindowsChanged"),
            eventAttr.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    @Test
    fun theEventFilterRejectsEverythingButWindowTransitions() {
        // The filter is the runtime boundary; it must name exactly the two allowed types.
        assertTrue(eventFilter.contains("TYPE_WINDOW_STATE_CHANGED"))
        assertTrue(eventFilter.contains("TYPE_WINDOWS_CHANGED"))
        // And the service must consult it before reading anything.
        assertTrue(service.contains("AccessibilityEventFilter.isRelevant(type)"))
    }

    @Test
    fun theServiceReadsOnlyThePackageNameAndNeverContent() {
        // The only event field touched is the package name.
        assertTrue(service.contains("event.packageName"))
        // No content getters are used from the event.
        listOf("event.text", "event.contentDescription", "event.source", "event.parcelableData")
            .forEach { assertFalse("the service must not read $it", service.contains(it)) }
    }

    // ------------------------------------------------- system-only binding

    @Test
    fun theServiceIsBoundOnlyByTheSystem() {
        assertTrue(manifest.contains("android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\""))
        assertTrue(manifest.contains("android:name=\".core.accessibility.ProtectionAccessibilityService\""))
        assertTrue(manifest.contains("android.accessibilityservice.AccessibilityService"))
        assertTrue(manifest.contains("android:name=\"android.accessibilityservice\""))
    }

    // ------------------------------------------------- no transmission capability

    @Test
    fun theShippedAppCannotTransmitData() {
        // Offline-first: INTERNET/ACCESS_NETWORK_STATE are only ever removed in the manifest,
        // and no networking dependency is declared.
        assertTrue(manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""))
        listOf("okhttp", "retrofit", "firebase", "crashlytics", "analytics", "volley", "ktor")
            .forEach { lib ->
                assertFalse("no $lib dependency may be declared", appGradle.lowercase().contains(lib))
                assertFalse("no $lib dependency may be declared", catalog.lowercase().contains(lib))
            }
        // And no raw networking API is used in production code.
        mainKotlinFiles().forEach { file ->
            val text = file.readText()
            listOf("java.net.", "Socket(", "HttpURLConnection", "WebView(").forEach { api ->
                assertFalse("${file.name} must not use $api", text.contains(api))
            }
        }
    }

    // ---------------------------------------- overlay is not equivalent to fallback

    @Test
    fun theAccessibilityOverlayConsumesTouchesAndTheFallbackDoesNot() {
        // The real input block: TYPE_ACCESSIBILITY_OVERLAY, and the *actual window flags*
        // must not include FLAG_NOT_TOUCHABLE. (Checked on the flags expression, not the
        // whole file, because the class KDoc contrasts itself with the legacy fallback.)
        val flagsRegion = accessibilityOverlayWindow
            .substringAfter("fun layoutParams()")
            .substringBefore("return params")
            // Ignore `//` comments (which deliberately mention the legacy flag) and check
            // the actual compiled flag expression only.
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(flagsRegion.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue(flagsRegion.contains("FLAG_NOT_FOCUSABLE"))
        assertTrue(flagsRegion.contains("FLAG_SECURE"))
        assertFalse(
            "the accessibility overlay's window flags must stay touchable",
            flagsRegion.contains("FLAG_NOT_TOUCHABLE"),
        )
        // The honest visual fallback: TYPE_APPLICATION_OVERLAY with FLAG_NOT_TOUCHABLE.
        assertTrue(legacyOverlayController.contains("TYPE_APPLICATION_OVERLAY"))
        assertTrue(legacyOverlayController.contains("FLAG_NOT_TOUCHABLE"))
    }

    // ------------------------------------------------------------------ helpers

    private fun mainKotlinFiles(): List<File> =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
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
