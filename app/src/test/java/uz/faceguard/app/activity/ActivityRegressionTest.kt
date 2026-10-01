package uz.faceguard.app.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 5: the Activity redesign must keep every existing capability
 * and must not disturb the layers or screens it does not own.
 *
 * Source-level guards, pure JVM.
 */
class ActivityRegressionTest {

    private val screen = read("feature/activity/ActivityLogScreen.kt")
    private val presentation = read("feature/activity/ActivityPresentation.kt")

    @Test
    fun theExistingClearLogCapabilityIsPreserved() {
        // "Clear log" moved into the overflow but must still exist and still call the
        // existing clear() on the account's log.
        assertTrue(screen.contains("R.string.activity_clear"))
        assertTrue(screen.contains("viewModel.clear()"))
        assertTrue(screen.contains("activityRepository.clear(accountId)"))
    }

    @Test
    fun theActivityCentreNeverComputesScreenTimeItself() {
        // Duration formatting is delegated to the one existing formatter; the centre
        // never multiplies or divides milliseconds into a duration of its own.
        assertTrue(screen.contains("durationLabel("))
        listOf("60_000", "/ 1000", "MS_PER_MINUTE", "* 60").forEach { math ->
            assertFalse("the activity centre must not compute durations: $math", screen.contains(math))
        }
    }

    @Test
    fun eventAndRequestPresentationAreReusedNotDuplicated() {
        // The activity log's type -> label mapping is the existing one; the centre
        // must not add a second mapping table.
        assertTrue(screen.contains("eventLabelRes("))
        assertTrue(screen.contains("requestRows("))
        assertTrue(screen.contains("requestStatusLabelRes("))
        assertFalse(
            "a second event label mapping must not exist in the centre",
            presentation.contains("ActivityEventType.CHILD_BLOCKED ->"),
        )
    }

    @Test
    fun theActivityCentreUsesOnlyExistingDataSources() {
        // No direct DAO/DataStore access from the screen.
        assertFalse(screen.contains("import uz.faceguard.app.data."))
        assertFalse(screen.contains("rawQuery"))
        // The ViewModel reads the existing repositories/services.
        listOf(
            "ActivityLogRepository",
            "ParentRequestRepository",
            "ChildProfileRepository",
            "ScreenTimeUsageRepository",
            "AppUsageSource",
        ).forEach { source ->
            assertTrue("the centre must read $source", screen.contains(source))
        }
    }

    @Test
    fun theProtectedSecurityContractsAreUntouched() {
        val navGraph = read("navigation/NavGraph.kt")
        listOf(
            "private val PROTECTED_ROUTE_PREFIXES",
            "fun isProtectedRoute(",
            "fun lockRedirectFor(",
            "fun notificationRouteFor(",
            "fun FaceGuardNavHost(",
        ).forEach { contract ->
            assertTrue("$contract was removed", navGraph.contains(contract))
        }
        assertTrue(read("core/security/AppLockState.kt").contains("class AppLockState"))
    }

    @Test
    fun homeAndChildrenAndChildDetailAreStillInPlace() {
        // Phase 3 / Phase 4 screens are untouched in shape.
        assertTrue(read("feature/home/HomeScreen.kt").contains("homeTodayMetrics("))
        assertTrue(read("feature/child/ChildProfilesScreen.kt").contains("childOverviews("))
        assertTrue(read("feature/child/ChildDetailScreen.kt").contains("CHILD_DETAIL_CONTROLS"))
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
