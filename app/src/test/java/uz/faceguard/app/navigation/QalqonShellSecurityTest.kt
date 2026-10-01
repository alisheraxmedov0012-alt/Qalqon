package uz.faceguard.app.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.notification.NotificationNavigation

/**
 * UI/UX redesign Phase 2: the navigation shell must not weaken the existing app
 * lock, and must not change any of the navigation/identity/security contracts it
 * sits on top of.
 *
 * The shell adds a bottom bar *around* the existing graph; the authoritative gate
 * (`AppLockState` → `lockRedirectFor` → `isProtectedRoute`) is untouched, so every
 * path into a parental-control destination is still gated while locked.
 */
class QalqonShellSecurityTest {

    // ------------------------------------------------------ authentication

    @Test
    fun case2_lockedChildrenTabRemainsBehindThePinGate() {
        assertEquals(
            Routes.PIN_UNLOCK,
            lockRedirectFor(QalqonTopLevelDestination.CHILDREN.route, isUnlocked = false),
        )
    }

    @Test
    fun case3_lockedActivityTabRemainsBehindThePinGate() {
        assertEquals(
            Routes.PIN_UNLOCK,
            lockRedirectFor(QalqonTopLevelDestination.ACTIVITY.route, isUnlocked = false),
        )
    }

    @Test
    fun case4_lockedSettingsTabRemainsBehindThePinGate() {
        assertEquals(
            Routes.PIN_UNLOCK,
            lockRedirectFor(QalqonTopLevelDestination.SETTINGS.route, isUnlocked = false),
        )
    }

    @Test
    fun case5_everyTabRouteIsProtectedSoADirectAttemptIsGated() {
        // A direct navigation call / restored stack into any tab route is redirected.
        QalqonTopLevelDestination.entries.forEach { destination ->
            assertTrue(destination.name, isProtectedRoute(destination.route))
            assertEquals(
                destination.name,
                Routes.PIN_UNLOCK,
                lockRedirectFor(destination.route, isUnlocked = false),
            )
        }
    }

    @Test
    fun case7_anUnlockedUiIsNeverRedirectedFromAnyTab() {
        QalqonTopLevelDestination.entries.forEach { destination ->
            assertNull(destination.name, lockRedirectFor(destination.route, isUnlocked = true))
        }
    }

    @Test
    fun thePinScreenItselfStaysReachableWhileLockedSoTheGateCannotLoop() {
        assertFalse(isProtectedRoute(Routes.PIN_UNLOCK))
        assertNull(lockRedirectFor(Routes.PIN_UNLOCK, isUnlocked = false))
    }

    // ------------------------------------------------------ notification deep links

    @Test
    fun case6_aLockedNotificationDeepLinkStopsAtThePinGate() {
        val route = notificationRouteFor(NotificationNavigation.DESTINATION_REQUESTS)
        assertEquals(Routes.REQUESTS, route)
        // …and because the resolved route is protected, a locked UI is redirected.
        assertEquals(Routes.PIN_UNLOCK, lockRedirectFor(route, isUnlocked = false))
    }

    @Test
    fun anUnlockedValidNotificationStillReachesItsDestination() {
        val route = notificationRouteFor(NotificationNavigation.DESTINATION_REQUESTS)
        assertEquals(Routes.REQUESTS, route)
        assertNull(lockRedirectFor(route, isUnlocked = true))
    }

    @Test
    fun theNotificationWhitelistIsNotBroadened() {
        // Phase 2 must not widen the whitelist: only the requests destination resolves.
        assertEquals(Routes.REQUESTS, notificationRouteFor("requests"))
        assertNull(notificationRouteFor("home"))
        assertNull(notificationRouteFor("children"))
        assertNull(notificationRouteFor("settings"))
        assertNull(notificationRouteFor("dashboard"))
        assertNull(notificationRouteFor(null))
    }

    // ------------------------------------------------------ regression guards

    @Test
    fun theLockGateAndNotificationRoutesAreUnchanged() {
        val navGraph = read("navigation/NavGraph.kt")
        assertTrue(navGraph.contains("private val PROTECTED_ROUTE_PREFIXES"))
        assertTrue(navGraph.contains("fun isProtectedRoute("))
        assertTrue(navGraph.contains("fun lockRedirectFor("))
        assertTrue(navGraph.contains("fun notificationRouteFor("))
        assertTrue(navGraph.contains("fun FaceGuardNavHost("))
    }

    @Test
    fun theImmutableIdentityContractsAreUnchanged() {
        val appGradle = File(repoRoot(), "app/build.gradle.kts").readText()
        assertTrue(appGradle.contains("namespace = \"uz.faceguard.app\""))
        assertTrue(appGradle.contains("applicationId = \"uz.faceguard.app\""))

        assertTrue(read("FaceGuardApp.kt").contains("class FaceGuardApp"))
        assertTrue(read("MainActivity.kt").contains("class MainActivity"))
        assertTrue(read("navigation/NavGraph.kt").contains("fun FaceGuardNavHost("))
        assertTrue(read("core/theme/Theme.kt").contains("fun FaceGuardTheme("))
        assertTrue(
            read("core/security/AppLockState.kt").contains("class AppLockState"),
        )
    }

    @Test
    fun theShellAddsNoPermissionOrNetworkCapability() {
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
