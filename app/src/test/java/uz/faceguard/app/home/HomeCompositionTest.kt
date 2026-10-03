package uz.faceguard.app.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home visual recomposition (V2): the dashboard order and the bounded child empty state
 * are pinned here so a later edit cannot silently regress the composition — "today" must
 * come before the child area, and the child empty state must stay a compact card rather
 * than a tall centred block.
 *
 * Pure JVM source assertions (the project has no Compose UI test host); wording is not
 * asserted, only the structural order and which components the sections use.
 */
class HomeCompositionTest {

    private val home = read("feature/home/HomeScreen.kt")

    @Test
    fun todayIsComposedAboveTheChildSection() {
        val today = home.indexOf("item { TodaySection(")
        val children = home.indexOf("item { ChildrenSection(")
        assertTrue("TodaySection must be called from the dashboard", today >= 0)
        assertTrue("ChildrenSection must be called from the dashboard", children >= 0)
        assertTrue(
            "today must appear above the child section so useful data sits above the fold",
            today < children,
        )
    }

    @Test
    fun theChildEmptyStateIsACompactCardNotATallCentredBlock() {
        // The child section's empty branch renders a card with a row + CTA. The tall
        // centred QalqonEmptyState must remain only for the no-account state.
        val childrenSection = home.substringAfter("private fun ChildrenSection(")
        val emptyBranch = childrenSection.substringAfter("if (children.isEmpty())").substringBefore("} else {")
        assertTrue("the child empty state must be a card", emptyBranch.contains("QalqonCard("))
        assertTrue("the child empty state must offer the existing add action", emptyBranch.contains("dashboard_child_add"))
        assertTrue("the child empty state must not be the tall centred block", !emptyBranch.contains("QalqonEmptyState("))
    }

    @Test
    fun theHeroIsATintedCardWithADominantTitle() {
        val hero = home.substringAfter("private fun ProtectionStatusSection(").substringBefore("/** Compact attention")
        assertTrue("the hero must be a card", hero.contains("QalqonCard("))
        assertTrue("the hero must carry a tinted container", hero.contains("containerColor ="))
        assertTrue("the hero title must be dominant", hero.contains("typography.headlineSmall"))
        assertTrue("the hero must keep a full-width CTA", hero.contains("Modifier.fillMaxWidth()"))
    }

    @Test
    fun todayAndQuickActionsHaveSectionSubtitles() {
        assertTrue(home.contains("R.string.home_today_subtitle"))
        assertTrue(home.contains("R.string.home_quick_actions_subtitle"))
    }

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
