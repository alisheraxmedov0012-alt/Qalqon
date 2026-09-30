package uz.faceguard.app.eyesafety

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 Step 5: the eye-safety localization guard.
 *
 * Reads the three resource files directly and checks the things a missing translation would
 * otherwise hide: that every eye-safety key exists in **all three** languages, that the three key
 * sets are identical (no missing and no extra key in any language), that no key is declared twice,
 * and that no key is left as a placeholder.
 *
 * The first list below is the exact set the eye-safety UI looks up at runtime, so a key that is
 * referenced but not declared for some language fails the build here rather than showing a blank
 * label on a device.
 */
class EyeSafetyStringsLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    /** Every eye-safety string the UI resolves, one per `R.string.eye_safety_*` reference. */
    private val requiredKeys = setOf(
        // Screen and entry point
        "eye_safety_title",
        "eye_safety_subtitle",
        "eye_safety_open",
        "eye_safety_explanation",
        // Status
        "eye_safety_status_not_configured",
        "eye_safety_status_enabled",
        "eye_safety_status_disabled",
        // Enable
        "eye_safety_enable",
        "eye_safety_enable_hint",
        "eye_safety_unconfigured_hint",
        // Thresholds
        "eye_safety_warning_enter_label",
        "eye_safety_danger_enter_label",
        "eye_safety_warning_exit_label",
        "eye_safety_danger_exit_label",
        "eye_safety_hysteresis_hint",
        // Confirmation
        "eye_safety_confirm_frames_label",
        "eye_safety_confirm_frames_hint",
        // Actions
        "eye_safety_warning_action",
        "eye_safety_danger_action",
        "eye_safety_warning_action_hint",
        "eye_safety_danger_action_hint",
        "eye_safety_action_allow",
        "eye_safety_action_warning",
        "eye_safety_action_soft_block",
        "eye_safety_action_hard_block",
        "eye_safety_action_mute",
        "eye_safety_action_unavailable_hint",
        "eye_safety_action_allow_hint",
        "eye_safety_action_warning_hint",
        "eye_safety_action_soft_block_hint",
        "eye_safety_action_hard_block_hint",
        "eye_safety_action_mute_hint",
        // Advanced
        "eye_safety_advanced_show",
        "eye_safety_advanced_hide",
        // Validation and failures
        "eye_safety_error_invalid",
        "eye_safety_error_enter_range",
        "eye_safety_error_exit_range",
        "eye_safety_error_threshold_order",
        "eye_safety_error_exit_order",
        "eye_safety_error_confirm_frames",
        "eye_safety_error_save",
        "eye_safety_error_load",
    )

    @Test
    fun everyLocaleDeclaresEveryEyeSafetyKey() {
        val problems = mutableListOf<String>()
        locales.forEach { locale ->
            val names = stringNames(stringsFile(locale))
            val missing = requiredKeys - names
            if (missing.isNotEmpty()) problems += "$locale is missing ${missing.sorted()}"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test
    fun theThreeLocalesHaveIdenticalKeySetsAcrossTheWholeFile() {
        // Stronger than a count comparison: a rename in one language and an unrelated addition in
        // another would keep the counts equal while leaving the file out of sync.
        val sets = locales.associateWith { stringNames(stringsFile(it)) }

        assertEquals("values vs values-en", sets["values"], sets["values-en"])
        assertEquals("values vs values-ru", sets["values"], sets["values-ru"])
    }

    @Test
    fun noEyeSafetyKeyIsDeclaredTwiceInAnyLocale() {
        locales.forEach { locale ->
            val names = Regex("""name="([^"]+)"""").findAll(stringsFile(locale).readText())
                .map { it.groupValues[1] }
                .toList()
            val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("$locale declares these keys more than once: $duplicates", duplicates.isEmpty())
        }
    }

    @Test
    fun noEyeSafetyStringIsLeftAsAPlaceholder() {
        val placeholders = listOf("TODO", "TRANSLATE", "???", "FIXME", "XXX")
        locales.forEach { locale ->
            val text = stringsFile(locale).readText()
            val offenders = Regex("""<string name="(eye_safety_[a-z0-9_]+)"[^>]*>([^<]*)</string>""")
                .findAll(text)
                .filter { match -> placeholders.any { it in match.groupValues[2] } }
                .map { it.groupValues[1] }
                .toList()
            assertTrue("$locale has placeholder eye-safety strings: $offenders", offenders.isEmpty())
        }
    }

    @Test
    fun noEyeSafetyStringIsEmpty() {
        locales.forEach { locale ->
            val empty = Regex("""<string name="(eye_safety_[a-z0-9_]+)"[^>]*>\s*</string>""")
                .findAll(stringsFile(locale).readText())
                .map { it.groupValues[1] }
                .toList()
            assertTrue("$locale has empty eye-safety strings: $empty", empty.isEmpty())
        }
    }

    @Test
    fun everyEyeSafetyKeyIsActuallyUsedByTheUi() {
        // An unused key is dead weight in three languages; the reverse direction (used but not
        // declared) is covered by [everyLocaleDeclaresEveryEyeSafetyKey].
        val declared = requiredKeys
        val referenced = eyeSafetyKeysReferencedFromKotlin()
        val unused = declared - referenced
        assertTrue("declared but never referenced: ${unused.sorted()}", unused.isEmpty())
    }

    private fun eyeSafetyKeysReferencedFromKotlin(): Set<String> =
        File(repoRoot(), "app/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""R\.string\.(eye_safety_[a-z0-9_]+)""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .asSequence()
            }
            .toSet()

    private fun stringsFile(locale: String): File = File(repoRoot(), "app/src/main/res/$locale/strings.xml")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    private fun stringNames(file: File): Set<String> =
        Regex("""name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toSet()
}
