package uz.faceguard.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup lock gate, as a pure rule.
 *
 * Every route a child must not reach without the parent PIN is classified here, and
 * the redirect rule is pinned so a locked UI cannot be driven into the parental
 * controls by a direct navigation call, a restored back stack, a notification deep
 * link or a saved navigation state.
 */
class AppLockNavigationGateTest {

    private val protectedRoutes = listOf(
        Routes.HOME,
        Routes.PARENT_PROFILE,
        Routes.CHILD_PROFILES,
        Routes.SETTINGS,
        Routes.SETTINGS_APPS,
        Routes.PROTECTION,
        Routes.PRIVACY,
        Routes.HELP,
        Routes.ACTIVITY_LOG,
        Routes.REQUESTS,
        Routes.RECOGNITION_DEBUG,
        Routes.PARENT_FACE_ENROLLMENT,
        Routes.childFaceEnrollment(7L),
        Routes.childPolicy(7L),
        Routes.childSchedules(7L),
        Routes.childScheduleEditor(7L),
        Routes.childScheduleEditor(7L, 3L),
        Routes.childEyeSafety(7L),
        // A notification deep link carries a query suffix; the path decides.
        "${Routes.REQUESTS}?requestId=5",
    )

    private val onboardingRoutes = listOf(
        Routes.SPLASH,
        Routes.LANGUAGE,
        Routes.WELCOME,
        Routes.REGISTER,
        Routes.LOGIN,
        Routes.CREATE_PIN,
        Routes.PIN_UNLOCK,
    )

    // C. Locked state cannot reach any parental-control route.
    @Test
    fun everyParentalRouteIsProtected() {
        protectedRoutes.forEach { route ->
            assertTrue("'$route' must be protected", isProtectedRoute(route))
        }
    }

    @Test
    fun aLockedUiIsAlwaysRedirectedToThePinScreen() {
        protectedRoutes.forEach { route ->
            assertEquals(
                "locked navigation to '$route' must be redirected",
                Routes.PIN_UNLOCK,
                lockRedirectFor(route, isUnlocked = false),
            )
        }
    }

    @Test
    fun anUnlockedUiIsNeverRedirected() {
        protectedRoutes.forEach { route ->
            assertNull(lockRedirectFor(route, isUnlocked = true))
        }
    }

    // Onboarding must remain reachable while locked: it is how a locked-out user
    // signs in again (and the language picker precedes any account existing).
    @Test
    fun onboardingRoutesAreNotProtected() {
        onboardingRoutes.forEach { route ->
            assertFalse("'$route' must not be protected", isProtectedRoute(route))
            assertNull("'$route' must not be redirected", lockRedirectFor(route, isUnlocked = false))
        }
    }

    @Test
    fun thePinScreenItselfIsNotProtectedSoTheGateCannotLoop() {
        assertFalse(isProtectedRoute(Routes.PIN_UNLOCK))
        assertNull(lockRedirectFor(Routes.PIN_UNLOCK, isUnlocked = false))
    }

    @Test
    fun unknownAndBlankRoutesAreNotProtected() {
        listOf(null, "", "   ", "something_else").forEach { route ->
            assertFalse("'$route' must not be treated as protected", isProtectedRoute(route))
        }
    }

    // The existing notification whitelist behaviour must be untouched.
    @Test
    fun theExistingNotificationWhitelistIsUnchanged() {
        assertTrue(isProtectedRoute(notificationRouteFor("requests") ?: ""))
        assertNull(notificationRouteFor("home"))
        assertNull(notificationRouteFor("settings"))
    }
}
