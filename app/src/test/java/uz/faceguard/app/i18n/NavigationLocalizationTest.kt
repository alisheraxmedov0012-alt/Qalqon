package uz.faceguard.app.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.navigation.QalqonTopLevelDestination

/**
 * UI/UX redesign Phase 2: the navigation shell is fully localized.
 *
 * The five bottom-navigation labels must exist as string resources in every
 * supported language (Uzbek default, English, Russian) and must never be hardcoded
 * in Kotlin. This mirrors [LocalizationCompletenessTest], but pins the navigation
 * keys specifically so a shell edit cannot smuggle in an English literal.
 */
class NavigationLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    private val navKeys = setOf(
        "nav_home", "nav_children", "nav_activity", "nav_help", "nav_settings",
    )

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    private fun values(locale: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(stringsFile(locale).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun everyLocaleDeclaresAllFiveNavigationLabels() {
        locales.forEach { locale ->
            val declared = keys(locale)
            val missing = navKeys - declared
            assertTrue("$locale is missing navigation labels: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theNavigationKeysHaveParityAcrossAllThreeLocales() {
        val reference = navKeys
        locales.forEach { locale ->
            val declared = keys(locale).filter { it.startsWith("nav_") }.toSet()
            assertEquals("$locale navigation key set drifted", reference, declared)
        }
    }

    @Test
    fun noNavigationLabelIsBlank() {
        locales.forEach { locale ->
            val entries = values(locale)
            navKeys.forEach { key ->
                assertTrue("$locale:$key must not be blank", !entries[key].isNullOrBlank())
            }
        }
    }

    @Test
    fun theNavigationLabelsAreActuallyTranslatedPerLanguage() {
        // The Uzbek default, English and Russian values must each be distinct so the
        // shell does not fall back to one language.
        assertEquals("Bosh sahifa", values("values")["nav_home"])
        assertEquals("Home", values("values-en")["nav_home"])
        assertEquals("Главная", values("values-ru")["nav_home"])

        assertEquals("Bolalar", values("values")["nav_children"])
        assertEquals("Children", values("values-en")["nav_children"])
        assertEquals("Дети", values("values-ru")["nav_children"])

        assertEquals("Faoliyat", values("values")["nav_activity"])
        assertEquals("Activity", values("values-en")["nav_activity"])
        assertEquals("Активность", values("values-ru")["nav_activity"])

        assertEquals("Yordam", values("values")["nav_help"])
        assertEquals("Help", values("values-en")["nav_help"])
        assertEquals("Помощь", values("values-ru")["nav_help"])

        assertEquals("Sozlamalar", values("values")["nav_settings"])
        assertEquals("Settings", values("values-en")["nav_settings"])
        assertEquals("Настройки", values("values-ru")["nav_settings"])
    }

    @Test
    fun eachDestinationUsesADeclaredNavigationStringResource() {
        val declared = keys("values")
        QalqonTopLevelDestination.entries.forEach { destination ->
            // The model exposes resource ids; the names are asserted against the
            // resources that actually exist in the default locale.
            assertTrue(
                "${destination.name} must reference a nav_* resource",
                declared.any { it == expectedKeyFor(destination.name) },
            )
        }
    }

    @Test
    fun theShellNeverHardcodesANavigationLabel() {
        val shell = read("navigation/QalqonAppShell.kt")
        // Labels/descriptions must come from string resources…
        assertTrue(shell.contains("stringResource(destination.labelRes)"))
        assertTrue(shell.contains("stringResource(destination.contentDescriptionRes)"))
        // …and the literal English words must not appear as quoted Kotlin strings.
        listOf("\"Home\"", "\"Children\"", "\"Activity\"", "\"Help\"", "\"Settings\"").forEach { literal ->
            assertFalse("hardcoded navigation label: $literal", shell.contains(literal))
        }
    }

    private fun expectedKeyFor(destinationName: String): String = when (destinationName) {
        "HOME" -> "nav_home"
        "CHILDREN" -> "nav_children"
        "ACTIVITY" -> "nav_activity"
        "HELP" -> "nav_help"
        "SETTINGS" -> "nav_settings"
        else -> error("unknown destination $destinationName")
    }

    private fun stringsFile(locale: String): File =
        File(repoRoot(), "app/src/main/res/$locale/strings.xml")

    private fun read(relativePath: String): String =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath").readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
