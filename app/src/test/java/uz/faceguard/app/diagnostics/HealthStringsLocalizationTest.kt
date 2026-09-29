package uz.faceguard.app.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12: the localization guard for the system-health tab.
 *
 * Mirrors the eye-safety guard: every key the Health UI resolves must exist in
 * all three languages with an identical key set, no duplicates, no placeholder
 * and no empty value — and no key may be declared without being referenced.
 */
class HealthStringsLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    private val requiredKeys = setOf(
        "settings_tab_health",
        "health_section_title",
        "health_hint",
        "health_run",
        "health_running",
        "health_not_run",
        "health_error",
        "health_level_healthy",
        "health_level_degraded",
        "health_level_critical",
        "health_level_unknown",
        "health_check_protection_intent",
        "health_check_foreground_service",
        "health_check_overlay",
        "health_check_accessibility",
        "health_check_usage_access",
        "health_check_notifications",
        "health_check_boot_restore",
        "health_check_schedule_sync",
        "health_status_ok",
        "health_status_warning",
        "health_status_failed",
        "health_status_unknown",
    )

    @Test
    fun everyLocaleDeclaresEveryHealthKeyExactlyOnce() {
        locales.forEach { locale ->
            val declared = healthKeys(locale)
            val missing = requiredKeys - declared
            assertTrue("$locale is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun theThreeKeySetsAreIdentical() {
        val reference = healthKeys("values")
        locales.forEach { locale ->
            assertEquals("$locale key set differs from values", reference, healthKeys(locale))
        }
    }

    @Test
    fun noHealthStringIsEmptyOrAPlaceholder() {
        val placeholders = listOf("TODO", "TRANSLATE", "???", "FIXME", "XXX")
        locales.forEach { locale ->
            val values = declaredHealthValues(locale)
            values.forEach { (name, value) ->
                assertTrue("$locale:$name is empty", value.isNotBlank())
                assertTrue(
                    "$locale:$name is a placeholder ($value)",
                    placeholders.none { it in value },
                )
            }
        }
    }

    @Test
    fun everyHealthKeyIsActuallyUsedByTheUi() {
        val referenced = healthKeysReferencedFromKotlin()
        val unused = requiredKeys - referenced
        assertTrue("declared but never referenced: ${unused.sorted()}", unused.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun healthKeys(locale: String): Set<String> = declaredHealthValues(locale).keys

    private fun declaredHealthValues(locale: String): Map<String, String> =
        Regex("""<string name="((?:health_|settings_tab_health)[a-z0-9_]*)"[^>]*>([^<]*)</string>""")
            .findAll(stringsFile(locale).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun healthKeysReferencedFromKotlin(): Set<String> =
        File(repoRoot(), "app/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""R\.string\.((?:health_[a-z0-9_]+|settings_tab_health))""")
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
