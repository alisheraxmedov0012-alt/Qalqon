package uz.faceguard.app.design

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 guardrails for the design-system rollout.
 *
 * Two things must stay true while the UI is migrated screen by screen:
 *
 * 1. the **pre-existing shared components stay intact** (they are still used by 20+
 *    screens, so the foundation must be additive, not a rename/removal), and
 * 2. the **immutable constraints** of this phase hold: no navigation change, no data
 *    change, and no new permission.
 *
 * These are source-level assertions so a later commit cannot quietly break them.
 */
class QalqonFoundationCompatibilityTest {

    // ------------------------------------------- existing components preserved

    @Test
    fun theExistingSharedComponentsAreStillDeclared() {
        val components = read("core/ui/Components.kt")
        listOf(
            "fun SectionCard(",
            "fun AppTextField(",
            "fun AppPhoneField(",
            "fun AppPinField(",
            "fun AppLoadingButton(",
        ).forEach { signature ->
            assertTrue("legacy component removed: $signature", components.contains(signature))
        }
    }

    @Test
    fun theExistingComponentsKeepTheirOriginalParameterShape() {
        val components = read("core/ui/Components.kt")
        // Signatures existing screens depend on — a change here breaks ~20 call sites.
        assertTrue(components.contains("title: String,"))
        assertTrue(components.contains("subtitle: String? = null,"))
        assertTrue(components.contains("content: @Composable ColumnScope.() -> Unit,"))
        assertTrue(components.contains("labelRes: Int,"))
        assertTrue(components.contains("errorRes: Int? = null,"))
        assertTrue(components.contains("loading: Boolean,"))
    }

    @Test
    fun theSharedUiStateContractIsUnchanged() {
        val uiState = read("core/ui/UiState.kt")
        listOf("data object Idle", "data object Loading", "data object Success", "data class Error(")
            .forEach { assertTrue("UiState changed: $it", uiState.contains(it)) }
    }

    @Test
    fun theThemeEntryPointKeepsItsLegacyName() {
        // Renaming FaceGuardTheme would touch MainActivity and every screen.
        assertTrue(read("core/theme/Theme.kt").contains("fun FaceGuardTheme("))
    }

    @Test
    fun theNewComponentsStayPurePresentation() {
        // No component may reach into data/domain/protection layers: the foundation is
        // UI-only, so it cannot smuggle in business logic.
        val offenders = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/ui/qalqon")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                listOf(
                    "uz.faceguard.app.data.",
                    "uz.faceguard.app.domain.repository",
                    "uz.faceguard.app.core.protection",
                    "uz.faceguard.app.core.recognition",
                ).filter { text.contains(it) }.map { "${file.name}: $it" }
            }
            .toList()

        assertTrue("design-system components must not depend on app layers: $offenders", offenders.isEmpty())
    }

    // --------------------------------------- immutable constraints (this phase)

    @Test
    fun navigationIsUntouchedInThisPhase() {
        // The redesign of navigation is a later phase; the route table and the lock
        // gate must be exactly as before.
        val navGraph = read("navigation/NavGraph.kt")
        assertTrue(navGraph.contains("private val PROTECTED_ROUTE_PREFIXES"))
        assertTrue(navGraph.contains("fun lockRedirectFor("))
        assertTrue(navGraph.contains("fun notificationRouteFor("))
        // Bottom navigation is explicitly a later phase.
        assertFalse(
            "a bottom navigation bar must not be introduced in the foundation phase",
            navGraph.contains("NavigationBar("),
        )
    }

    @Test
    fun noNewPermissionOrNetworkCapabilityWasAdded() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

        // INTERNET / ACCESS_NETWORK_STATE appear only as `tools:node="remove"`
        // declarations (ML Kit's transport dependency injects them and the manifest
        // strips them again), never as a granted permission.
        listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        ).forEach { permission ->
            val lines = manifest.lines().filter { it.contains(permission) }
            lines.forEach { line ->
                assertTrue(
                    "$permission must only appear as a tools:node=\"remove\" removal",
                    line.contains("tools:node=\"remove\""),
                )
            }
        }

        assertFalse("QUERY_ALL_PACKAGES must not be requested", manifest.contains("QUERY_ALL_PACKAGES"))
    }

    @Test
    fun theDesignSystemAddsNoDependency() {
        // The foundation is built from the Material3 already present; adding a UI
        // library here would contradict the phase's constraints.
        val catalog = File(repoRoot(), "gradle/libs.versions.toml").readText()
        assertFalse(catalog.contains("material-icons-extended"))
        assertFalse(catalog.contains("accompanist-navigation"))
        assertFalse(catalog.contains("wheelpicker"))
    }

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
