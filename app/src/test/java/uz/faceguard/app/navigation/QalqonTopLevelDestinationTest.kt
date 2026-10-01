package uz.faceguard.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R

/**
 * UI/UX redesign Phase 2: the top-level destination model.
 *
 * Pins the *shape* of the app shell — exactly four primary destinations, in order,
 * with unique routes and localized labels/icons — and the route mapping onto the
 * existing screens. Pure JVM: the model holds no Android/runtime state.
 */
class QalqonTopLevelDestinationTest {

    @Test
    fun thereAreExactlyFourPrimaryDestinationsInProductOrder() {
        assertEquals(
            listOf("HOME", "CHILDREN", "ACTIVITY", "SETTINGS"),
            QalqonTopLevelDestination.entries.map { it.name },
        )
    }

    @Test
    fun everyPrimaryDestinationExists() {
        assertNotNull(QalqonTopLevelDestination.valueOf("HOME"))
        assertNotNull(QalqonTopLevelDestination.valueOf("CHILDREN"))
        assertNotNull(QalqonTopLevelDestination.valueOf("ACTIVITY"))
        assertNotNull(QalqonTopLevelDestination.valueOf("SETTINGS"))
    }

    @Test
    fun routesAreStableAndUnique() {
        val routes = QalqonTopLevelDestination.entries.map { it.route }
        assertEquals("routes must be unique", routes.size, routes.toSet().size)
        assertEquals(
            listOf(Routes.HOME, Routes.CHILD_PROFILES, Routes.ACTIVITY_LOG, Routes.SETTINGS),
            routes,
        )
    }

    @Test
    fun eachDestinationMapsToItsOwnLocalizedLabelResource() {
        assertEquals(R.string.nav_home, QalqonTopLevelDestination.HOME.labelRes)
        assertEquals(R.string.nav_children, QalqonTopLevelDestination.CHILDREN.labelRes)
        assertEquals(R.string.nav_activity, QalqonTopLevelDestination.ACTIVITY.labelRes)
        assertEquals(R.string.nav_settings, QalqonTopLevelDestination.SETTINGS.labelRes)
    }

    @Test
    fun eachDestinationHasAnAccessibleDescriptionAndAIcon() {
        QalqonTopLevelDestination.entries.forEach { destination ->
            assertEquals(
                "the accessible name must be the localized label",
                destination.labelRes,
                destination.contentDescriptionRes,
            )
            assertNotNull("${destination.name} must have an icon", destination.icon)
        }
    }

    // ---------------------------------------------------------- route mapping

    @Test
    fun theTabRouteOfEveryDestinationResolvesBackToThatDestination() {
        QalqonTopLevelDestination.entries.forEach { destination ->
            assertEquals(destination, QalqonTopLevelDestination.forRoute(destination.route))
        }
    }

    @Test
    fun homeMapsToTheExistingHomeDestination() {
        assertEquals(Routes.HOME, QalqonTopLevelDestination.HOME.route)
    }

    @Test
    fun childrenMapsToTheExistingChildProfilesDestination() {
        assertEquals(Routes.CHILD_PROFILES, QalqonTopLevelDestination.CHILDREN.route)
    }

    @Test
    fun activityMapsToTheExistingActivityDestination() {
        assertEquals(Routes.ACTIVITY_LOG, QalqonTopLevelDestination.ACTIVITY.route)
    }

    @Test
    fun settingsMapsToTheExistingSettingsDestination() {
        assertEquals(Routes.SETTINGS, QalqonTopLevelDestination.SETTINGS.route)
    }

    @Test
    fun nonTabAndBlankRoutesHaveNoSelectedDestination() {
        // The bottom bar must be off every non-tab route: onboarding, the PIN gate,
        // child details and settings sub-routes.
        listOf(
            null,
            "",
            Routes.SPLASH,
            Routes.PIN_UNLOCK,
            Routes.WELCOME,
            Routes.LOGIN,
            Routes.REGISTER,
            Routes.CREATE_PIN,
            Routes.LANGUAGE,
            Routes.SETTINGS_APPS,
            Routes.REQUESTS,
            Routes.PARENT_PROFILE,
            Routes.CHILD_FACE_ENROLLMENT,
            Routes.CHILD_POLICY,
            Routes.CHILD_SCHEDULES,
            Routes.CHILD_EYE_SAFETY,
            Routes.PROTECTION,
            Routes.PRIVACY,
            Routes.HELP,
            Routes.RECOGNITION_DEBUG,
        ).forEach { route ->
            assertNull("'$route' must not select a tab", QalqonTopLevelDestination.forRoute(route))
        }
    }

    @Test
    fun aNestedRouteIsNeverMistakenForATab() {
        // The tab route strings must match exactly; a route that merely shares a
        // prefix (a child detail screen) is not a tab.
        assertNull(QalqonTopLevelDestination.forRoute("${Routes.CHILD_PROFILES}/7"))
        assertNull(QalqonTopLevelDestination.forRoute("${Routes.SETTINGS}/apps"))
    }

    @Test
    fun theFourTabRoutesAreStillTheProtectedParentalControlRoutes() {
        // The shell must live behind the existing lock: every tab is protected.
        assertTrue(QalqonTopLevelDestination.entries.all { isProtectedRoute(it.route) })
    }
}
