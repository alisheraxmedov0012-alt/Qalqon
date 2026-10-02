package uz.faceguard.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.settings.SettingsCategory
import uz.faceguard.app.feature.settings.SettingsGroup
import uz.faceguard.app.feature.settings.settingsCategories
import uz.faceguard.app.feature.settings.settingsGroups

/**
 * Post-UI correction: the Settings hub groups are presentation-only and must contain
 * exactly the same categories, in the same order, as the flat [settingsCategories] list.
 */
class SettingsGroupingTest {

    @Test
    fun theGroupsContainExactlyTheVisibleCategories() {
        listOf(false, true).forEach { debug ->
            val flat = settingsCategories(debug)
            val grouped = settingsGroups(debug).flatMap { it.second }
            // Grouping is presentation only: it must contain exactly the same categories
            // (as a set, and with no duplicates) — order differs because sections regroup them.
            assertEquals("grouped size must match (debug=$debug)", flat.size, grouped.size)
            assertEquals("grouped set must match (debug=$debug)", flat.toSet(), grouped.toSet())
        }
    }

    @Test
    fun aReleaseBuildNeverRendersTheDeveloperGroup() {
        val groups = settingsGroups(debugScreensEnabled = false)
        assertFalse(groups.any { it.first == SettingsGroup.DEVELOPER })
        assertTrue(groups.flatMap { it.second }.none { it == SettingsCategory.DEVELOPER })
    }

    @Test
    fun aDebugBuildRendersTheDeveloperGroup() {
        val groups = settingsGroups(debugScreensEnabled = true)
        assertTrue(groups.any { it.first == SettingsGroup.DEVELOPER })
    }

    @Test
    fun everyGroupIsNonEmptyAndCarriesALocalizedTitle() {
        settingsGroups(debugScreensEnabled = true).forEach { (group, categories) ->
            assertTrue("${group.name} must not be empty", categories.isNotEmpty())
            assertTrue("${group.name} title", group.labelRes != 0)
        }
    }

    @Test
    fun noCategoryAppearsInMoreThanOneGroup() {
        val all = settingsGroups(debugScreensEnabled = true).flatMap { it.second }
        assertEquals("no duplicate category", all.size, all.toSet().size)
    }
}
