package uz.faceguard.app.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Localization completeness for the three supported languages.
 *
 * QALQON's default locale is Uzbek (`values`); English and Russian are mirrors.
 * A key present in one file but missing from another is exactly how a mixed-language
 * UI appears ("Uzbek UI + English button"), so the key sets are required to match.
 */
class LocalizationCompletenessTest {

    private val locales = listOf("values", "values-en", "values-ru")

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    private fun values(locale: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(stringsFile(locale).readText())
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    /** Resolves the Android XML escapes the way aapt does at build time. */
    private fun unescape(raw: String): String = raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\@", "@")
        .replace("\\?", "?")

    @Test
    fun allThreeLocalesDeclareTheSameKeys() {
        val reference = keys("values")
        locales.forEach { locale ->
            val declared = keys(locale)
            val missing = reference - declared
            val extra = declared - reference
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
            assertTrue("$locale has extra keys: ${extra.sorted()}", extra.isEmpty())
        }
    }

    @Test
    fun noLocaleIsEmptyAndEveryValueIsPresent() {
        locales.forEach { locale ->
            val entries = values(locale)
            assertTrue("$locale declares no strings", entries.isNotEmpty())
            val blank = entries.filterValues { it.isBlank() }.keys
            assertTrue("$locale has blank strings: ${blank.sorted()}", blank.isEmpty())
        }
    }

    @Test
    fun theLanguageNamesAreTheExactEndonymsInEveryLocale() {
        // The picker must be readable regardless of the active language, so these
        // three labels are identical in all three files.
        val expected = mapOf(
            "language_name_uzbek" to "O'zbekcha",
            "language_name_english" to "English",
            "language_name_russian" to "Русский",
        )
        locales.forEach { locale ->
            val declared = values(locale)
            expected.forEach { (key, value) ->
                assertEquals("$locale:$key", value, declared[key])
            }
        }
    }

    @Test
    fun theFirstLaunchAndSettingsLanguageKeysExistEverywhere() {
        val required = setOf(
            "language_select_title",
            "language_select_subtitle",
            "language_section",
            "language_section_hint",
        )
        locales.forEach { locale ->
            val missing = required - keys(locale)
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

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
