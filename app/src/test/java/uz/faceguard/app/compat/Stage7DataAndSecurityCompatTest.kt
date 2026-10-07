package uz.faceguard.app.compat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7: local data, storage and security compatibility (API 26–36).
 *
 * QALQON is local-first: Room, DataStore and Keystore-encrypted templates. None of
 * these may depend on a platform range narrower than the app's own. This guard reads
 * the source and asserts the version-safe choices, so a change that would break an
 * older (or newer) level fails here.
 */
class Stage7DataAndSecurityCompatTest {

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

    // ------------------------------------------------------------------ Room

    @Test
    fun roomUsesAdditiveMigrationsAndNeverADestructiveFallback() {
        val appModule = read("${root}di/AppModule.kt")
        assertTrue("the migration chain must be registered", appModule.contains("addMigrations("))
        assertFalse(
            "a destructive fallback would drop user data on an OS-driven re-open",
            appModule.contains("fallbackToDestructiveMigration"),
        )
    }

    @Test
    fun theDatabaseSchemaVersionIsPinned() {
        assertTrue(read("${root}data/db/FaceGuardDatabase.kt").contains("version = 10"))
    }

    // -------------------------------------------------------------- DataStore

    @Test
    fun preferencesUseTheVersionStableDataStoreApi() {
        val store = read("${root}data/prefs/SettingsStore.kt")
        assertTrue("the app uses preferencesDataStore", store.contains("preferencesDataStore("))
        assertFalse(
            "no SharedPreferences (version-fragile across the 26–36 range)",
            store.contains("getSharedPreferences("),
        )
    }

    // ---------------------------------------------------------------- Keystore

    @Test
    fun biometricTemplatesUseAesGcmInTheAndroidKeystore() {
        val crypto = read("${root}core/security/SecureCrypto.kt")
        assertTrue(crypto.contains("\"AndroidKeyStore\""))
        assertTrue(crypto.contains("\"AES/GCM/NoPadding\""))
        assertTrue("the key size must be explicit", crypto.contains("setKeySize(256)"))
        assertTrue("a fresh nonce per encryption", crypto.contains("cipher.iv"))
    }

    // ---------------------------------------------------------------- Biometric

    @Test
    fun theBiometricGateDegradesToPinOnEveryFailureMode() {
        val biometric = read("${root}core/security/BiometricAvailabilityProvider.kt")
        assertTrue("strong biometrics only", biometric.contains("BIOMETRIC_STRONG"))
        // Every hardware/enrolment failure must map to "offer PIN", never an error.
        listOf(
            "BIOMETRIC_ERROR_NO_HARDWARE",
            "BIOMETRIC_ERROR_HW_UNAVAILABLE",
            "BIOMETRIC_ERROR_NONE_ENROLLED",
            "BIOMETRIC_ERROR_UNSUPPORTED",
        ).forEach { code ->
            assertTrue("$code must be handled", biometric.contains(code))
        }
        assertTrue(
            "a failure defaults to a safe value instead of throwing",
            biometric.contains("getOrDefault(BiometricAvailability.UNSUPPORTED)"),
        )
    }

    // ------------------------------------------------------------- PendingIntent

    @Test
    fun everyPendingIntentIsImmutableAsRequiredFromApi31() {
        val service = read("${root}core/protection/ProtectionForegroundService.kt")
        val notifications = read("${root}core/notification/AndroidNotifications.kt")
        assertTrue(
            "the service notification intent must be immutable",
            service.contains("PendingIntent.FLAG_IMMUTABLE"),
        )
        assertTrue(
            "the notification click intent must be immutable",
            notifications.contains("PendingIntent.FLAG_IMMUTABLE"),
        )
        // FLAG_MUTABLE would be a security regression on API 31+.
        assertFalse(service.contains("FLAG_MUTABLE"))
        assertFalse(notifications.contains("FLAG_MUTABLE"))
    }

    // ------------------------------------------------------------------ Backup

    @Test
    fun dataNeverLeavesTheDeviceThroughABackupOrTransferOnAnyVersion() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue("cloud backup must stay off", manifest.contains("android:allowBackup=\"false\""))

        val rules = read("app/src/main/res/xml/data_extraction_rules.xml")
        // API 31+ device-to-device transfers must exclude every domain.
        assertTrue(rules.contains("<device-transfer>"))
        listOf("root", "file", "database", "sharedpref", "external").forEach { domain ->
            assertTrue("device-transfer must exclude $domain", rules.contains("<exclude domain=\"$domain\" />"))
        }
    }

    @Test
    fun theLegacyBackupAttributeIsAbsentBecauseTheRulesCoverApi31() {
        // allowBackup=false fully disables backup below API 31; from 31 the
        // dataExtractionRules govern. Declaring the deprecated fullBackupContent would
        // be redundant at best and misleading at worst.
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("android:fullBackupContent"))
    }
}
