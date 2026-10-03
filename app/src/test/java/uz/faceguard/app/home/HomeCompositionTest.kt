package uz.faceguard.app.home

import java.io.File
import org.junit.Assert.assertFalse
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
    fun theHeroIsASignaturePremiumSurface() {
        val hero = home.substringAfter("private fun ProtectionStatusSection(").substringBefore("/** Compact attention")
        assertTrue("the hero must be a soft-blue premium surface", hero.contains("Surface("))
        assertTrue("the hero must use the QALQON blue surface", hero.contains("primaryContainer"))
        assertTrue("the hero must carry the QALQON wordmark", hero.contains("R.string.app_name"))
        assertTrue("the hero title must be dominant", hero.contains("typography.headlineSmall"))
        assertTrue("the hero must keep a full-width CTA", hero.contains("Modifier.fillMaxWidth()"))
    }

    @Test
    fun theDashboardSectionsCarryAnIconMarker() {
        // The Today / Children / Quick actions headers use the shared section-icon marker,
        // giving the page a single designed identity. The Quick actions marker is the
        // lightning bolt added by the reference refinement.
        assertTrue(home.contains("HomeSectionIcon(Icons.Filled.DateRange"))
        assertTrue(home.contains("HomeSectionIcon(Icons.Filled.Person"))
        assertTrue(home.contains("HomeSectionIcon(HomeQuickActionBolt"))
    }

    @Test
    fun theTodayTilesArePremiumBorderedSurfaces() {
        // Reference redesign: the Today metric tiles are white surfaces with a hairline
        // border and a soft lift, so they read as premium cards on the light canvas
        // (superseding the earlier flat tonal tiles).
        val tile = home.substringAfter("private fun HomeMetricTile(").substringBefore("/**")
        assertTrue("the tile must carry the hairline border", tile.contains("bordered = true"))
        assertTrue("the tile must sit on the white card surface", tile.contains("MaterialTheme.colorScheme.surface"))
        assertTrue("the tile must use the soft lift", tile.contains("elevation = QalqonDimens.elevation.raised"))
    }

    @Test
    fun theHeroCarriesTheQalqonShieldAndDeviceMotif() {
        // The signature hero includes a Compose-native shield+device motif (no bitmap,
        // no remote asset), drawn from the semantic accent color.
        val hero = home.substringAfter("private fun ProtectionStatusSection(").substringBefore("/** Compact attention")
        assertTrue("the hero must render the motif", hero.contains("QalqonProtectionMotif("))
        val motif = home.substringAfter("private fun QalqonProtectionMotif(").substringBefore("/**")
        assertTrue("the motif must be Compose-drawn", motif.contains("Canvas("))
        assertTrue("the motif must not use a bitmap asset", !motif.contains("painterResource"))
    }

    @Test
    fun theHeroCtaMeetsTheAccessibilityTouchTarget() {
        val hero = home.substringAfter("private fun ProtectionStatusSection(").substringBefore("/** Compact attention")
        assertTrue(
            "the hero CTA must be at least the 48dp touch target",
            hero.contains("height(QalqonDimens.sizes.buttonDefault)"),
        )
    }

    @Test
    fun theTodaySectionIsATwoColumnMetricGrid() {
        val today = home.substringAfter("private fun TodaySection(").substringBefore("/** The glyph for each metric")
        assertTrue("today must lay its metrics out two per row", today.contains("chunked(2)"))
    }

    @Test
    fun todayAndQuickActionsHaveSectionSubtitles() {
        assertTrue(home.contains("R.string.home_today_subtitle"))
        assertTrue(home.contains("R.string.home_quick_actions_subtitle"))
    }

    @Test
    fun theTodayMetricTileNeverEllipsisesImportantText() {
        // The metric tile carries the real value/label/caption; none of it may be
        // truncated with an ellipsis — it must wrap instead.
        val tile = home.substringAfter("private fun HomeMetricTile(").substringBefore("/**")
        assertTrue("the tile must render the label", tile.contains("metric.labelRes"))
        assertTrue("the tile must render the value", tile.contains("valueText"))
        assertFalse("the metric tile must not ellipsise", tile.contains("TextOverflow.Ellipsis"))
        assertFalse("the metric tile must not cap important text", tile.contains("maxLines"))
    }

    @Test
    fun theHeroIsAChipBackedPremiumSurface() {
        val hero = home.substringAfter("private fun ProtectionStatusSection(").substringBefore("/** Compact attention")
        assertTrue("the hero must use the soft-blue premium surface", hero.contains("primaryContainer"))
        assertTrue("the hero must show the real count as a badge", hero.contains("QalqonStatusBadge("))
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
