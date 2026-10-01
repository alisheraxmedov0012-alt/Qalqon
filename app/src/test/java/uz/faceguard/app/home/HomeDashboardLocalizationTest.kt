package uz.faceguard.app.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 3: the Home dashboard is fully localized and design-token
 * driven.
 *
 * The dashboard's own copy must exist in every supported language (Uzbek default,
 * English, Russian) and must never be hardcoded in Kotlin, and it must not introduce
 * raw colors — the Phase 1 semantic palette is the only source of state color.
 */
class HomeDashboardLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** Every string the Phase 3 dashboard adds. */
    private val dashboardKeys = setOf(
        "home_greeting",
        "home_more_options",
        "home_attention_title",
        "home_attention_children_setup",
        "home_today_title",
        "home_quick_actions_title",
        "home_action_manage_children",
        "home_status_off_hint",
        "home_status_active_hint",
        "home_status_setup_hint",
        "home_status_blocking",
        "home_status_blocking_hint",
        "home_status_recovering_hint",
        "home_child_content_description",
    )

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everyLocaleDeclaresAllDashboardStrings() {
        locales.forEach { locale ->
            val declared = keys(locale)
            val missing = dashboardKeys - declared
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theDashboardKeysHaveParityAcrossAllThreeLocales() {
        val reference = keys("values").filter { it.startsWith("home_") }.toSet()
        locales.forEach { locale ->
            val declared = keys(locale).filter { it.startsWith("home_") }.toSet()
            assertEquals("$locale home_* keys drifted", reference, declared)
        }
    }

    @Test
    fun noShellOrDashboardLabelIsHardcoded() {
        val screen = read("feature/home/HomeScreen.kt")
        // The visible copy must come from resources…
        assertTrue(screen.contains("stringResource("))
        // …and none of it may be an inline English literal.
        listOf(
            "\"Home\"",
            "\"Children\"",
            "\"Today\"",
            "\"Settings\"",
            "\"Protection\"",
            "\"Needs attention\"",
            "\"Quick actions\"",
            "\"More\"",
            "\"Hi, \"",
        ).forEach { literal ->
            assertFalse("hardcoded dashboard copy: $literal", screen.contains(literal))
        }
    }

    @Test
    fun theDashboardUsesSemanticTokensAndNoRawColor() {
        listOf("feature/home/HomeScreen.kt", "feature/home/HomeDashboardPresentation.kt").forEach { path ->
            val source = read(path)
            assertFalse("$path must not define a raw color literal", source.contains("Color(0x"))
            assertFalse("$path must not reference android.graphics.Color", source.contains("android.graphics.Color"))
        }
        // The protection card color comes from the shared semantic tone resolver.
        assertTrue(read("feature/home/HomeScreen.kt").contains("toneColor("))
        assertTrue(read("feature/home/HomeDashboardPresentation.kt").contains("QalqonStatusTone"))
    }

    @Test
    fun theDashboardUsesQalqonComponents() {
        val screen = read("feature/home/HomeScreen.kt")
        listOf(
            "QalqonStatusCard(",
            "QalqonSectionHeader(",
            "QalqonChildCard(",
            "QalqonAlertRow(",
            "QalqonEmptyState(",
            "QalqonErrorState(",
            "QalqonLoadingState(",
            "QalqonCard(",
        ).forEach { component ->
            assertTrue("the dashboard must reuse $component", screen.contains(component))
        }
    }

    @Test
    fun theDashboardUsesTheSpacingTokensRatherThanAdHocPaddings() {
        val screen = read("feature/home/HomeScreen.kt")
        assertTrue(screen.contains("QalqonDimens."))
        // No bare `N.dp` literal in the dashboard composables.
        assertFalse(
            "use the Qalqon spacing tokens, not raw dp literals",
            Regex("""\b\d+\.dp\b""").containsMatchIn(screen),
        )
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
