package uz.faceguard.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 6: the Settings centre is fully localized and design-token
 * driven.
 *
 * Every string the settings hub and its category pages add must exist in all three
 * supported languages (Uzbek default, English, Russian) with identical key sets, and
 * must never be hardcoded in Kotlin.
 */
class SettingsLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** Every string the Phase 6 Settings centre adds. */
    private val phase6Keys = setOf(
        "settings_category_protection",
        "settings_category_protection_desc",
        "settings_category_family",
        "settings_category_family_desc",
        "settings_category_security",
        "settings_category_security_desc",
        "settings_category_appearance",
        "settings_category_appearance_desc",
        "settings_category_notifications",
        "settings_category_notifications_desc",
        "settings_category_privacy",
        "settings_category_privacy_desc",
        "settings_category_support",
        "settings_category_support_desc",
        "settings_category_developer",
        "settings_category_developer_desc",
        "settings_row_parent_profile",
        "settings_row_parent_profile_desc",
        "settings_row_children",
        "settings_row_children_desc",
        "settings_row_protected_apps_desc",
        "settings_row_help_desc",
        "settings_row_recognition_debug_desc",
        "settings_row_app_lock",
        "settings_row_app_lock_desc",
        "settings_notifications_enabled",
        "settings_notifications_disabled",
        "settings_notifications_hint",
    )

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everyLocaleDeclaresAllPhase6Strings() {
        locales.forEach { locale ->
            val missing = phase6Keys - keys(locale)
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theSettingsKeysHaveParityAcrossAllThreeLocales() {
        val reference = keys("values").filter { it.startsWith("settings_") }.toSet()
        locales.forEach { locale ->
            val declared = keys(locale).filter { it.startsWith("settings_") }.toSet()
            assertEquals("$locale settings_* keys drifted", reference, declared)
        }
    }

    @Test
    fun theSettingsScreensNeverHardcodeUserFacingCopy() {
        listOf(
            "feature/settings/SettingsScreen.kt",
            "feature/settings/SettingsCategoryScreens.kt",
            "feature/settings/SettingsCategories.kt",
            "feature/settings/ProtectedAppsScreen.kt",
        ).forEach { path ->
            val source = read(path)
            listOf("\"Protection\"", "\"Family\"", "\"Security\"", "\"Appearance\"", "\"Support\"")
                .forEach { literal ->
                    assertFalse("hardcoded copy in $path: $literal", source.contains(literal))
                }
        }
        // The strings the hub rows render all come from the model's resources.
        assertTrue(read("feature/settings/SettingsCategories.kt").contains("R.string.settings_category_"))
    }

    @Test
    fun theSettingsScreensDefineNoRawColor() {
        listOf(
            "feature/settings/SettingsScreen.kt",
            "feature/settings/SettingsCategoryScreens.kt",
            "feature/settings/SettingsCategories.kt",
            "feature/settings/ProtectedAppsScreen.kt",
        ).forEach { path ->
            assertFalse("$path must not define a raw color", read(path).contains("Color(0x"))
        }
    }

    @Test
    fun theSettingsScreensReuseTheDesignSystem() {
        val categoryScreens = read("feature/settings/SettingsCategoryScreens.kt")
        listOf("QalqonSettingRow(", "QalqonStatusCard(", "QalqonDimens.", "SectionCard(").forEach { component ->
            assertTrue("the settings category pages must reuse $component", categoryScreens.contains(component))
        }
        assertTrue(read("feature/settings/SettingsScreen.kt").contains("QalqonCard("))
    }

    private fun read(relativePath: String): String =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath").readText()

    private fun stringsFile(locale: String): File =
        File(repoRoot(), "app/src/main/res/$locale/strings.xml")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
