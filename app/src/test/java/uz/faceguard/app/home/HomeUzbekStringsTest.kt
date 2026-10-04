package uz.faceguard.app.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home screen's Uzbek (default-locale) copy.
 *
 * QALQON ships Uzbek as the primary locale, so its Home strings must be spelled and
 * punctuated consistently: the modifier apostrophe is the plain ASCII one used for
 * "o'" and "g'", never the typographic quote, and the "manage" family uses one form
 * ("boshqarish") so the screen never mixes "boshqaruv" / "boshqarish". A pass over the
 * Home strings found them already correct; this test keeps them that way.
 */
class HomeUzbekStringsTest {

    private val uz = read("app/src/main/res/values/strings.xml")
    private val homeEntries: List<Pair<String, String>> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(uz)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (key, _) -> HOME_PREFIXES.any { key.startsWith(it) } }
            .toList()

    @Test
    fun theHomeStringsAreNotEmpty() {
        assertTrue("no Home strings were found", homeEntries.size > 20)
    }

    @Test
    fun theHomeStringsUseThePlainApostropheNotATypographicQuote() {
        homeEntries.forEach { (key, value) ->
            (0x2018..0x2019).forEach { cp ->
                assertTrue(
                    "$key uses a typographic quote (U+%04X); use the plain ' for o'/g'".format(cp),
                    !value.contains(cp.toChar()),
                )
            }
            assertTrue(
                "$key uses an unusual apostrophe (U+02BC)",
                !value.contains(0x02BC.toChar()),
            )
        }
    }

    @Test
    fun theHomeStringsUseOneManageForm() {
        homeEntries.forEach { (key, value) ->
            assertTrue(
                "$key uses a second 'manage' form; use 'boshqarish' consistently",
                !value.contains("boshqaruv"),
            )
        }
    }

    @Test
    fun theHomeStringsHaveNoStrayWhitespace() {
        homeEntries.forEach { (key, value) ->
            assertTrue("$key has a trailing space", value == value.trimEnd())
            assertTrue("$key has a double space", !value.contains("  "))
        }
    }

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val HOME_PREFIXES = listOf(
            "home_",
            "dashboard_",
            "screentime_summary_",
            "children_section",
            "children_overview",
            "protection_degraded_",
            "protection_after_boot_",
            "protection_capability_",
            "protection_req_",
            "protection_state_",
            "eye_safety_status_",
        )
    }
}
