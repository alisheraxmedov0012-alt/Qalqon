package uz.faceguard.app.compliance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8 (Google Play compliance) source/resource contracts. These pin the
 * machine-checkable parts of QALQON's Play compliance so a future change cannot
 * silently regress them. They are JVM/static only — the Play Console configuration
 * and the real Google review are NOT verifiable here.
 *
 * Policy references:
 * - Target API level: support.google.com/googleplay/android-developer/answer/11926878
 *   (new apps and updates must target Android 16 / API 36 from 2026-08-31).
 * - Accessibility API use: support.google.com/googleplay/android-developer/answer/16558241
 *   (non-accessibility-tool apps need an in-app disclosure + affirmative consent).
 * - User data / sensitive permissions: support.google.com/googleplay/android-developer/answer/10144311
 * - Data safety: support.google.com/googleplay/android-developer/answer/10787469
 */
class PlayComplianceContractTest {

    private val uz by lazy { read("app/src/main/res/values/strings.xml") }
    private val en by lazy { read("app/src/main/res/values-en/strings.xml") }
    private val ru by lazy { read("app/src/main/res/values-ru/strings.xml") }
    private val appGradle by lazy { read("app/build.gradle.kts") }
    private val protection by lazy { read("app/src/main/java/uz/faceguard/app/feature/protection/ProtectionScreen.kt") }
    // Release Block 3 (ACC-03): the disclosure wording/dialog is centralized in one gate.
    private val accessibilityGate by lazy {
        read("app/src/main/java/uz/faceguard/app/core/ui/qalqon/AccessibilityConsentGate.kt")
    }
    private val manifest by lazy { read("app/src/main/AndroidManifest.xml") }
    private val accessibilityConfig by lazy { read("app/src/main/res/xml/accessibility_service_config.xml") }
    private val inventory by lazy { read("gradle/libs.versions.toml") }

    // ------------------------------------------------------------ target API 36

    @Test
    fun theAppTargetsApi36AsGooglePlayRequires() {
        assertTrue("compileSdk must be 36", appGradle.contains("compileSdk = 36"))
        assertTrue("targetSdk must be 36", appGradle.contains("targetSdk = 36"))
    }

    @Test
    fun theToolchainSupportsApi36() {
        // AGP >= 8.9.1 is required to compile API 36.
        val agp = Regex("""agp = "([0-9.]+)"""").find(inventory)?.groupValues?.get(1)
        assertNotNull("AGP version must be declared", agp)
        val parts = agp!!.split(".").map { it.toInt() }
        val major = parts[0]
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        val atLeast = major > 8 || (major == 8 && (minor > 9 || (minor == 9 && patch >= 1)))
        assertTrue("AGP $agp must be >= 8.9.1 for compileSdk 36", atLeast)
    }

    // ------------------------------------------------- accessibility disclosure

    @Test
    fun theAccessibilityServiceIsNotDeclaredAsADisabilityTool() {
        // QALQON is a parental-control app, not a disability support tool, so
        // isAccessibilityTool must not be set.
        assertFalse(
            "isAccessibilityTool must not be present in the service config",
            accessibilityConfig.contains("isAccessibilityTool"),
        )
        assertFalse(
            "the manifest must not set an accessibility-tool flag",
            manifest.contains("isAccessibilityTool"),
        )
    }

    @Test
    fun theAccessibilityServiceDoesNotRetrieveWindowContent() {
        assertTrue(
            "canRetrieveWindowContent must stay false",
            accessibilityConfig.contains("android:canRetrieveWindowContent=\"false\""),
        )
    }

    @Test
    fun theProtectionScreenShowsAnAccessibilityDisclosureBeforeEnabling() {
        // The action must open a disclosure, not jump straight to system settings.
        assertTrue(
            "tapping enable must show the disclosure",
            protection.contains("onOpenAccessibility = { showAccessibilityDisclosure = true }"),
        )
        assertTrue(
            "the screen must render the shared disclosure gate",
            protection.contains("AccessibilityDisclosureDialog("),
        )
        // The affirmative-consent wording lives in the single shared gate (ACC-03).
        assertTrue(
            "the disclosure must be an affirmative-consent dialog",
            accessibilityGate.contains("R.string.accessibility_disclosure_body") &&
                accessibilityGate.contains("R.string.accessibility_disclosure_agree"),
        )
        assertTrue(
            "settings are opened only after consent",
            protection.contains("accessibilitySettingsIntent()"),
        )
        assertFalse(
            "the screen must not open settings directly on tap",
            protection.contains("onOpenAccessibility = { context.startActivity"),
        )
    }

    @Test
    fun theAccessibilityDisclosureExistsInEveryLocale() {
        val keys = listOf(
            "accessibility_disclosure_title",
            "accessibility_disclosure_body",
            "accessibility_disclosure_agree",
            "accessibility_disclosure_decline",
        )
        keys.forEach { key ->
            assertTrue("$key missing in uz", uz.contains("name=\"$key\""))
            assertTrue("$key missing in en", en.contains("name=\"$key\""))
            assertTrue("$key missing in ru", ru.contains("name=\"$key\""))
        }
    }

    // ---------------------------------------------------- privacy / no-INTERNET

    @Test
    fun theManifestKeepsTheAppOffline() {
        // INTERNET/ACCESS_NETWORK_STATE must remain explicit removals.
        listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE").forEach { p ->
            assertTrue(
                "$p must be declared as tools:node=remove",
                manifest.contains(p) && manifest.contains("tools:node=\"remove\""),
            )
        }
    }

    @Test
    fun billingIsDeclaredButNoLocationOrQueryAllPackagesIs() {
        // The Billing library (subscription) is used, but no broad/location permissions.
        assertTrue("billing must be wired", appGradle.contains("libs.billing"))
        assertFalse("QUERY_ALL_PACKAGES must not be declared", manifest.contains("QUERY_ALL_PACKAGES"))
        assertFalse("no location permission", manifest.contains("ACCESS_FINE_LOCATION") || manifest.contains("ACCESS_COARSE_LOCATION"))
    }

    @Test
    fun theForegroundServiceDeclaresItsTypesAndSubtype() {
        // Play FGS review needs the declared types + a specialUse subtype justification.
        assertTrue(manifest.contains("foregroundServiceType=\"camera|specialUse\""))
        assertTrue(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_CAMERA"))
    }

    @Test
    fun biometricValuesAreNeverLogged() {
        // No log call may interpolate a biometric value (the template/embedding/features
        // variables). A message such as "embedding inference failed" is a failure notice,
        // not the data; only interpolated *values* are a privacy violation.
        val main = File(repoRoot(), "app/src/main/java/uz/faceguard/app")
        val offending = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        line.contains("Log.") &&
                            Regex("""\$\{?(template|embedding|features|faceTemplateRef|plainRef)\b""")
                                .containsMatchIn(line)
                    }
                    .map { (i, _) -> "${file.name}:${i + 1}" }
            }
            .toList()
        assertTrue("no biometric value may be logged: $offending", offending.isEmpty())
    }

    @Test
    fun aPrivacyScreenExists() {
        assertTrue(
            "an in-app privacy screen must exist",
            File(repoRoot(), "app/src/main/java/uz/faceguard/app/feature/privacy/PrivacyScreen.kt").isFile,
        )
    }

    @Test
    fun theAccountResetDeletesAllDataAndTheBiometricKey() {
        // Account deletion must delete all data and the Keystore key (Play requirement).
        val reset = read("app/src/main/java/uz/faceguard/app/data/repository/ResetRepositoryImpl.kt")
        assertTrue("reset must delete the biometric key", reset.contains("keyProvider.deleteKey()"))
        assertTrue("reset must clear the session", reset.contains("sessionManager.clearSession()"))
        assertTrue("reset must clear settings", reset.contains("settingsStore.clearAll()"))
    }

    @Test
    fun theAccessibilityServiceIsNotExportedAsAGenericBoundService() {
        // The service must require BIND_ACCESSIBILITY_SERVICE (system-only caller).
        assertTrue(
            "the accessibility service must be guarded by BIND_ACCESSIBILITY_SERVICE",
            manifest.contains("BIND_ACCESSIBILITY_SERVICE"),
        )
    }

    // ------------------------------------------------------ price not hardcoded

    @Test
    fun theSubscriptionScreenDoesNotHardcodeAPrice() {
        val subscription = read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionScreen.kt")
        assertFalse(
            "the subscription screen must not hardcode a price",
            Regex("""[$€£]\s?\d""").containsMatchIn(subscription),
        )
    }

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
