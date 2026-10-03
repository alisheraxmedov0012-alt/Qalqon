package uz.faceguard.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-UI correction: the bottom-navigation decision contract.
 *
 * Pure JVM — exercises the deterministic [BottomNavigationPolicy] over the full 5×5 tab
 * matrix and the repeated-tap / rapid-switch cases, without a `NavController` or device.
 */
class BottomNavigationTest {

    private val tabs = QalqonTopLevelDestination.entries

    // ------------------------------------------------------------ 5x5 switch matrix

    @Test
    fun everyTabCanReachEveryOtherTab() {
        tabs.forEach { from ->
            tabs.filter { it != from }.forEach { to ->
                val decision = BottomNavigationPolicy.resolve(from.route, to)
                assertEquals(
                    "from ${from.name} to ${to.name}",
                    BottomNavigationPolicy.Decision.Switch(to.route),
                    decision,
                )
                assertNotEquals("a switch must change the route", from.route, to.route)
            }
        }
    }

    @Test
    fun eachTabMapsToItsOwnRoute() {
        tabs.forEach { tab ->
            assertEquals(tab, QalqonTopLevelDestination.forRoute(tab.route))
        }
        // The five routes are unique.
        assertEquals(tabs.size, tabs.map { it.route }.toSet().size)
    }

    // ---------------------------------------------------------- repeated / same-tab taps

    @Test
    fun reTappingTheActiveTabResetsToItsRootInsteadOfSilentlyDoingNothing() {
        tabs.forEach { tab ->
            assertEquals(
                "re-tap of ${tab.name}",
                BottomNavigationPolicy.Decision.ResetToRoot(tab.route),
                BottomNavigationPolicy.resolve(tab.route, tab),
            )
        }
    }

    @Test
    fun aRepeatedTapIsStableAndNeverProducesASwitchOrDuplicate() {
        // Tapping the same tab repeatedly must always decide the same, non-switching action.
        val tab = QalqonTopLevelDestination.HOME
        repeat(5) {
            val decision = BottomNavigationPolicy.resolve(tab.route, tab)
            assertEquals(BottomNavigationPolicy.Decision.ResetToRoot(tab.route), decision)
        }
    }

    @Test
    fun aNonTabRouteAlwaysSwitchesToTheTappedTab() {
        // e.g. arriving from a settings sub-page or a child detail must still switch.
        listOf(
            Routes.SETTINGS_APPS,
            Routes.SETTINGS_PROTECTION,
            Routes.PROTECTION,
            Routes.CHILD_PROFILES + "/7",
            null,
        ).forEach { from ->
            tabs.forEach { to ->
                assertEquals(
                    "from '$from' to ${to.name}",
                    BottomNavigationPolicy.Decision.Switch(to.route),
                    BottomNavigationPolicy.resolve(from, to),
                )
            }
        }
    }

    @Test
    fun rapidAlternationBetweenTwoTabsAlwaysSwitchesBothWays() {
        val a = QalqonTopLevelDestination.HOME
        val b = QalqonTopLevelDestination.SETTINGS
        // Model the back stack as "one of the two tabs is current" and alternate taps.
        repeat(10) {
            assertEquals(BottomNavigationPolicy.Decision.Switch(b.route), BottomNavigationPolicy.resolve(a.route, b))
            assertEquals(BottomNavigationPolicy.Decision.Switch(a.route), BottomNavigationPolicy.resolve(b.route, a))
        }
    }

    // ------------------------------------------------------------------ bar visibility

    @Test
    fun everyTabRouteSelectsExactlyThatTabAndNoOtherRouteDoes() {
        tabs.forEach { tab ->
            assertEquals(tab, QalqonTopLevelDestination.forRoute(tab.route))
        }
        // A non-tab route never selects a tab (the bar hides there, unchanged).
        assertNull(QalqonTopLevelDestination.forRoute(Routes.SETTINGS_APPS))
        assertNull(QalqonTopLevelDestination.forRoute(Routes.PROTECTION))
        assertNull(QalqonTopLevelDestination.forRoute(Routes.HELP_ASSISTANT))
    }
}
