package uz.faceguard.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protected-apps UI refinement: the catalogue's presentation contract.
 *
 * Source-level guards (pure JVM), following the pattern the other UI-refinement
 * tests use. They pin what the refinement actually changed — a real per-app icon with
 * a defined fallback, a lazy list keyed by package name, an off-main-thread memoised
 * icon lookup — and what it must NOT have changed: the catalogue data source, the
 * selection callbacks, the search semantics, the route, the package-visibility and
 * permission configuration, and the design-system rules.
 */
class ProtectedAppsUIRefinementTest {

    private val screen = read("feature/settings/ProtectedAppsScreen.kt")
    private val resolver = read("feature/settings/AppIconResolver.kt")

    // ------------------------------------------------------------ real icons

    @Test
    fun theRowRendersTheRealLauncherIconFromPackageManager() {
        // The resolver reads the platform's icon — no bundled/hardcoded artwork.
        assertTrue(resolver.contains("packageManager.getApplicationIcon("))
        // …and the row renders whatever it resolved.
        assertTrue(screen.contains("rememberAppIcon("))
        assertTrue(screen.contains("Image("))
        assertTrue(screen.contains("bitmap = icon,"))
    }

    @Test
    fun theIconLookupHappensOffTheMainThreadAndIsMemoised() {
        assertTrue("icon loading must run on IO", resolver.contains("withContext(Dispatchers.IO)"))
        assertTrue("icons must be cached", resolver.contains("cache["))
        assertTrue(
            "a repeated row must not re-query PackageManager",
            resolver.contains("if (cache.containsKey(packageName)) return cache[packageName]"),
        )
    }

    @Test
    fun aMissingIconFallsBackToALocalGenericGlyph() {
        // The fallback is a local Material vector, chosen deterministically — never a
        // guessed identity and never a crash.
        assertTrue(screen.contains("Icons.AutoMirrored.Filled.List"))
        assertTrue(screen.contains("if (icon != null)"))
    }

    // --------------------------------------------------------------- the list

    @Test
    fun theListUsesPackageNameAsItsStableKey() {
        assertTrue(screen.contains("LazyColumn("))
        assertTrue(screen.contains("items(visible, key = { it.packageName })"))
    }

    @Test
    fun searchKeepsItsExistingMatchingSemantics() {
        assertTrue(screen.contains("it.appDisplayName.contains(query.trim(), ignoreCase = true)"))
        assertTrue(screen.contains("R.string.papps_search_hint"))
    }

    @Test
    fun refreshAndTheCatalogueStillComeFromTheExistingViewModel() {
        assertTrue(screen.contains("viewModel.protectedApps"))
        assertTrue(screen.contains("viewModel::toggleProtectedApp"))
        assertTrue(screen.contains("viewModel::refreshProtectedApps"))
        assertTrue(screen.contains("viewModel.appsRefreshing"))
    }

    @Test
    fun theScreenDoesNotReachIntoTheDataLayer() {
        assertFalse("no Room", screen.contains("androidx.room"))
        assertFalse("no DAO", screen.contains("Dao"))
        assertFalse("no raw query", screen.contains("rawQuery"))
        assertFalse("no data-layer import", screen.contains("import uz.faceguard.app.data."))
    }

    // ---------------------------------------------------------- selection rule

    @Test
    fun bothTheRowAndTheCheckboxUseTheSameExistingToggleCallback() {
        // Row tap and checkbox both call the existing (String, Boolean) toggle with the
        // identical argument order, so the persisted selection behaviour is unchanged.
        assertTrue(screen.contains("onToggle: (String, Boolean) -> Unit"))
        assertTrue(screen.contains("onToggle(app.packageName, !app.isProtected)"))
        assertTrue(screen.contains("onCheckedChange = { onToggle(app.packageName, it) }"))
    }

    @Test
    fun theSelectionControlIsStillACheckboxBoundToThePersistedState() {
        assertTrue(screen.contains("Checkbox("))
        assertTrue(screen.contains("checked = app.isProtected,"))
    }

    // -------------------------------------------------------- design system

    @Test
    fun theScreenStaysFreeOfRawColorsAndRawDimensions() {
        assertFalse("raw color literal", screen.contains("Color(0x"))
        assertFalse("android.graphics.Color", screen.contains("android.graphics.Color"))
        assertFalse("raw dp literal", Regex("""\b\d+\.dp\b""").containsMatchIn(screen))
        assertTrue("must use the spacing/shape tokens", screen.contains("QalqonDimens."))
    }

    @Test
    fun theIconColoursComeFromTheSemanticPalette() {
        // The container may be tinted, but the real icon is never recoloured.
        assertTrue(screen.contains("MaterialTheme.colorScheme.primaryContainer"))
        assertTrue(screen.contains("MaterialTheme.colorScheme.surfaceVariant"))
        assertFalse(
            "the app icon itself must not be tinted",
            Regex("""Image\([^)]*tint""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(screen),
        )
    }

    // --------------------------------------------------------- accessibility

    @Test
    fun eachRowCarriesALocalizedDescriptionAndAdequateTouchTarget() {
        assertTrue(screen.contains("R.string.papps_row_description"))
        assertTrue(screen.contains("contentDescription = rowDescription"))
        assertTrue(screen.contains("heightIn(min = QalqonDimens.sizes.touchTarget)"))
        // The icon is decorative: it must not add its own announcement.
        assertTrue(screen.contains("contentDescription = null,"))
        assertTrue(screen.contains("mergeDescendants = true"))
    }

    @Test
    fun theBackArrowNowCarriesALocalizedDescription() {
        assertTrue(screen.contains("contentDescription = stringResource(R.string.request_back)"))
    }

    // --------------------------------------------------------- localization

    @Test
    fun theRefinementStringsExistInEveryLocaleAndAreReferenced() {
        val locales = listOf("values", "values-en", "values-ru")
        val newKeys = listOf("papps_state_protected", "papps_state_unprotected", "papps_row_description")
        val keys = locales.associateWith { keysIn(it) }
        locales.forEach { locale ->
            val missing = newKeys.filter { it !in keys.getValue(locale) }
            assertTrue("$locale is missing $missing", missing.isEmpty())
        }
        // Locale parity must hold for the whole catalogue block.
        val reference = keys.getValue("values").filter { it.startsWith("papps_") }.toSet()
        locales.forEach { locale ->
            assertEquals(
                "$locale papps_* keys drifted",
                reference,
                keys.getValue(locale).filter { it.startsWith("papps_") }.toSet(),
            )
        }
        // …and each new key is actually used (no dead string).
        val referenced = referencedStringNames()
        newKeys.forEach { key -> assertTrue("$key is declared but never used", key in referenced) }
    }

    @Test
    fun theNowUnusedSelectedLabelWasRemovedEverywhere() {
        val locales = listOf("values", "values-en", "values-ru")
        locales.forEach { locale ->
            assertFalse("$locale still declares the removed label", "papps_marked" in keysIn(locale))
        }
        assertFalse("the screen still references the removed label", screen.contains("papps_marked"))
    }

    // ------------------------------------------------------ manifest safety

    @Test
    fun noPermissionOrPackageVisibilityChangeWasIntroduced() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        manifest.lines()
            .filter { it.contains("android.permission.INTERNET") }
            .forEach { line ->
                assertTrue(
                    "INTERNET must only ever appear as a tools:node=\"remove\" removal",
                    line.contains("tools:node=\"remove\""),
                )
            }
        assertFalse("QUERY_ALL_PACKAGES must not be requested", manifest.contains("QUERY_ALL_PACKAGES"))
        // The existing narrow <queries> configuration is untouched.
        assertTrue("the existing <queries> block must remain", manifest.contains("<queries>"))
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
