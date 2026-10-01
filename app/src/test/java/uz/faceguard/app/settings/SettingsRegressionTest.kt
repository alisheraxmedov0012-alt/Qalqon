package uz.faceguard.app.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 6: the Settings redesign must keep every existing capability
 * and must not disturb the layers or screens it does not own.
 *
 * Source-level guards, pure JVM.
 */
class SettingsRegressionTest {

    private val vm = read("feature/settings/SettingsScreen.kt")
    private val categories = read("feature/settings/SettingsCategoryScreens.kt")
    private val navGraph = read("navigation/NavGraph.kt")

    @Test
    fun theSettingsViewModelKeepsItsConstructorDependencies() {
        // The existing ViewModel API is what the instrumented settings test constructs.
        listOf(
            "private val settingsRepository: SettingsRepository",
            "private val accountRepository: AccountRepository",
            "private val protectedAppsRepository: ProtectedAppsRepository",
            "private val parentProfileRepository: ParentProfileRepository",
            "private val childRepository: ChildProfileRepository",
            "private val resetRepository: ResetRepository",
            "private val healthSource: SystemHealthSnapshotSource",
            "private val languageStore: AppLanguageStore",
            "private val appLockState: AppLockState",
        ).forEach { dependency ->
            assertTrue("SettingsViewModel lost $dependency", vm.contains(dependency))
        }
    }

    @Test
    fun everyExistingSettingsActionIsStillReachable() {
        listOf(
            "fun setLanguage(",
            "fun setProtectionEnabled(",
            "fun setScanMode(",
            "fun setRecoveryDelay(",
            "fun setUnknownPolicy(",
            "fun setNoFacePolicy(",
            "fun setLowBatteryBehavior(",
            "fun toggleProtectedApp(",
            "fun refreshProtectedApps(",
            "fun deleteParentFace(",
            "fun deleteChildFace(",
            "fun resetAll(",
            "fun logout(",
            "fun runDiagnostics(",
        ).forEach { action ->
            assertTrue("the settings action $action was removed", vm.contains(action))
        }
    }

    @Test
    fun theProtectionSettingsAreGroupedInTheProtectionPage() {
        listOf(
            "settings_protection_toggle",
            "scan_balanced",
            "settings_recovery_delay",
            "settings_unknown_policy",
            "settings_no_face_policy",
            "settings_low_battery",
        ).forEach { key ->
            assertTrue("the protection page lost $key", categories.contains(key))
        }
    }

    @Test
    fun theLanguageSettingStillReusesTheExistingPicker() {
        // Appearance reuses the single LanguageOptions / AppLanguageStore path.
        assertTrue(categories.contains("LanguageOptions("))
        assertTrue(categories.contains("viewModel::setLanguage"))
    }

    @Test
    fun theLocalDataToolsArePreserved() {
        listOf(
            "data_delete_parent_face",
            "data_delete_child_face",
            "data_reset_all",
        ).forEach { key ->
            assertTrue("the data tools lost $key", categories.contains(key))
        }
        assertTrue("the reset confirmation must remain", categories.contains("data_reset_confirm_body"))
    }

    @Test
    fun theSecurityContractsAreUntouched() {
        listOf(
            "private val PROTECTED_ROUTE_PREFIXES",
            "fun isProtectedRoute(",
            "fun lockRedirectFor(",
            "fun notificationRouteFor(",
            "fun FaceGuardNavHost(",
        ).forEach { contract ->
            assertTrue("$contract was removed", navGraph.contains(contract))
        }
        assertTrue(read("core/security/AppLockState.kt").contains("class AppLockState"))
    }

    @Test
    fun theEarlierRedesignPhasesAreUntouched() {
        assertTrue(read("feature/home/HomeScreen.kt").contains("homeTodayMetrics("))
        assertTrue(read("feature/child/ChildProfilesScreen.kt").contains("childOverviews("))
        assertTrue(read("feature/child/ChildDetailScreen.kt").contains("CHILD_DETAIL_CONTROLS"))
        assertTrue(read("feature/activity/ActivityLogScreen.kt").contains("activityMetrics("))
    }

    @Test
    fun theSettingsScreensDoNotQueryPersistenceDirectly() {
        // The screens go through the existing repository/ViewModel; they never touch
        // Room or the SQLite database themselves.
        listOf(
            "feature/settings/SettingsScreen.kt",
            "feature/settings/SettingsCategoryScreens.kt",
            "feature/settings/ProtectedAppsScreen.kt",
        ).forEach { path ->
            val source = read(path)
            assertFalse("$path must not use Room", source.contains("androidx.room"))
            assertFalse("$path must not query the database", source.contains("rawQuery"))
            assertFalse("$path must not use a DAO", source.contains("Dao"))
        }
    }

    @Test
    fun noPermissionOrNetworkCapabilityWasAdded() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        manifest.lines()
            .filter { it.contains("android.permission.INTERNET") }
            .forEach { line ->
                assertTrue(
                    "INTERNET must only ever appear as a tools:node=\"remove\" removal",
                    line.contains("tools:node=\"remove\""),
                )
            }
        assertFalse("QUERY_ALL_PACKAGES must not be requested", manifest.contains("QUERY_ALL_PACKAGES"))
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
