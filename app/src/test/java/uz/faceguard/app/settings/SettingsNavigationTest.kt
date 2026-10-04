package uz.faceguard.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.settings.SettingsCategory
import uz.faceguard.app.navigation.Routes
import uz.faceguard.app.navigation.isProtectedRoute
import uz.faceguard.app.navigation.lockRedirectFor

/**
 * UI/UX redesign Phase 6: the Settings hub routes to one page per category, every page
 * stays behind the existing PIN gate, and the developer page stays inside the DEBUG
 * gate.
 *
 * Pure JVM.
 */
class SettingsNavigationTest {

    @Test
    fun everyCategoryPageIsBehindThePinGate() {
        SettingsCategory.entries.forEach { category ->
            assertTrue("${category.name} must be protected", isProtectedRoute(category.route))
            assertEquals(
                "${category.name} must redirect while locked",
                Routes.PIN_UNLOCK,
                lockRedirectFor(category.route, isUnlocked = false),
            )
            assertNull(lockRedirectFor(category.route, isUnlocked = true))
        }
    }

    @Test
    fun theSettingsHubAndProtectedAppsRoutesStayProtected() {
        assertTrue(isProtectedRoute(Routes.SETTINGS))
        assertTrue(isProtectedRoute(Routes.SETTINGS_APPS))
        assertEquals(Routes.PIN_UNLOCK, lockRedirectFor(Routes.SETTINGS, isUnlocked = false))
        assertEquals(Routes.PIN_UNLOCK, lockRedirectFor(Routes.SETTINGS_APPS, isUnlocked = false))
    }

    @Test
    fun theHubWiresEveryCategoryToItsOwnRoute() {
        val navGraph = read("navigation/NavGraph.kt")
        // The hub navigates by the category's own route (no hardcoded duplicates).
        assertTrue(navGraph.contains("onOpenCategory = { category -> navController.navigate(category.route) }"))
        // And each category composable exists.
        listOf(
            "Routes.SETTINGS_PROTECTION",
            "Routes.SETTINGS_FAMILY",
            "Routes.SETTINGS_SECURITY",
            "Routes.SETTINGS_APPEARANCE",
            "Routes.SETTINGS_NOTIFICATIONS",
            "Routes.SETTINGS_PRIVACY",
            "Routes.SETTINGS_SUPPORT",
            "Routes.SETTINGS_SUBSCRIPTION",
            "Routes.SETTINGS_DEVELOPER",
        ).forEach { route ->
            assertTrue("the hub graph is missing $route", navGraph.contains(route))
        }
    }

    @Test
    fun theDeveloperCategoryComposableExistsOnlyInsideTheDebugGate() {
        val navGraph = read("navigation/NavGraph.kt")
        val gate = navGraph.indexOf("if (DebugFlags.DEBUG_SCREENS_ENABLED) {")
        // There are two debug gates (recognition debug + settings developer); the
        // settings developer one must be after a debug gate.
        val developer = navGraph.indexOf("composable(Routes.SETTINGS_DEVELOPER)")
        assertTrue("developer settings must exist", developer > 0)
        assertTrue("developer settings must be inside a debug gate", gate in 0 until developer)
    }

    @Test
    fun settingsIsATopLevelTabSoTheHubHasNoBackArrow() {
        val screen = read("feature/settings/SettingsScreen.kt")
        assertFalse(
            "the Settings hub must not render a back arrow",
            screen.contains("ArrowBack"),
        )
    }

    @Test
    fun theProtectedAppsDestinationStillReusesTheExistingRoute() {
        val navGraph = read("navigation/NavGraph.kt")
        assertTrue(navGraph.contains("composable(Routes.SETTINGS_APPS)"))
        assertTrue(navGraph.contains("ProtectedAppsScreen("))
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
