package uz.faceguard.app.policy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group 4 localization guard: every new user-facing string must exist in the
 * default (Uzbek) resources and in the English/Russian mirrors. Reads the
 * resource files directly, so a missing translation fails the build fast.
 */
class PolicyStringsLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    private val requiredKeys = setOf(
        "child_policy_action",
        "child_policy_title",
        "child_policy_subtitle",
        "child_policy_choose_child",
        "child_policy_selected",
        "child_policy_apps_title",
        "child_policy_empty_apps",
        "child_policy_empty_apps_hint",
        "child_policy_no_children",
        "child_policy_no_children_hint",
        "child_policy_mode_allow",
        "child_policy_mode_limit",
        "child_policy_mode_block",
        "child_policy_state_explicit_allow",
        "child_policy_state_explicit_limit",
        "child_policy_state_explicit_block",
        "child_policy_state_default_allow",
        "child_policy_state_default_block",
        "child_policy_dialog_title",
        "child_policy_dialog_current",
        "child_policy_limit_label",
        "child_policy_minutes",
        "child_policy_limit_note",
        "child_policy_reset",
        "child_policy_open",
        "settings_load_error",
        // Group 5 activity log labels
        "activity_no_face",
        "activity_protection_released",
        // Group 6 foreground service notification
        "protection_service_channel_name",
        "protection_service_channel_description",
        "protection_service_notification_title",
        "protection_service_notification_text",
    )

    @Test
    fun everyLocaleDeclaresEveryGroup4Key() {
        val problems = mutableListOf<String>()
        locales.forEach { locale ->
            val names = stringNames(stringsFile(locale))
            val missing = requiredKeys - names
            if (missing.isNotEmpty()) problems += "$locale is missing $missing"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test
    fun localesStayInSync() {
        val sizes = locales.map { stringNames(stringsFile(it)).size }
        assertEquals("values vs values-en", sizes[0], sizes[1])
        assertEquals("values vs values-ru", sizes[0], sizes[2])
    }

    private fun stringsFile(locale: String): File = File(repoRoot(), "app/src/main/res/$locale/strings.xml")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile ?: error("Repo root not found from ${System.getProperty("user.dir")}")
        }
    }

    private fun stringNames(file: File): Set<String> =
        Regex("""name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toSet()
}
