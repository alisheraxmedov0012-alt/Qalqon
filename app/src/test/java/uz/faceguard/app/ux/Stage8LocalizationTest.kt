package uz.faceguard.app.ux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.ui.protectionCapabilityWhyRes
import uz.faceguard.app.core.ui.protectionReadinessHintRes
import uz.faceguard.app.core.ui.protectionReadinessLabelRes
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.ProtectionReadiness

/**
 * Stage 8: every new user-facing string exists, non-blank, in Uzbek (default),
 * English and Russian.
 *
 * The three-locale parity is already enforced globally by
 * `LocalizationCompletenessTest`; this pins the Stage 8 keys specifically so a
 * readiness/camera-recovery string can never ship untranslated, and asserts the label
 * mappings cover every enum value.
 */
class Stage8LocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    private fun strings(locale: String): String =
        File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }

    private val stage8Keys = listOf(
        "protection_readiness_label_off",
        "protection_readiness_label_not_ready",
        "protection_readiness_label_limited",
        "protection_readiness_label_ready",
        "protection_readiness_hint_off",
        "protection_readiness_hint_not_ready",
        "protection_readiness_hint_limited",
        "protection_readiness_hint_ready",
        "protection_requirements_required_group",
        "protection_capability_why_camera",
        "protection_capability_why_usage_access",
        "protection_capability_why_accessibility",
        "protection_capability_why_overlay",
        "protection_capability_state_ready",
        "protection_capability_state_missing",
        "protection_capability_state_unavailable",
        "protection_camera_recovery_title",
        "protection_camera_recovery_body",
        "protection_camera_recovery_unavailable",
        "protection_camera_recovery_retry",
        "protection_camera_recovery_restored",
    )

    @Test
    fun everyStage8KeyExistsInAllThreeLocales() {
        locales.forEach { locale ->
            val text = strings(locale)
            stage8Keys.forEach { key ->
                assertTrue("$locale is missing $key", text.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun theReadinessLabelsAndHintsAreNeverBlank() {
        locales.forEach { locale ->
            val entries = Regex("""<string name="(protection_(?:readiness|camera_recovery)[^"]*)">([^<]*)</string>""")
                .findAll(strings(locale))
                .associate { it.groupValues[1] to it.groupValues[2] }
            entries.forEach { (key, value) ->
                assertTrue("$locale:$key is blank", value.isNotBlank())
            }
            assertTrue("$locale exposes no readiness/recovery strings", entries.isNotEmpty())
        }
    }

    @Test
    fun theReadinessPresentationMappingsCoverEveryState() {
        ProtectionReadiness.entries.forEach { readiness ->
            assertTrue("no label for $readiness", protectionReadinessLabelRes(readiness) != 0)
            assertTrue("no hint for $readiness", protectionReadinessHintRes(readiness) != 0)
        }
        assertEquals(4, ProtectionReadiness.entries.size)
    }

    @Test
    fun theCapabilityWhyMappingCoversEveryCapability() {
        ProtectionCapability.entries.forEach { capability ->
            assertTrue(
                "no plain-language explanation for $capability",
                protectionCapabilityWhyRes(capability) != 0,
            )
        }
    }

    @Test
    fun readinessStringsAreNotDuplicatedWithinALocale() {
        // A duplicate name would silently shadow the earlier definition.
        locales.forEach { locale ->
            val names = Regex("name=\"(protection_readiness_[^\"]*)\"")
                .findAll(strings(locale))
                .map { it.groupValues[1] }
                .toList()
            assertEquals("$locale has duplicate readiness keys", names.distinct().size, names.size)
        }
    }

    @Test
    fun noStage8StringLeaksPlaceholderSyntax() {
        locales.forEach { locale ->
            val text = strings(locale)
            stage8Keys.forEach { key ->
                val line = text.lines().first { it.contains("name=\"$key\"") }
                assertFalse("$locale:$key leaks a bare %", line.contains("%") && !line.contains("%1\$s"))
            }
        }
    }
}
