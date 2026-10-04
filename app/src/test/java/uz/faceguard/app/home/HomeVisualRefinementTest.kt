package uz.faceguard.app.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.feature.home.HomeProtectionStatus
import uz.faceguard.app.feature.home.HomeQuickAction
import uz.faceguard.app.feature.home.HomeTodayMetricKind
import uz.faceguard.app.feature.home.homeProtectionActionLabelRes
import uz.faceguard.app.feature.home.homeQuickActionLabelRes

/**
 * Home visual refinement: the dashboard's presentation contract.
 *
 * Pure JVM. Pins the refinement's real guarantees — a protection action for every
 * state, no invented metric/action kinds, the new copy present in all three locales,
 * and the source-level invariants (semantic colors, spacing tokens, labelled icon-only
 * controls, the child card still opening the existing child-detail callback).
 */
class HomeVisualRefinementTest {

    // ------------------------------------------------------- protection action

    @Test
    fun everyProtectionStateOffersAnAction() {
        HomeProtectionStatus.entries.forEach { status ->
            assertTrue("${status.name} has no action label", homeProtectionActionLabelRes(status) != 0)
        }
    }

    @Test
    fun theStatesTheParentMustActOnOfferTheExistingSetupDestination() {
        assertEquals(
            R.string.dashboard_capability_open,
            homeProtectionActionLabelRes(HomeProtectionStatus.OFF),
        )
        assertEquals(
            R.string.dashboard_capability_open,
            homeProtectionActionLabelRes(HomeProtectionStatus.SETUP_REQUIRED),
        )
    }

    @Test
    fun aRunningSessionOffersTheExistingManagementScreen() {
        listOf(
            HomeProtectionStatus.ACTIVE,
            HomeProtectionStatus.BLOCKING,
            HomeProtectionStatus.RECOVERING,
        ).forEach { status ->
            assertEquals(
                "${status.name} must offer the management action",
                R.string.home_protection_manage,
                homeProtectionActionLabelRes(status),
            )
        }
    }

    // --------------------------------------------------------- no invented data

    @Test
    fun theRefinementIntroducedNoNewMetricKinds() {
        // Protection is deliberately NOT a Today tile (it lives only in the hero), so
        // the metrics are the three derivable from existing data.
        assertEquals(
            listOf("SCREEN_TIME", "SCHEDULE", "EYE_SAFETY"),
            HomeTodayMetricKind.entries.map { it.name },
        )
    }

    @Test
    fun theQuickActionsStayExactlyTheExistingDestinations() {
        assertEquals(
            listOf("PROTECTED_APPS", "SCREEN_TIME", "RULES"),
            HomeQuickAction.entries.map { it.name },
        )
        HomeQuickAction.entries.forEach { action ->
            assertTrue("${action.name} has no label", homeQuickActionLabelRes(action) != 0)
        }
    }

    // ------------------------------------------------------------- localization

    @Test
    fun theRefinementCopyExistsInEveryLocaleAndIsActuallyUsed() {
        val locales = listOf("values", "values-en", "values-ru")
        val keys = locales.associateWith { keysIn(it) }
        val newKeys = listOf("home_all_children", "home_protection_manage", "home_child_screen_time")

        locales.forEach { locale ->
            val missing = newKeys.filter { it !in keys.getValue(locale) }
            assertTrue("$locale is missing $missing", missing.isEmpty())
        }
        // …and each is referenced from production code (no dead string).
        val referenced = referencedStringNames()
        newKeys.forEach { key ->
            assertTrue("$key is declared but never used", key in referenced)
        }
    }

    // ------------------------------------------------------- source invariants

    @Test
    fun theHomeScreenStaysFreeOfRawColorsAndRawDimensions() {
        val screen = read("feature/home/HomeScreen.kt")
        assertFalse("raw color literal", screen.contains("Color(0x"))
        assertFalse("android.graphics.Color", screen.contains("android.graphics.Color"))
        assertFalse(
            "raw dp literal",
            Regex("""\b\d+\.dp\b""").containsMatchIn(screen),
        )
        assertTrue("the dashboard must keep using the spacing tokens", screen.contains("QalqonDimens."))
    }

    @Test
    fun theIconOnlyTopBarControlKeepsItsLocalizedDescription() {
        val screen = read("feature/home/HomeScreen.kt")
        assertTrue(
            "the overflow icon needs a localized description",
            screen.contains("contentDescription = stringResource(R.string.home_more_options)"),
        )
    }

    @Test
    fun theChildCardStillOpensTheExistingChildDetailCallbackWithTheChildId() {
        val screen = read("feature/home/HomeScreen.kt")
        assertTrue(screen.contains("onClick = { onOpenChildPolicy(child.childId) }"))
    }

    @Test
    fun theChildrenSectionOffersAllOnlyThroughTheExistingChildrenCallback() {
        val screen = read("feature/home/HomeScreen.kt")
        assertTrue(screen.contains("R.string.home_all_children"))
        assertTrue(screen.contains("onOpenChildren"))
    }

    @Test
    fun theEmptyChildrenStateStillUsesTheExistingAddChildAction() {
        val screen = read("feature/home/HomeScreen.kt")
        assertTrue(screen.contains("R.string.dashboard_child_add"))
    }

    // ------------------------------------------------------------------ helpers

    private fun keysIn(locale: String): Set<String> =
        Regex("""name="([a-z0-9_]+)"""")
            .findAll(File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun referencedStringNames(): Set<String> =
        File(repoRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""R\.string\.([a-z0-9_]+)""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .asSequence()
            }
            .toSet()

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath")
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
}
