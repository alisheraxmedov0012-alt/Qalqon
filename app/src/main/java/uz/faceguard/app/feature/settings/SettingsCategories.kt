package uz.faceguard.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import uz.faceguard.app.R
import uz.faceguard.app.navigation.Routes

/**
 * UI/UX redesign, Phase 6: the Settings centre's category model.
 *
 * The single, centralized description of the Settings hub. Each category carries a
 * stable route identity, the localized label and description the row renders, and the
 * Material icon. This is what stops Settings from becoming one long form: the hub is
 * a short, scannable list of categories, and each category owns its own page.
 *
 * [debugOnly] categories are shown only when the debug screens are enabled
 * (`BuildConfig.DEBUG`, via [uz.faceguard.app.core.debug.DebugFlags]), so developer
 * diagnostics never appear in a release UI.
 *
 * Pure (no Android/`NavController`), so the hub's contract is unit-testable on the JVM.
 */
enum class SettingsCategory(
    val route: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val debugOnly: Boolean = false,
) {
    PROTECTION(
        Routes.SETTINGS_PROTECTION,
        R.string.settings_category_protection,
        R.string.settings_category_protection_desc,
        Icons.Filled.Lock,
    ),
    FAMILY(
        Routes.SETTINGS_FAMILY,
        R.string.settings_category_family,
        R.string.settings_category_family_desc,
        Icons.Filled.Person,
    ),
    SECURITY(
        Routes.SETTINGS_SECURITY,
        R.string.settings_category_security,
        R.string.settings_category_security_desc,
        Icons.Filled.AccountCircle,
    ),
    APPEARANCE(
        Routes.SETTINGS_APPEARANCE,
        R.string.settings_category_appearance,
        R.string.settings_category_appearance_desc,
        Icons.Filled.Star,
    ),
    NOTIFICATIONS(
        Routes.SETTINGS_NOTIFICATIONS,
        R.string.settings_category_notifications,
        R.string.settings_category_notifications_desc,
        Icons.Filled.Notifications,
    ),
    PRIVACY(
        Routes.SETTINGS_PRIVACY,
        R.string.settings_category_privacy,
        R.string.settings_category_privacy_desc,
        Icons.Filled.Info,
    ),
    SUPPORT(
        Routes.SETTINGS_SUPPORT,
        R.string.settings_category_support,
        R.string.settings_category_support_desc,
        Icons.Filled.Email,
    ),
    DEVELOPER(
        Routes.SETTINGS_DEVELOPER,
        R.string.settings_category_developer,
        R.string.settings_category_developer_desc,
        Icons.Filled.Build,
        debugOnly = true,
    ),
}

/**
 * The categories the hub shows, in reading order. The developer category is present
 * only when [debugScreensEnabled] is true, so a release build never renders it.
 */
fun settingsCategories(debugScreensEnabled: Boolean): List<SettingsCategory> =
    SettingsCategory.entries.filter { debugScreensEnabled || !it.debugOnly }

/**
 * Post-UI correction: the logical groups the Settings hub renders, so the centre reads
 * as a short, scannable set of sections instead of one flat list. Every category keeps
 * its own route and page — this is presentation grouping only, no new setting.
 */
enum class SettingsGroup(@StringRes val labelRes: Int) {
    RULES(R.string.settings_group_rules),
    FAMILY(R.string.settings_group_family),
    NOTIFICATIONS(R.string.settings_group_notifications),
    PRIVACY_SECURITY(R.string.settings_group_privacy_security),
    APPEARANCE(R.string.settings_group_appearance),
    SUPPORT(R.string.settings_group_support),
    DEVELOPER(R.string.settings_group_developer),
}

/**
 * The visible categories grouped for display, in reading order. The developer group is
 * present only when [debugScreensEnabled] is true (an empty group is dropped), so a
 * release build never renders it.
 */
fun settingsGroups(debugScreensEnabled: Boolean): List<Pair<SettingsGroup, List<SettingsCategory>>> {
    val groups = linkedMapOf(
        SettingsGroup.RULES to listOf(SettingsCategory.PROTECTION),
        SettingsGroup.FAMILY to listOf(SettingsCategory.FAMILY),
        SettingsGroup.NOTIFICATIONS to listOf(SettingsCategory.NOTIFICATIONS),
        SettingsGroup.PRIVACY_SECURITY to listOf(SettingsCategory.PRIVACY, SettingsCategory.SECURITY),
        SettingsGroup.APPEARANCE to listOf(SettingsCategory.APPEARANCE),
        SettingsGroup.SUPPORT to listOf(SettingsCategory.SUPPORT),
        SettingsGroup.DEVELOPER to listOf(SettingsCategory.DEVELOPER),
    )
    return groups.mapNotNull { (group, categories) ->
        val visible = categories.filter { debugScreensEnabled || !it.debugOnly }
        if (visible.isEmpty()) null else group to visible
    }
}
