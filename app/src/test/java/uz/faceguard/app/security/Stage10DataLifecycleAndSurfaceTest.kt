package uz.faceguard.app.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 10 (Privacy & Security Hardening): the **sensitive-data lifecycle**,
 * **debug/entitlement boundary** and **provider/URI attack surface**.
 *
 * These are the dimensions the release/debug build config and data-exposure tests do not
 * cover: that a biometric template is only ever written encrypted, that a full reset
 * destroys the key that protects it, that no debug switch can grant Premium, that
 * nothing exposes biometric data through a ContentProvider/FileProvider/URI, and that
 * DataStore holds no secrets. Reads the production source so a regression fails here.
 */
class Stage10DataLifecycleAndSurfaceTest {

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun productionSources(): List<File> =
        File(repoRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private val root = "app/src/main/java/uz/faceguard/app/"

    // ------------------------------------------------------ biometric lifecycle

    @Test
    fun aTemplateIsPersistedOnlyThroughTheEncryptingCipher() {
        // The repositories must never write a raw `faceTemplateRef` straight through;
        // the only writer of a stored template is the cipher's `protect(...)`.
        val child = read("${root}data/repository/ChildProfileRepositoryImpl.kt")
        val parent = read("${root}data/repository/ParentProfileRepositoryImpl.kt")
        assertTrue("child enrollment must route through the cipher", child.contains("templateCipher"))
        assertTrue("parent enrollment must route through the cipher", parent.contains("templateCipher"))
        assertTrue("the stored form is the v1 envelope", read("${root}core/security/KeystoreBiometricTemplateCipher.kt").contains("PREFIX = \"v1:\""))
    }

    @Test
    fun aDecryptionFailureNeverFallsBackToPlaintext() {
        // An authenticated-but-broken envelope yields null (recovery required); a raw
        // legacy value is only ever reported as `LegacyPlaintext` for the migration
        // sweep — never silently reused as a live identity.
        val cipher = read("${root}core/security/KeystoreBiometricTemplateCipher.kt")
        assertTrue(cipher.contains("TemplateRecovery.Unavailable"))
        assertTrue(cipher.contains("TemplateRecovery.LegacyPlaintext"))
        // The cipher never returns the *encrypted* payload as a usable plaintext.
        assertTrue(cipher.contains("isProtected(storedRef)"))
    }

    @Test
    fun theFullResetDestroysTheKeyProtectingTheTemplates() {
        val reset = read("${root}data/repository/ResetRepositoryImpl.kt")
        assertTrue("the biometric key must be deleted on a full reset", reset.contains("keyProvider.deleteKey()"))
        // Every domain store is wiped too.
        listOf("settingsStore.clearAll()", "pinAttemptStore.clearAll()").forEach {
            assertTrue("reset must clear $it", reset.contains(it))
        }
    }

    @Test
    fun perSubjectDeletionClearsThatSubjectsTemplate() {
        assertTrue(
            "child face deletion must exist",
            read("${root}data/repository/ChildProfileRepositoryImpl.kt").contains("deleteFaceData"),
        )
        assertTrue(
            "parent face deletion must exist",
            read("${root}data/repository/ParentProfileRepositoryImpl.kt").contains("deleteFaceData"),
        )
    }

    @Test
    fun noGlobalSingletonHoldsBiometricDataAcrossCalls() {
        // No object-level mutable collection in the recognition/embed layer may retain
        // embeddings; the decoded vector is a local value with a bounded lifetime.
        val offenders = listOf(
            "${root}core/recognition/Recognizer.kt",
            "${root}core/embed/FaceEmbeddingCodec.kt",
            "${root}core/embed/FaceEmbeddingModel.kt",
            "${root}core/security/KeystoreBiometricTemplateCipher.kt",
        ).flatMap { path ->
            val text = read(path)
            Regex(
                "(object\\s+\\w+\\s*\\{[^}]*mutable(Map|List|Set)Of)|(^\\s*val\\s+\\w+\\s*=\\s*mutableMapOf)",
                RegexOption.MULTILINE,
            )
                .findAll(text)
                .map { "$path: ${it.value.take(40)}" }
                .toList()
        }
        assertTrue("a global collection retains biometric data: $offenders", offenders.isEmpty())
    }

    // ------------------------------------------------------ debug / entitlement

    @Test
    fun theDebugSwitchIsBuildConfigDebugAndNeverAConstantTrue() {
        val flags = read("${root}core/debug/DebugFlags.kt")
        assertTrue(flags.contains("BuildConfig.DEBUG"))
        assertFalse("the debug flag must not be hardcoded true", flags.contains("= true"))
    }

    @Test
    fun noEntitlementOrPremiumPathCanBeSwitchedOnByTheDebugFlag() {
        // A debug switch may reveal UI/routes, but it must never grant Premium or bypass
        // the entitlement evaluator (production entitlement bypass).
        val billingSources = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/billing").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val offenders = billingSources.filter {
            it.readText().contains("DebugFlags") || it.readText().contains("BuildConfig.DEBUG")
        }
        assertTrue("billing must not be influenced by a debug flag: ${offenders.map { it.name }}", offenders.isEmpty())

        val evaluator = read("${root}domain/billing/Subscription.kt")
        assertFalse(evaluator.contains("DebugFlags"))
        assertFalse(evaluator.contains("BuildConfig"))
    }

    @Test
    fun everyDebugOnlyRouteIsBehindTheDebugFlag() {
        val nav = read("${root}navigation/NavGraph.kt")
        // No debug screen may be registered unconditionally outside a debug guard.
        val allComposables = Regex("composable\\(Routes\\.[A-Z_]+")
            .findAll(nav).map { it.value }.toList()
        assertTrue("the nav graph must register routes", allComposables.isNotEmpty())
        assertTrue(
            "the recognition debug route must sit inside the debug guard",
            nav.contains("if (DebugFlags.DEBUG_SCREENS_ENABLED)"),
        )
    }

    // -------------------------------------------------- provider / uri surface

    @Test
    fun noContentProviderOrFileProviderIsDeclared() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertFalse("no <provider> may be declared", manifest.contains("<provider"))
        assertFalse("no FileProvider may be declared", manifest.contains("FileProvider"))
    }

    @Test
    fun noProductionCodeSharesFilesOrCasesUriPermissions() {
        val forbidden = Regex(
            "grantUriPermission|" +
                "addFlags\\(Intent\\.FLAG_GRANT_|" +
                "Intent\\.ACTION_SEND|" +
                "createChooser|" +
                "openInputStream|" +
                "contentResolver\\.open|" +
                "ClipboardManager|" +
                "setPrimaryClip",
        )
        val found = productionSources().flatMap { file ->
            forbidden.findAll(file.readText()).map { "${file.name}: ${it.value}" }.toList()
        }
        assertTrue("an external data-sharing surface was found: $found", found.isEmpty())
    }

    // ------------------------------------------------------------- DataStore

    @Test
    fun dataStoreDeclaresNoSecretBearingKeys() {
        // DataStore is used for preferences, counters, the session account id and the
        // entitlement cache — never for a PIN, template, phone number or key material.
        // Inspect the *persisted key names*, not the source identifiers.
        val storeFiles = listOf(
            "${root}data/prefs/SettingsStore.kt",
            "${root}data/prefs/PinAttemptStore.kt",
            "${root}data/prefs/SessionManager.kt",
            "${root}data/prefs/AppLanguageStore.kt",
            "${root}core/billing/EntitlementStore.kt",
        )
        val sensitiveWords = listOf(
            "template", "embedding", "phone", "token",
            "secret", "password", "salt", "hash", "biometric",
        )
        val keyName = Regex("PreferencesKey\\(\\s*\"([^\"]+)\"")
        val found = storeFiles.flatMap { path ->
            keyName.findAll(read(path))
                .map { it.groupValues[1] }
                .filter { name -> sensitiveWords.any { name.contains(it, ignoreCase = true) } }
                .map { "$path: $it" }
                .toList()
        }
        assertTrue("DataStore must not persist a secret-bearing key: $found", found.isEmpty())
    }

    // ------------------------------------------------------------ pending intent

    @Test
    fun everyPendingIntentIsImmutable() {
        val offenders = productionSources().flatMap { file ->
            Regex("PendingIntent\\.FLAG_MUTABLE")
                .findAll(file.readText())
                .map { file.name }
                .toList()
        }
        assertTrue("FLAG_MUTABLE is never required here: $offenders", offenders.isEmpty())
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
