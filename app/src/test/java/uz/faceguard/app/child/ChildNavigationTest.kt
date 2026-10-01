package uz.faceguard.app.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.navigation.Routes
import uz.faceguard.app.navigation.isProtectedRoute
import uz.faceguard.app.navigation.lockRedirectFor

/**
 * UI/UX redesign Phase 4: the Child Detail route and child-context navigation.
 *
 * Pure JVM. Pins that every child-scoped destination carries the child id (so
 * Child A's context can never open Child B's data) and that the new hub stays behind
 * the existing PIN gate.
 */
class ChildNavigationTest {

    @Test
    fun theChildDetailRouteCarriesTheChildId() {
        assertEquals("child_detail/7", Routes.childDetail(7L))
        assertEquals("child_detail/{childId}", Routes.CHILD_DETAIL)
    }

    @Test
    fun theAppsAndScreenTimeDestinationsAreDistinctButShareTheChildContext() {
        // Apps and Screen time are different entry points on the same underlying screen.
        assertNotEquals(Routes.childPolicy(7L), Routes.childScreenTime(7L))
        assertTrue(Routes.childPolicy(7L).startsWith("child_policy/7"))
        assertTrue(Routes.childScreenTime(7L).startsWith("child_policy/7"))
        assertTrue(Routes.childScreenTime(7L).contains("section=screen_time"))
    }

    @Test
    fun everyChildControlDestinationCarriesTheSameChildId() {
        val childId = 42L
        listOf(
            Routes.childDetail(childId),
            Routes.childPolicy(childId),
            Routes.childScreenTime(childId),
            Routes.childSchedules(childId),
            Routes.childEyeSafety(childId),
            Routes.childFaceEnrollment(childId),
        ).forEach { route ->
            assertTrue("'$route' must carry the child id", route.contains("/$childId"))
        }
    }

    @Test
    fun twoChildrenNeverShareARoute() {
        assertNotEquals(Routes.childDetail(1L), Routes.childDetail(2L))
        assertNotEquals(Routes.childPolicy(1L), Routes.childPolicy(2L))
    }

    @Test
    fun theChildDetailHubIsBehindThePinGate() {
        assertTrue(isProtectedRoute(Routes.childDetail(7L)))
        assertEquals(
            Routes.PIN_UNLOCK,
            lockRedirectFor(Routes.childDetail(7L), isUnlocked = false),
        )
    }

    @Test
    fun theScreenTimeSectionRouteIsStillProtected() {
        // The optional query suffix must not hide the route from the lock gate.
        assertTrue(isProtectedRoute(Routes.childScreenTime(7L)))
        assertEquals(
            Routes.PIN_UNLOCK,
            lockRedirectFor(Routes.childScreenTime(7L), isUnlocked = false),
        )
    }

    @Test
    fun anUnlockedUiIsNeverRedirectedFromAChildRoute() {
        assertNull(lockRedirectFor(Routes.childDetail(7L), isUnlocked = true))
        assertNull(lockRedirectFor(Routes.childPolicy(7L), isUnlocked = true))
    }
}
