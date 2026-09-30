package uz.faceguard.app.enrollment

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12 (enrollment): the localization guard for the enrollment copy.
 *
 * The full key set of the three resource files must stay identical (so no locale
 * is ever missing a string), the new enrollment keys must exist everywhere, must
 * actually be referenced from Kotlin, and must not be empty or left as a
 * placeholder.
 */
class EnrollmentStringsLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** The keys the robust frontal enrollment UI resolves at runtime. */
    private val requiredKeys = setOf(
        "enroll_hint_initial",
        "enroll_hint_no_face",
        "enroll_hint_multiple_faces",
        "enroll_hint_too_far",
        "enroll_hint_too_close",
        "enroll_hint_off_center",
        "enroll_hint_look_straight",
        "enroll_hint_too_dark",
        "enroll_hint_too_bright",
        "enroll_hint_hold_still",
        "enroll_hint_occluded",
        "enroll_hint_quality_retry",
        "enroll_hint_unstable",
        "enroll_hint_hold",
        "enroll_hint_preparing",
        "enroll_progress",
        "enroll_success_message",
        "enroll_failure_message",
        "enroll_canceled_message",
    )

    @Test
    fun everyLocaleDeclaresEveryEnrollmentKey() {
        locales.forEach { locale ->
            val declared = keys(locale)
            val missing = requiredKeys - declared
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theThreeWholeFileKeySetsAreIdentical() {
        val reference = keys("values")
        locales.forEach { locale ->
            assertEquals("$locale key set differs from values", reference, keys(locale))
        }
    }

    @Test
    fun noEnrollmentStringIsEmptyOrAPlaceholder() {
        val placeholders = listOf("TODO", "TRANSLATE", "???", "FIXME", "XXX")
        locales.forEach { locale ->
            enrollmentValues(locale).forEach { (name, value) ->
                assertTrue("$locale:$name is empty", value.isNotBlank())
                assertTrue("$locale:$name is a placeholder ($value)", placeholders.none { it in value })
            }
        }
    }

    @Test
    fun everyRequiredEnrollmentKeyIsReferencedFromKotlin() {
        val referenced = keysReferencedFromKotlin()
        val unused = requiredKeys - referenced
        assertTrue("declared but never referenced: ${unused.sorted()}", unused.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun keys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText()).map { it.groupValues[1] }.toSet()

    private fun enrollmentValues(locale: String): Map<String, String> =
        Regex("""<string name="(enroll_[a-z0-9_]+)"[^>]*>([^<]*)</string>""")
            .findAll(stringsFile(locale).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun keysReferencedFromKotlin(): Set<String> =
        File(repoRoot(), "app/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""R\.string\.(enroll_[a-z0-9_]+)""")
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
