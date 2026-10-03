package uz.faceguard.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import uz.faceguard.app.R

/**
 * The five primary top-level destinations of the authenticated parent UI
 * (UI/UX redesign, Phase 2 — navigation shell; Help added as a first-class tab).
 *
 * This is the single, centralized description of the app shell. Each entry carries
 * a *stable route identity*, the localized label resource and the Material icon the
 * bottom navigation renders, so no route string or label is duplicated at the call
 * site. The routes deliberately reuse the existing screen routes — the shell is an
 * addition to the current navigation graph, not a replacement for it.
 *
 * The enum is pure (no Android/`NavController`), so its mapping and the shell's
 * contract are directly unit-testable on the JVM.
 */
enum class QalqonTopLevelDestination(
    /** Stable route identity; must match a route registered in [Routes]. */
    val route: String,
    /** Localized, user-visible label. Never hardcode navigation text. */
    @StringRes val labelRes: Int,
    /** Material icon shown above the label. */
    val icon: ImageVector,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    CHILDREN(Routes.CHILD_PROFILES, R.string.nav_children, Icons.Filled.Person),
    ACTIVITY(Routes.ACTIVITY_LOG, R.string.nav_activity, Icons.AutoMirrored.Filled.List),

    /**
     * Help is a top-level destination that reuses the existing Help Center route
     * ([Routes.HELP]); no duplicate help destination exists. The Help glyph is not part
     * of the bundled `material-icons-core` set, so the closest core icon (`Info`) is
     * used rather than adding the heavy extended-icons dependency.
     */
    HELP(Routes.HELP, R.string.nav_help, Icons.Filled.Info),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
    ;

    /**
     * The accessible name announced for this destination.
     *
     * Bottom-navigation labels are always visible, so the localized label *is* the
     * item's accessible name; exposing it as the content description keeps the intent
     * explicit (and lets an icon-only variant reuse it later) without inventing a
     * second, divergent string. Referencing the same resource is what guarantees the
     * label and its description can never drift into different translations.
     */
    @get:StringRes
    val contentDescriptionRes: Int
        get() = labelRes

    companion object {
        /**
         * The destination a concrete back-stack route belongs to, or `null` when the
         * route is not one of the primary destinations (onboarding, a child
         * detail screen, a settings sub-route, …). Matching is exact: the tab routes
         * are plain strings, so a nested route such as `child_policy/{childId}` can
         * never be mistaken for a tab.
         */
        fun forRoute(route: String?): QalqonTopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
