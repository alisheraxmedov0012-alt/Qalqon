package uz.faceguard.app.compliance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.requiresAccessibilityDisclosure

/**
 * Release Block 3 — ACC-03 and ACC-04.
 *
 * ACC-03: every user-initiated path that opens Android Accessibility settings must pass
 * through the single prominent-disclosure / affirmative-consent gate, and settings may be
 * launched *only* from the affirmative action.
 *
 * ACC-04: the disclosure body explains, in Uzbek, English and Russian, the purpose, the
 * foreground-package-only access, on-device use, the permission dependency and how to revoke.
 *
 * The routing rule is verified behaviourally ([requiresAccessibilityDisclosure]); the UI and
 * architecture guarantees are verified as source contracts (the repo's JVM convention for
 * Compose). Real-device rendering is NOT TESTED here.
 */
class AccessibilityConsentGateTest {

    private val gate by lazy { read("app/src/main/java/uz/faceguard/app/core/ui/qalqon/AccessibilityConsentGate.kt") }
    private val home by lazy { read("app/src/main/java/uz/faceguard/app/feature/home/HomeScreen.kt") }
    private val protection by lazy { read("app/src/main/java/uz/faceguard/app/feature/protection/ProtectionScreen.kt") }
    private val consentScreen by lazy { read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionConsentScreen.kt") }

    // ------------------------------------------------------------ ACC-03: rule

    @Test
    fun onlyTheAccessibilityCapabilityRequiresTheConsentGate() {
        assertTrue(ProtectionCapability.ACCESSIBILITY.requiresAccessibilityDisclosure())
        // Camera / Usage Access / Overlay keep their own existing permission flows.
        assertFalse(ProtectionCapability.CAMERA.requiresAccessibilityDisclosure())
        assertFalse(ProtectionCapability.USAGE_ACCESS.requiresAccessibilityDisclosure())
        assertFalse(ProtectionCapability.OVERLAY.requiresAccessibilityDisclosure())
    }

    // -------------------------------------------------- ACC-03: single dialog

    @Test
    fun theDisclosureWordingExistsOnlyInTheSharedGate() {
        // A second dialog with its own copy of the wording could drift; the body/agree
        // strings must be referenced from exactly one production file.
        val holders = mainKotlinFiles()
            .filter { it.readText().contains("R.string.accessibility_disclosure_body") }
            .map { it.name }
            .toSortedSet()
        assertEquals(setOf("AccessibilityConsentGate.kt"), holders)
    }

    @Test
    fun theGateLaunchesNothingItself() {
        // The shared dialog only reports the user's choice; the caller wires the launch.
        assertTrue(gate.contains("onConfirm: () -> Unit"))
        assertTrue(gate.contains("onDismiss: () -> Unit"))
        assertTrue(gate.contains("onClick = onConfirm"))
        assertFalse("the gate must not launch settings itself", gate.contains("startActivity"))
        assertFalse("the gate must not launch settings itself", gate.contains("openSettingsOrFallback"))
    }

    // ------------------------------------------- ACC-03: every path is gated

    @Test
    fun everyFeaturePathToAccessibilitySettingsPassesThroughTheGate() {
        // Any feature file that can open accessibility settings must (a) consult the gating
        // rule and (b) render the shared gate. This catches a newly added shortcut path.
        val offenders = featureKotlinFiles()
            .filter { file ->
                val text = file.readText()
                text.contains("accessibilitySettingsIntent(") || text.contains("capabilitySettingsIntent(")
            }
            .filter { file ->
                val text = file.readText()
                !(text.contains("requiresAccessibilityDisclosure(") && text.contains("AccessibilityDisclosureDialog("))
            }
            .map { it.name }
            .sorted()
        assertTrue("ungated accessibility-settings path(s): $offenders", offenders.isEmpty())
    }

    @Test
    fun noFeatureScreenConstructsAccessibilitySettingsDirectly() {
        // Only the two centralized intent factories may build the intent; a feature screen
        // that does so is a direct-launch bypass.
        val offenders = featureKotlinFiles()
            .filter { it.readText().contains("ACTION_ACCESSIBILITY_SETTINGS") }
            .map { it.name }
            .sorted()
        assertTrue("feature screens must not build the accessibility intent: $offenders", offenders.isEmpty())
    }

    @Test
    fun theAccessibilityIntentIsBuiltOnlyInTheCentralFactories() {
        val constructors = mainKotlinFiles()
            .filter { it.readText().contains("Settings.ACTION_ACCESSIBILITY_SETTINGS") }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            setOf("AccessibilityCapability.kt", "ProtectionCapabilities.kt"),
            constructors,
        )
    }

    // ----------------------------------- ACC-03: affirmative-only, decline safe

    @Test
    fun bothGatedScreensWireTheLaunchIntoTheConfirmActionOnly() {
        listOf("ProtectionScreen.kt" to protection, "HomeScreen.kt" to home).forEach { (name, src) ->
            assertTrue("$name must render the shared gate", src.contains("AccessibilityDisclosureDialog("))
            assertTrue("$name must consult the gating rule", src.contains("requiresAccessibilityDisclosure()"))
            assertTrue("$name must wire the launch in onConfirm", src.contains("onConfirm = {"))
            assertTrue(
                "$name must open settings from the confirm action",
                src.contains("openSettingsOrFallback("),
            )
            // Declining/dismissing only closes the dialog and opens nothing.
            assertTrue(
                "$name dismiss must not launch settings",
                src.contains("onDismiss = { showAccessibilityDisclosure = false }"),
            )
            assertFalse(
                "$name must not launch settings from onDismiss",
                Regex("""onDismiss\s*=\s*\{[^}]*openSettingsOrFallback""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun theAccessibilitySettingsEntryPointStillShowsTheDisclosureFirst() {
        // Protection requirements row: tapping the accessibility action opens the gate
        // (it must not launch settings on tap).
        assertTrue(protection.contains("onOpenAccessibility = { showAccessibilityDisclosure = true }"))
        assertFalse(protection.contains("onOpenAccessibility = { context.startActivity"))
    }

    // ---------------------------------------------------- ACC-04: disclosure body

    private fun body(locale: String): String =
        read("app/src/main/res/$locale/strings.xml")
            .let { Regex("""name="accessibility_disclosure_body">([^<]*)<""").find(it)?.groupValues?.get(1) }
            ?: error("no accessibility_disclosure_body in $locale")

    @Test
    fun everyLocaleExplainsThePermissionDependency() {
        // Without Accessibility, the intended protection cannot operate as designed.
        assertTrue("en dependency", body("values-en").contains("cannot work as designed"))
        assertTrue("uz dependency", body("values").contains("ishlamaydi"))
        assertTrue("ru dependency", body("values-ru").contains("не может работать"))
    }

    @Test
    fun everyLocaleExplainsHowToRevokeThePermission() {
        assertTrue("en revoke", body("values-en").contains("You can turn off"))
        assertTrue("uz revoke", body("values").contains("chirib qo"))
        assertTrue("ru revoke", body("values-ru").contains("Отключить доступ"))
    }

    @Test
    fun everyLocaleStatesForegroundPackageOnlyAndOnDeviceUse() {
        assertTrue("en package", body("values-en").contains("package name"))
        assertTrue("uz package", body("values").contains("paket nomini"))
        assertTrue("ru package", body("values-ru").contains("имя пакета"))

        assertTrue("en on-device", body("values-en").contains("nothing is sent off this device"))
        assertTrue("uz on-device", body("values").contains("qurilmadan tashqariga yuborilmaydi"))
        assertTrue("ru on-device", body("values-ru").contains("не отправляются"))
    }

    @Test
    fun everyLocaleStatesItDoesNotReadScreenContent() {
        assertTrue("en no-content", body("values-en").contains("never reads screen text"))
        assertTrue("uz no-content", body("values").contains("hech qachon o"))
        assertTrue("ru no-content", body("values-ru").contains("никогда не читаются"))
    }

    // ------------------------------------------- subscription consent unchanged

    @Test
    fun theSubscriptionConsentStaysIndependentOfTheAccessibilityDisclosure() {
        assertFalse(
            "the subscription consent must not reference the accessibility disclosure",
            consentScreen.contains("accessibility_disclosure"),
        )
        // And the subscription consent gate itself is unchanged.
        assertTrue(consentScreen.contains("SubscriptionConsent.canStartPurchase("))
    }

    // ------------------------------------------------------------------ helpers

    private fun mainRoot() = File(repoRoot(), "app/src/main/java/uz/faceguard/app")

    private fun mainKotlinFiles(): List<File> =
        mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun featureKotlinFiles(): List<File> =
        File(mainRoot(), "feature").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

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
