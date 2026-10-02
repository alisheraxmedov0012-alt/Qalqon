package uz.faceguard.app.navigation

/**
 * The deterministic bottom-navigation decision (post-UI correction).
 *
 * Root cause this fixes: [FaceGuardNavHost]'s tab handler issued
 * `navigate(route) { popUpTo(HOME){saveState=true}; launchSingleTop=true; restoreState=true }`
 * for **every** tap, including re-tapping the tab that is already selected. The anchor
 * `popUpTo(Routes.HOME)` is exclusive, so `HOME` is never popped and its state is never
 * saved, while `launchSingleTop` collapses a tap on the current destination to a **silent
 * no-op** — the back stack (and therefore `currentBackStackEntryAsState()`, which drives the
 * bar's selected item) does not change, so the screen appears not to switch. There was no
 * explicit "the tapped tab is already the current tab" branch.
 *
 * This policy makes the decision explicit and unit-testable (pure, no `NavController`):
 *  - tapping a **different** tab -> [Decision.Switch] (a real route change, keeping the
 *    existing save/restore state preservation);
 *  - tapping the **tab you are already on** -> [Decision.ResetToRoot] (pop that tab's own
 *    inner stack back to its root — the standard Material re-select — never a silent nothing
 *    and never a duplicate destination).
 */
object BottomNavigationPolicy {

    sealed interface Decision {
        /** Navigate to [route], preserving the tab's saved state (different tab tapped). */
        data class Switch(val route: String) : Decision

        /** Pop [route]'s inner stack back to the tab root (the active tab was re-tapped). */
        data class ResetToRoot(val route: String) : Decision
    }

    /**
     * Resolves the decision for a bottom-navigation tap given the current route.
     *
     * [currentRoute] is the back-stack entry's route (may be `null` transiently, or a
     * non-tab route such as a settings sub-page).
     */
    fun resolve(currentRoute: String?, destination: QalqonTopLevelDestination): Decision {
        val currentTab = QalqonTopLevelDestination.forRoute(currentRoute)
        return if (currentTab == destination) {
            Decision.ResetToRoot(destination.route)
        } else {
            Decision.Switch(destination.route)
        }
    }
}
