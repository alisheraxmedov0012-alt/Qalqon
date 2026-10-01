package uz.faceguard.app.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 5: the Activity centre is fully localized and design-token
 * driven.
 *
 * Every string the centre adds must exist in all three supported languages (Uzbek
 * default, English, Russian) with identical key sets, and must never be hardcoded in
 * Kotlin.
 */
class ActivityLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** Every string the Phase 5 activity centre adds. */
    private val phase5Keys = setOf(
        "activity_overview_title",
        "activity_metric_screen_time",
        "activity_metric_requests",
        "activity_metric_events",
        "activity_screentime_title",
        "activity_filter_all",
        "activity_day_today",
        "activity_day_yesterday",
        "activity_usage_unavailable",
        "activity_no_screentime",
        "activity_clear_confirm",
    )

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everyLocaleDeclaresAllPhase5Strings() {
        locales.forEach { locale ->
            val missing = phase5Keys - keys(locale)
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theActivityKeysHaveParityAcrossAllThreeLocales() {
        val reference = keys("values").filter { it.startsWith("activity_") }.toSet()
        locales.forEach { locale ->
            val declared = keys(locale).filter { it.startsWith("activity_") }.toSet()
            assertEquals("$locale activity_* keys drifted", reference, declared)
        }
    }

    @Test
    fun theActivityScreenNeverHardcodesUserFacingCopy() {
        val screen = read("feature/activity/ActivityLogScreen.kt")
        assertTrue(screen.contains("stringResource("))
        listOf(
            "\"Today\"",
            "\"Yesterday\"",
            "\"All children\"",
            "\"Screen time history\"",
            "\"Pending requests\"",
            "\"Events today\"",
            "\"No screen time recorded yet\"",
        ).forEach { literal ->
            assertFalse("hardcoded activity copy: $literal", screen.contains(literal))
        }
    }

    @Test
    fun theActivityScreenDefinesNoRawColor() {
        listOf(
            "feature/activity/ActivityLogScreen.kt",
            "feature/activity/ActivityPresentation.kt",
        ).forEach { path ->
            assertFalse("$path must not define a raw color", read(path).contains("Color(0x"))
        }
    }

    @Test
    fun theActivityScreenUsesTheQalqonDesignSystem() {
        val screen = read("feature/activity/ActivityLogScreen.kt")
        listOf(
            "QalqonSectionHeader(",
            "QalqonCard(",
            "QalqonListCard {",
            "QalqonListRow(",
            "QalqonLoadingState(",
            "QalqonEmptyState(",
            "QalqonErrorState(",
            "QalqonConfirmDialog(",
            "QalqonDimens.",
        ).forEach { component ->
            assertTrue("the activity centre must reuse $component", screen.contains(component))
        }
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
