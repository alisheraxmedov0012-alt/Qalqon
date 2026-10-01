package uz.faceguard.app.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 3: the Home redesign must not disturb anything it does not
 * own.
 *
 * Source-level guards so a later edit cannot quietly rename a legacy identifier,
 * weaken the app lock, orphan a screen, re-introduce the old configuration dump on
 * Home, or add a permission. Pure JVM.
 */
class HomeDashboardRegressionTest {

    @Test
    fun theImmutableIdentityContractsAreUnchanged() {
        val appGradle = File(repoRoot(), "app/build.gradle.kts").readText()
        assertTrue(appGradle.contains("namespace = \"uz.faceguard.app\""))
        assertTrue(appGradle.contains("applicationId = \"uz.faceguard.app\""))

        assertTrue(read("FaceGuardApp.kt").contains("class FaceGuardApp"))
        assertTrue(read("data/db/FaceGuardDatabase.kt").contains("class FaceGuardDatabase"))
        assertTrue(read("navigation/NavGraph.kt").contains("fun FaceGuardNavHost("))
        assertTrue(read("core/theme/Theme.kt").contains("fun FaceGuardTheme("))
    }

    @Test
    fun theAuthenticationGateIsUntouched() {
        assertTrue(read("core/security/AppLockState.kt").contains("class AppLockState"))

        val navGraph = read("navigation/NavGraph.kt")
        assertTrue(navGraph.contains("private val PROTECTED_ROUTE_PREFIXES"))
        assertTrue(navGraph.contains("fun isProtectedRoute("))
        assertTrue(navGraph.contains("fun lockRedirectFor("))
        assertTrue(navGraph.contains("fun notificationRouteFor("))
    }

    @Test
    fun theDashboardStillUsesTheExistingDataSourcesOnly() {
        val screen = read("feature/home/HomeScreen.kt")
        // The state still comes from the existing ViewModel/aggregator.
        assertTrue(screen.contains("viewModel.dashboard"))
        assertTrue(screen.contains("viewModel.screenTimeSummary"))
        assertTrue(screen.contains("viewModel.parentProfile"))
        // No direct persistence access from the screen: the Home package must not
        // reach into the data layer at all.
        assertFalse(
            "HomeScreen must not import the data layer directly",
            screen.contains("import uz.faceguard.app.data."),
        )
        assertFalse("HomeScreen must not query a SQLite database", screen.contains("rawQuery"))
    }

    @Test
    fun everyDestinationHomeOffersStillUsesAnExistingNavigationCallback() {
        val screen = read("feature/home/HomeScreen.kt")
        listOf(
            "onOpenChildren",
            "onOpenProtectedApps",
            "onOpenSettings",
            "onOpenProtection",
            "onOpenRequests",
            "onOpenChildPolicy",
            "onOpenPrivacy",
            "onOpenHelp",
            "onOpenRecognition",
        ).forEach { callback ->
            assertTrue("Home lost the '$callback' action", screen.contains(callback))
        }
    }

    @Test
    fun theHomeDestinationStillRoutesThroughTheExistingGraph() {
        val navGraph = read("navigation/NavGraph.kt")
        listOf(
            "Routes.CHILD_PROFILES",
            "Routes.SETTINGS_APPS",
            "Routes.SETTINGS",
            "Routes.PROTECTION",
            "Routes.requests()",
            "Routes.childPolicy(",
            "Routes.PRIVACY",
            "Routes.HELP",
        ).forEach { route ->
            assertTrue("the Home wiring no longer reaches $route", navGraph.contains(route))
        }
    }

    @Test
    fun theOldConfigurationDumpIsGoneFromHome() {
        val screen = read("feature/home/HomeScreen.kt")
        listOf(
            // the duplicate child picker
            "FilterChip(",
            "ChildContextCard(",
            // the large historical/usage presentation
            "RecentActivityCard(",
            "UsageCard(",
            "ScreenTimeSummarySection(",
            // the setup checklist and the in-Home developer section
            "SetupChecklistCard(",
            "DeveloperSection(",
        ).forEach { removed ->
            assertFalse("Home still contains the removed '$removed'", screen.contains(removed))
        }
    }

    @Test
    fun homeHasNoSecondNavigationBar() {
        // The global shell owns the bottom navigation (Phase 2).
        assertFalse(read("feature/home/HomeScreen.kt").contains("NavigationBar("))
    }

    @Test
    fun theDebugEntryPointStaysBehindTheDebugGate() {
        // Debug diagnostics are not in the production UI, but are not deleted either.
        assertTrue(read("feature/home/HomeScreen.kt").contains("DebugFlags.DEBUG_SCREENS_ENABLED"))
        assertTrue(read("navigation/NavGraph.kt").contains("Routes.RECOGNITION_DEBUG"))
    }

    @Test
    fun noPermissionOrNetworkCapabilityWasAdded() {
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
