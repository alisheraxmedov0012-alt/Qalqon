package uz.faceguard.app.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Stage 10 (Privacy & Security Hardening): the **release build** and **attack surface**.
 *
 * The release APK is what ships; these assert its hardening so a build-config regression
 * (minification silently turned off, a debug flag shipped, an extra exported component)
 * fails here rather than in the wild. The R8 step itself is verified end-to-end by the
 * build gate (`:app:minifyReleaseWithR8` / `assembleRelease`); this pins the config.
 */
class Stage10ReleaseHardeningTest {

    private val appGradle = read("app/build.gradle.kts")
    private val proguard = read("app/proguard-rules.pro")
    private val manifest = read("app/src/main/AndroidManifest.xml")

    // ------------------------------------------------------------- R8 / shrinking

    @Test
    fun releaseEnablesR8MinificationAndResourceShrinking() {
        assertTrue(
            "release must enable R8 minification (attack-surface reduction)",
            appGradle.contains("isMinifyEnabled = true"),
        )
        assertTrue(
            "release must enable resource shrinking",
            appGradle.contains("isShrinkResources = true"),
        )
        assertFalse(
            "minification must never be turned back off for release",
            appGradle.contains("isMinifyEnabled = false"),
        )
        assertTrue(
            "proguard rules must be applied to release",
            appGradle.contains("proguard-android-optimize.txt"),
        )
    }

    @Test
    fun theProguardRulesAreNotABlanketKeepAll() {
        // A keep-all defeats both shrinking and obfuscation; it was removed once and
        // must never come back. Comments are stripped first (the rule file *documents*
        // the anti-pattern), so only real directives are inspected.
        val directives = proguard.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        assertFalse(
            "a blanket -keep class ** defeats shrinking/obfuscation",
            Regex("""-keep\s+class\s+\*\*""").containsMatchIn(directives),
        )
        assertFalse(
            "a blanket keepclassmembers ** defeats shrinking/obfuscation",
            Regex("""-keepclassmembers\s+class\s+\*\*""").containsMatchIn(directives),
        )
    }

    @Test
    fun theProguardRulesKeepTheKnownReflectiveSurfaces() {
        // The runtime-loaded/reflective surfaces that must survive R8.
        assertTrue("TFLite interpreter must be kept", proguard.contains("org.tensorflow.lite"))
        assertTrue("the app entry points must be kept", proguard.contains("uz.faceguard.app.FaceGuardApp"))
        assertTrue("the accessibility service must be kept", proguard.contains("ProtectionAccessibilityService"))
        assertTrue("the boot receiver must be kept", proguard.contains("ProtectionBootReceiver"))
        assertTrue("the foreground service must be kept", proguard.contains("ProtectionForegroundService"))
        assertTrue(
            "enums read by name at runtime must keep values/valueOf",
            proguard.contains("valueOf(java.lang.String)"),
        )
    }

    // --------------------------------------------------------------- debug surface

    @Test
    fun theManifestNeverEnablesDebuggable() {
        assertFalse(
            "android:debuggable must never be baked into the shipped manifest",
            manifest.contains("android:debuggable=\"true\""),
        )
    }

    // ------------------------------------------------------------- exported surface

    private fun exportedComponents(): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(repoRoot(), "app/src/main/AndroidManifest.xml"))
        val root = doc.documentElement
        val result = mutableListOf<String>()
        listOf("activity", "service", "receiver", "provider").forEach { tag ->
            val nodes = root.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                if (el.getAttribute("android:exported") == "true") {
                    result += el.getAttribute("android:name")
                }
            }
        }
        return result.sorted()
    }

    @Test
    fun onlyTheIntendedComponentsAreExported() {
        // Exactly three, and exactly these three:
        //  - the launcher activity (must be exported),
        //  - the BOOT_COMPLETED receiver (system broadcast; protected),
        //  - the accessibility service (the system binds it; guarded by
        //    BIND_ACCESSIBILITY_SERVICE).
        assertEquals(
            listOf(
                ".MainActivity",
                ".core.accessibility.ProtectionAccessibilityService",
                ".core.protection.ProtectionBootReceiver",
            ).sorted(),
            exportedComponents(),
        )
    }

    @Test
    fun theProtectionForegroundServiceIsNotExported() {
        assertTrue(
            "the foreground service must be non-exported",
            manifest.contains("android:name=\".core.protection.ProtectionForegroundService\"")
                && manifest.contains("android:exported=\"false\""),
        )
    }

    @Test
    fun theAccessibilityServiceStaysGuardedByTheSystemPermission() {
        assertTrue(
            "only the system may bind the accessibility service",
            manifest.contains("android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\""),
        )
    }

    // --------------------------------------------------------------- placeholder

    @Test
    fun noDebugOrTestOnlyComponentIsDeclaredInTheManifest() {
        // A debug/test-only provider or activity (e.g. a leak-canary) must not ship.
        assertFalse(manifest.contains("leakcanary", ignoreCase = true))
        assertFalse(manifest.contains("TestActivity"))
        assertFalse(manifest.contains("tools:ignore=\"MissingClass\""))
    }

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
}
