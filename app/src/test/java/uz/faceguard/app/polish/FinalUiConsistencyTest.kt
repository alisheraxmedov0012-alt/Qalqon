package uz.faceguard.app.polish

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 7 (final integration / polish / QA): the cross-cutting
 * consistency guards that keep the redesigned screens a single product.
 *
 * These are source-level assertions, pure JVM. They pin the invariants the redesign
 * phases established — the semantic palette instead of raw colors, the spacing tokens
 * instead of ad-hoc dp literals, no leftover TODOs, and no dead localized strings
 * introduced by the redesign — so a later edit cannot quietly reintroduce them.
 */
class FinalUiConsistencyTest {

    /** Every screen the UI/UX redesign built or rewrote (Phases 3-6). */
    private val redesignedScreens = listOf(
        "feature/home/HomeScreen.kt",
        "feature/child/ChildProfilesScreen.kt",
        "feature/child/ChildDetailScreen.kt",
        "feature/activity/ActivityLogScreen.kt",
        "feature/settings/SettingsScreen.kt",
        "feature/settings/SettingsCategoryScreens.kt",
        "feature/settings/ProtectedAppsScreen.kt",
        "navigation/QalqonAppShell.kt",
    )

    /** The redesigned packages, for the TODO scan. */
    private val redesignedPackages = listOf(
        "feature/home",
        "feature/child",
        "feature/activity",
        "feature/settings",
        "navigation/QalqonAppShell.kt",
        "navigation/QalqonTopLevelDestination.kt",
    )

    @Test
    fun noRedesignedScreenDefinesARawColorLiteral() {
        redesignedScreens.forEach { path ->
            val source = read(path)
            assertTrue(
                "$path must take its colors from the semantic palette, not a raw literal",
                !source.contains("Color(0x"),
            )
        }
    }

    @Test
    fun noRedesignedScreenUsesARawDpLiteral() {
        redesignedScreens.forEach { path ->
            val source = read(path)
            val literal = Regex("""\b\d+\.dp\b""").find(source)
            assertTrue(
                "$path must use the Qalqon spacing/shape tokens, not a raw dp literal" +
                    (literal?.let { " (found '${it.value}')" } ?: ""),
                literal == null,
            )
        }
    }

    @Test
    fun theRedesignedCodeContainsNoTodoOrFixme() {
        redesignedPackages.forEach { pkg ->
            val dir = File(repoRoot(), "app/src/main/java/uz/faceguard/app/$pkg")
            val files = if (dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            } else {
                sequenceOf(dir)
            }
            files.forEach { file ->
                val text = file.readText()
                listOf("TODO", "FIXME", "XXX").forEach { marker ->
                    assertTrue("${file.name} still contains a '$marker'", !text.contains(marker))
                }
            }
        }
    }

    @Test
    fun theRedesignIntroducedNoDeadLocalizedStrings() {
        // Every string the redesign touches (its prefixes) must be referenced from
        // production code - a key left behind after a screen was replaced is dead.
        val prefixes = listOf("home_", "children_", "child_detail_", "activity_", "settings_", "nav_")
        val keys = keysIn("values").filter { key -> prefixes.any { key.startsWith(it) } }
        val referenced = referencedStringNames()
        val dead = keys.filter { it !in referenced }.sorted()
        assertEquals("dead redesign string resources: $dead", emptyList<String>(), dead)
    }

    @Test
    fun theLocalizedStringKeySetsStayIdenticalAcrossAllLocales() {
        val reference = keysIn("values")
        listOf("values-en", "values-ru").forEach { locale ->
            val declared = keysIn(locale)
            val missing = (reference - declared).sorted()
            val extra = (declared - reference).sorted()
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
            assertEquals("$locale has extra $extra", emptyList<String>(), extra)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun keysIn(locale: String): Set<String> =
        Regex("""name="([a-z0-9_]+)"""")
            .findAll(File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    /** Every `R.string.<name>` referenced from production Kotlin. */
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
