package uz.faceguard.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.settings.SettingsCategory
import uz.faceguard.app.feature.settings.settingsCategories
import uz.faceguard.app.navigation.Routes

/**
 * UI/UX redesign Phase 6: the Settings category model and its debug gating.
 *
 * Pure JVM — the hub's shape and the DEBUG-only developer category are pinned without
 * a device.
 */
class SettingsCategoriesTest {

    @Test
    fun theHubOffersTheExpectedCategoriesInOrder() {
        assertEquals(
            listOf(
                "PROTECTION",
                "FAMILY",
                "SECURITY",
                "APPEARANCE",
                "NOTIFICATIONS",
                "PRIVACY",
                "SUPPORT",
                "SUBSCRIPTION",
                "DEVELOPER",
            ),
            SettingsCategory.entries.map { it.name },
        )
    }

    @Test
    fun aReleaseBuildNeverShowsTheDeveloperCategory() {
        val release = settingsCategories(debugScreensEnabled = false)
        assertFalse(release.any { it == SettingsCategory.DEVELOPER })
        assertEquals(8, release.size)
    }

    @Test
    fun aDebugBuildShowsTheDeveloperCategory() {
        val debug = settingsCategories(debugScreensEnabled = true)
        assertTrue(debug.contains(SettingsCategory.DEVELOPER))
        assertEquals(9, debug.size)
    }

    @Test
    fun onlyTheDeveloperCategoryIsDebugOnly() {
        assertEquals(
            listOf(SettingsCategory.DEVELOPER),
            SettingsCategory.entries.filter { it.debugOnly },
        )
    }

    @Test
    fun everyCategoryHasAUniqueRouteAndLocalizedLabelAndDescription() {
        val routes = SettingsCategory.entries.map { it.route }
        assertEquals("routes must be unique", routes.size, routes.toSet().size)
        SettingsCategory.entries.forEach { category ->
            assertTrue("${category.name} route", category.route.isNotBlank())
            assertTrue("${category.name} label", category.labelRes != 0)
            assertTrue("${category.name} description", category.descriptionRes != 0)
            assertNotNull("${category.name} icon", category.icon)
        }
    }

    @Test
    fun theCategoryRoutesAreTheDedicatedSettingsRoutes() {
        assertEquals(Routes.SETTINGS_PROTECTION, SettingsCategory.PROTECTION.route)
        assertEquals(Routes.SETTINGS_FAMILY, SettingsCategory.FAMILY.route)
        assertEquals(Routes.SETTINGS_SECURITY, SettingsCategory.SECURITY.route)
        assertEquals(Routes.SETTINGS_APPEARANCE, SettingsCategory.APPEARANCE.route)
        assertEquals(Routes.SETTINGS_NOTIFICATIONS, SettingsCategory.NOTIFICATIONS.route)
        assertEquals(Routes.SETTINGS_PRIVACY, SettingsCategory.PRIVACY.route)
        assertEquals(Routes.SETTINGS_SUPPORT, SettingsCategory.SUPPORT.route)
        assertEquals(Routes.SETTINGS_SUBSCRIPTION, SettingsCategory.SUBSCRIPTION.route)
        assertEquals(Routes.SETTINGS_DEVELOPER, SettingsCategory.DEVELOPER.route)
    }

    @Test
    fun theCategoryRoutesStayDistinctFromTheSettingsHubAndAppsDestinations() {
        val routes = SettingsCategory.entries.map { it.route }
        assertFalse(routes.contains(Routes.SETTINGS))
        assertFalse(routes.contains(Routes.SETTINGS_APPS))
    }
}
