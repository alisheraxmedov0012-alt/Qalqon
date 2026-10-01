package uz.faceguard.app.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Localization guarantees for the design-system components.
 *
 * The components carry **no** hardcoded user-facing text: labels are passed in, and
 * the few defaults they own are string resources. This test pins both halves of
 * that contract — the default keys exist in all three languages with the same key
 * set, and they are actually referenced from Kotlin (so a locale can never fall
 * behind, and a default can never become dead weight).
 */
class QalqonComponentStringsTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** Every string the design-system components own. */
    private val requiredKeys = setOf(
        "qal_action_retry",
        "qal_action_cancel",
        "qal_action_confirm",
        "qal_badge_content_description",
    )

    @Test
    fun everyDesignSystemStringExistsInAllThreeLocales() {
        locales.forEach { locale ->
            val missing = requiredKeys - keys(locale)
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theThreeLocaleKeySetsRemainIdentical() {
        val reference = keys("values")
        locales.forEach { locale ->
            assertEquals("$locale key set differs from values", reference, keys(locale))
        }
    }

    @Test
    fun noDesignSystemStringIsEmptyOrAPlaceholder() {
        val placeholders = listOf("TODO", "TRANSLATE", "???", "FIXME", "XXX")
        locales.forEach { locale ->
            requiredKeys.forEach { key ->
                val value = values(locale)[key]
                assertTrue("$locale:$key is missing", value != null)
                assertTrue("$locale:$key is empty", !value!!.isBlank())
                assertTrue(
                    "$locale:$key is a placeholder ($value)",
                    placeholders.none { it in value },
                )
            }
        }
    }

    @Test
    fun theCountBadgeKeepsItsNumberPlaceholderInEveryLocale() {
        locales.forEach { locale ->
            val value = values(locale)["qal_badge_content_description"]
            assertTrue(
                "$locale qal_badge_content_description must format the count",
                value != null && value.contains("%1\$d"),
            )
        }
    }

    @Test
    fun everyDesignSystemStringIsActuallyReferencedFromKotlin() {
        val referenced = referencedKeys()
        val unused = requiredKeys - referenced
        assertTrue("declared but never referenced: ${unused.sorted()}", unused.isEmpty())
    }

    @Test
    fun theComponentsDoNotHardcodeUserFacingActionText() {
        // The design-system components must take labels as parameters (or resolve a
        // resource), never embed English/Uzbek literals such as "Retry" or "Settings".
        val banned = listOf("\"Retry\"", "\"Cancel\"", "\"Confirm\"", "\"Settings\"", "\"Active\"")
        val offenders = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/ui/qalqon")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                banned.filter { file.readText().contains(it) }.map { "${file.name}: $it" }
            }
            .toList()

        assertTrue("hardcoded user-facing text in components: $offenders", offenders.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    private fun values(locale: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(stringsFile(locale).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun referencedKeys(): Set<String> =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""R\.string\.(qal_[a-z0-9_]+)""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .asSequence()
            }
            .toSet()

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
