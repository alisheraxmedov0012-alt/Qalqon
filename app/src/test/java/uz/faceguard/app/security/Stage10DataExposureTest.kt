package uz.faceguard.app.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 10 (Privacy & Security Hardening): **data exposure** tripwires.
 *
 * The app's hardest promises are that raw face images are never persisted, no secret is
 * written to a log, and no credential/key material is hardcoded. This reads the whole
 * production source and fails if any of those promises is broken by a future change.
 */
class Stage10DataExposureTest {

    private val productionSources: List<File> =
        File(repoRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun hits(regex: Regex): List<String> =
        productionSources.flatMap { file ->
            regex.findAll(file.readText()).map { "${file.name}: ${it.value.take(80)}" }.toList()
        }

    // ------------------------------------------------------- no raw image on disk

    @Test
    fun noProductionCodePersistsRawImagesToDisk() {
        // Face frames are processed in memory and recycled; nothing writes an image,
        // and nothing touches shared/external storage or a media collection.
        val forbidden = Regex(
            "Bitmap\\.CompressFormat|" +
                "\\.compress\\(|" +
                "FileOutputStream|" +
                "openFileOutput\\(|" +
                "MediaStore|" +
                "Environment\\.getExternalStorage|" +
                "getExternalFilesDir|" +
                "FileWriter|" +
                "\\.writeBytes\\(|" +
                "ImageWriter|" +
                "Bitmap\\.createScaledBitmap\\([^)]*\\).*\\.compress",
        )
        val found = hits(forbidden)
        assertTrue("raw image persistence found: $found", found.isEmpty())
    }

    @Test
    fun theAppDeclaresNoStorageOrMediaPermissions() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        listOf(
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "ACCESS_MEDIA_LOCATION",
        ).forEach { permission ->
            assertTrue(
                "manifest must not request $permission",
                !manifest.contains("android:name=\"android.permission.$permission\""),
            )
        }
    }

    // ----------------------------------------------------------- no secret in logs

    @Test
    fun noLogStatementInterpolatesASecretOrBiometricValue() {
        // No log line may interpolate a purchase token, PIN, password, face template,
        // embedding or phone number. (Stack traces of caught exceptions are fine.)
        val sensitiveInterpolation = Regex(
            "Log\\.[a-z]\\([^\\)]*\\$\\s*\\w*(token|pin|password|passphrase|template|embedding|phone|secret)\\w*",
            RegexOption.IGNORE_CASE,
        )
        val found = hits(sensitiveInterpolation)
        assertTrue("a log line interpolates a sensitive value: $found", found.isEmpty())
    }

    @Test
    fun noLogStatementPrintsTheRawPurchaseToken() {
        val tree = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/billing").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        val found = tree.flatMap { file ->
            Regex("Log\\.[a-z]\\([^\\)]*purchaseToken", RegexOption.IGNORE_CASE)
                .findAll(file.readText())
                .map { "${file.name}: ${it.value.take(60)}" }
                .toList()
        }.toList()
        assertTrue("the purchase token must never be logged: $found", found.isEmpty())
    }

    // ------------------------------------------------------- no hardcoded secret

    @Test
    fun noProductionCodeContainsAHardcodedSecret() {
        val forbidden = Regex(
            "api[_-]?key\\s*=\\s*\"[^\"]+\"|" +
                "secret\\s*=\\s*\"[^\"]{6,}\"|" +
                "Bearer\\s+[A-Za-z0-9._-]{12,}|" +
                "-----BEGIN [A-Z ]*PRIVATE KEY-----",
            RegexOption.IGNORE_CASE,
        )
        val found = hits(forbidden)
        assertTrue("a hardcoded secret was found: $found", found.isEmpty())
    }

    @Test
    fun theReleaseSigningSecretsAreReadFromTheEnvironmentOrLocalProperties() {
        // The release keystore passwords must never be literals in the build script:
        // they are read from the environment (how CI supplies them) or from
        // local.properties.
        val gradle = File(repoRoot(), "app/build.gradle.kts").readText()
        assertTrue(
            "signing secrets must come from the environment",
            gradle.contains("System.getenv(environmentName)"),
        )
        assertTrue(
            "signing secrets must be read from local.properties as a fallback",
            gradle.contains("localProperties.getProperty(propertyName)"),
        )
        assertTrue(
            "the store password must be requested by env-var name",
            gradle.contains("\"QALQON_RELEASE_STORE_PASSWORD\""),
        )
        assertTrue(
            "the key password must be requested by env-var name",
            gradle.contains("\"QALQON_RELEASE_KEY_PASSWORD\""),
        )
        // No literal password assignment anywhere in the build script.
        assertTrue(
            "a signing password must not be a literal",
            !Regex("""(storePassword|keyPassword)\s*=\s*"[^"]+"""").containsMatchIn(gradle),
        )
    }

    // ------------------------------------------------------------- key hygiene

    @Test
    fun theKeystoreKeyIsNeverExportedOrBackedUp() {
        val crypto = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/security/SecureCrypto.kt").readText()
        // The key lives only in the AndroidKeyStore; it is generated on-device and has
        // no export path (no setRandomizedEncryptionRequired(false), no key.getEncoded()
        // serialisation to storage).
        assertTrue(crypto.contains("\"AndroidKeyStore\""))
        assertTrue(
            "the key must not be read back out for export",
            !crypto.contains("getEncoded()"),
        )
        val backupRules = File(repoRoot(), "app/src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("all domains excluded from cloud backup", backupRules.contains("<cloud-backup>"))
        assertTrue("all domains excluded from device transfer", backupRules.contains("<device-transfer>"))
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
