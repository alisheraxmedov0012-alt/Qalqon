package uz.faceguard.app.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.navigation.QalqonTopLevelDestination
import uz.faceguard.app.navigation.Routes
import uz.faceguard.app.navigation.isProtectedRoute
import uz.faceguard.app.navigation.lockRedirectFor

/**
 * UI/UX redesign Phase 5: Activity remains the third top-level destination and keeps
 * the existing security semantics.
 *
 * Pure JVM.
 */
class ActivityNavigationTest {

    @Test
    fun activityIsTheExistingTopLevelDestination() {
        assertEquals(Routes.ACTIVITY_LOG, QalqonTopLevelDestination.ACTIVITY.route)
        assertEquals(
            QalqonTopLevelDestination.ACTIVITY,
            QalqonTopLevelDestination.forRoute(Routes.ACTIVITY_LOG),
        )
    }

    @Test
    fun theActivityRouteIsBehindThePinGate() {
        assertTrue(isProtectedRoute(Routes.ACTIVITY_LOG))
        assertEquals(Routes.PIN_UNLOCK, lockRedirectFor(Routes.ACTIVITY_LOG, isUnlocked = false))
        assertNull(lockRedirectFor(Routes.ACTIVITY_LOG, isUnlocked = true))
    }

    @Test
    fun theRequestsDestinationActivityLinksToIsStillProtected() {
        assertTrue(isProtectedRoute(Routes.requests()))
        assertTrue(isProtectedRoute(Routes.REQUESTS))
        assertEquals(Routes.PIN_UNLOCK, lockRedirectFor(Routes.REQUESTS, isUnlocked = false))
    }

    @Test
    fun activityIsATopLevelTabSoItHasNoBackArrow() {
        val screen = read("feature/activity/ActivityLogScreen.kt")
        assertFalse(
            "a top-level tab must not render a back arrow",
            screen.contains("ArrowBack"),
        )
    }

    @Test
    fun activityOpensTheExistingRequestsRoute() {
        // The screen offers an onOpenRequests callback and the graph wires it to the
        // existing requests route (no invented route).
        assertTrue(read("feature/activity/ActivityLogScreen.kt").contains("onOpenRequests"))
        val navGraph = read("navigation/NavGraph.kt")
        assertTrue(navGraph.contains("ActivityLogScreen("))
        assertTrue(navGraph.contains("onOpenRequests = { navController.navigate(Routes.requests()) }"))
    }

    @Test
    fun theShellStillProvidesTheTopLevelDestinationsIncludingActivity() {
        assertEquals(5, QalqonTopLevelDestination.entries.size)
        assertTrue(QalqonTopLevelDestination.entries.map { it.route }.contains(Routes.ACTIVITY_LOG))
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
