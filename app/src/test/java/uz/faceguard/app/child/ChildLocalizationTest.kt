package uz.faceguard.app.child

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 4: Children + Child Detail are fully localized.
 *
 * Every string the two screens add must exist in all three supported languages
 * (Uzbek default, English, Russian) with identical key sets, and must never be
 * hardcoded in Kotlin.
 */
class ChildLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** Every string the Phase 4 child screens add. */
    private val phase4Keys = setOf(
        "children_overview",
        "children_open_details",
        "child_detail_back",
        "child_detail_controls_title",
        "child_detail_apps",
        "child_detail_apps_desc",
        "child_detail_screen_time",
        "child_detail_screen_time_desc",
        "child_detail_schedule",
        "child_detail_schedule_desc",
        "child_detail_eye_safety",
        "child_detail_eye_safety_desc",
        "child_detail_face",
        "child_detail_face_desc",
        "child_detail_requests",
        "child_detail_requests_desc",
        "child_detail_missing",
        "child_detail_missing_hint",
    )

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everyLocaleDeclaresAllPhase4Strings() {
        locales.forEach { locale ->
            val missing = phase4Keys - keys(locale)
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theChildKeysHaveParityAcrossAllThreeLocales() {
        val reference = keys("values").filter { it.startsWith("child_detail_") || it.startsWith("children_") }.toSet()
        locales.forEach { locale ->
            val declared = keys(locale)
                .filter { it.startsWith("child_detail_") || it.startsWith("children_") }
                .toSet()
            assertEquals("$locale child keys drifted", reference, declared)
        }
    }

    @Test
    fun theChildScreensNeverHardcodeUserFacingCopy() {
        listOf(
            "feature/child/ChildProfilesScreen.kt",
            "feature/child/ChildDetailScreen.kt",
        ).forEach { path ->
            val source = read(path)
            assertTrue("$path must use string resources", source.contains("stringResource("))
            listOf(
                "\"Children\"",
                "\"Your children\"",
                "\"Child not found\"",
                "\"Apps\"",
                "\"Screen time\"",
                "\"Schedule\"",
                "\"Eye safety\"",
                "\"Requests\"",
            ).forEach { literal ->
                assertFalse("hardcoded copy in $path: $literal", source.contains(literal))
            }
        }
    }

    @Test
    fun theChildScreensDefineNoRawColor() {
        listOf(
            "feature/child/ChildProfilesScreen.kt",
            "feature/child/ChildDetailScreen.kt",
        ).forEach { path ->
            assertFalse("$path must not define a raw color", read(path).contains("Color(0x"))
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
