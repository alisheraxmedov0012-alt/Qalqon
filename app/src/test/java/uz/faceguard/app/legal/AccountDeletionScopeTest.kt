package uz.faceguard.app.legal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.navigation.Routes
import uz.faceguard.app.navigation.isProtectedRoute

/**
 * Release Block 2: the account-deletion **scope** for data that is easy to overlook.
 *
 * The Room/DataStore/Keystore wipe is already pinned by `Stage4DeletionAndBackupTest` and
 * `PlayComplianceContractTest`; this class adds the two Block 2 concerns that were not
 * previously asserted:
 *
 *  1. the **subscription entitlement cache** is cleared on account deletion (it lives in the
 *     shared `settings` DataStore, so the full reset wipes it — no stale Premium state can
 *     survive deletion);
 *  2. the in-app deletion is a **destructive, confirmed** action behind the PIN gate, and it
 *     returns the app to a signed-out (fresh-install) state.
 */
class AccountDeletionScopeTest {

    private fun root(relative: String) = "app/src/main/java/uz/faceguard/app/$relative"
    private val resetRepo by lazy { read(root("data/repository/ResetRepositoryImpl.kt")) }
    private val entitlementStore by lazy { read(root("core/billing/EntitlementStore.kt")) }
    private val settingsStore by lazy { read(root("data/prefs/SettingsStore.kt")) }
    private val appModule by lazy { read(root("di/AppModule.kt")) }
    private val settingsScreens by lazy { read(root("feature/settings/SettingsCategoryScreens.kt")) }
    private val settingsScreen by lazy { read(root("feature/settings/SettingsScreen.kt")) }

    @Test
    fun theFullResetAlsoClearsTheStaleSubscriptionEntitlementCache() {
        // The entitlement cache is persisted in the same `settings` DataStore the reset clears
        // wholesale by `clear()`, so a deleted account can never leave a stale Premium state.
        assertTrue(
            "the DataStore must be the shared settings one",
            appModule.contains("context.settingsDataStore") && appModule.contains("provideSettingsDataStore"),
        )
        assertTrue(
            "the entitlement store must persist into that DataStore",
            entitlementStore.contains("DataStore<Preferences>") && entitlementStore.contains("store.edit"),
        )
        assertTrue(
            "the settings store must clear the whole DataStore on reset",
            settingsStore.contains("suspend fun clearAll()") && settingsStore.contains("store.edit { it.clear() }"),
        )
        assertTrue("the reset must run the settings clear", resetRepo.contains("settingsStore.clearAll()"))
    }

    @Test
    fun theResetRemovesTheBiometricKeyWithTheDataItProtects() {
        assertTrue("reset must delete the Keystore key", resetRepo.contains("keyProvider.deleteKey()"))
        assertTrue("reset must clear the session", resetRepo.contains("sessionManager.clearSession()"))
    }

    @Test
    fun theInAppDeletionIsConfirmedAndBehindThePinGate() {
        // The action requires an explicit, irreversible confirmation.
        assertTrue(settingsScreens.contains("data_reset_confirm_title"))
        assertTrue(settingsScreens.contains("data_reset_confirm_yes"))
        assertTrue("the reset must be wired to the repository", settingsScreen.contains("resetRepository.resetAll()"))
        // Reached through the PIN-gated Privacy category, so a child cannot trigger it.
        assertTrue("the Privacy category must be PIN-gated", isProtectedRoute(Routes.SETTINGS_PRIVACY))
        // Deletion returns the app to a signed-out state (fresh-install behaviour).
        assertTrue(settingsScreens.contains("LaunchedEffect(resetDone) { if (resetDone) onLoggedOut() }"))
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
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
