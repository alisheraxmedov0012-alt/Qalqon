package uz.faceguard.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.core.notification.NotificationNavigation

/**
 * Phase 12: the notification destination extra is untrusted — `MainActivity` is
 * exported (it is the launcher), so any app on the device can send it. The raw
 * value used to be navigated verbatim, which let an external caller drive the app
 * into an arbitrary internal route. Only the single known destination that needs
 * an explicit route may resolve; everything else is ignored.
 */
class NavigationSecurityTest {

    @Test
    fun theKnownRequestsDestinationResolvesToTheRequestsRoute() {
        assertEquals(
            Routes.REQUESTS,
            notificationRouteFor(NotificationNavigation.DESTINATION_REQUESTS),
        )
    }

    @Test
    fun theDashboardDestinationNeverForcesARoute() {
        // The normal start flow already lands on Home (signed in) or Welcome
        // (signed out), so the dashboard destination must not drive navigation — which
        // would also let an external caller skip the auth gate while signed out.
        assertNull(notificationRouteFor(NotificationNavigation.DESTINATION_DASHBOARD))
    }

    @Test
    fun arbitraryInternalRoutesAreNeverHonoured() {
        // A crafted intent must not reach any internal route, including the
        // developer-only recognition debug surface.
        listOf(
            Routes.HOME,
            Routes.SPLASH,
            Routes.WELCOME,
            Routes.SETTINGS,
            Routes.PROTECTION,
            Routes.PRIVACY,
            Routes.HELP,
            Routes.ACTIVITY_LOG,
            Routes.RECOGNITION_DEBUG,
            Routes.PARENT_PROFILE,
            Routes.CHILD_PROFILES,
            "child_policy/1",
            "child_eye_safety/1",
            "child_schedules/1",
            "requests?requestId=5",
        ).forEach { crafted ->
            assertNull("crafted destination '$crafted' must be ignored", notificationRouteFor(crafted))
        }
    }

    @Test
    fun blankAndMalformedValuesAreIgnored() {
        listOf(null, "", "   ", "REQUESTS", "requests ", "..; DROP TABLE parent_requests").forEach { value ->
            assertNull("value '$value' must be ignored", notificationRouteFor(value))
        }
    }
}
