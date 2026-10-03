package uz.faceguard.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonElevation
import uz.faceguard.app.core.theme.QalqonIconSize

/**
 * QALQON application shell (UI/UX redesign, Phase 2).
 *
 * A thin Material 3 [Scaffold] with a [NavigationBar] holding the five primary
 * destinations ([QalqonTopLevelDestination]). It deliberately owns *no* navigation
 * state: the caller supplies the currently selected destination and receives the
 * taps, so the single existing `NavHostController` in [FaceGuardNavHost] stays the
 * one source of truth and no second navigation framework is introduced.
 *
 * The shell is only shown for the primary destinations. On every other route
 * (onboarding, the PIN gate, a child detail screen, a settings sub-route, …) the
 * bottom bar is hidden and [content] receives the plain window insets, so those
 * screens keep the exact full-screen layout they had before.
 */
@Composable
fun QalqonAppShell(
    selected: QalqonTopLevelDestination?,
    onSelect: (QalqonTopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Defaults to "shown exactly when a primary destination is selected". The bar is
     * never shown while the UI is locked: locked users are redirected away from the
     * protected tab routes by the existing gate, so [selected] is `null` for them.
     */
    showBottomBar: Boolean = selected != null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                // A premium anchored bar: a hairline separates it from scrolling content and
                // the bar itself sits on the tonal `surfaceContainer` role, so the bottom
                // navigation reads as a deliberate surface rather than a floating strip.
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = QalqonDimens.cardBorder,
                    )
                    QalqonNavigationBar(selected = selected, onSelect = onSelect)
                }
            }
        },
        content = content,
    )
}

/**
 * The QALQON bottom navigation: exactly the primary destinations, no more.
 *
 * Uses the real Material 3 [NavigationBar]/[NavigationBarItem] (not a custom bar),
 * themed with the Phase 1 QALQON tokens: the surface container color, flat tonal
 * elevation, the icon-size token and the label typography. The selected indicator,
 * the selected/unselected colors and the 48 dp minimum touch target all come from
 * Material 3, so the bar behaves like a modern production Android app in both light
 * and dark themes and under gesture/3-button navigation (the bar applies the
 * platform navigation-bar insets itself).
 */
@Composable
fun QalqonNavigationBar(
    selected: QalqonTopLevelDestination?,
    onSelect: (QalqonTopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        // Tonal container role (not plain `surface`) so the anchored bar layers above the
        // screen background in both light and dark themes.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = QalqonElevation.flat,
    ) {
        QalqonTopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        // The always-visible label is the accessible name; the icon
                        // carries the localized description so exactly one name is
                        // announced (the label's own semantics are cleared below to
                        // avoid a duplicate "Home, Home" announcement).
                        contentDescription = stringResource(destination.contentDescriptionRes),
                        modifier = Modifier.size(QalqonIconSize.md),
                    )
                },
                label = {
                    Text(
                        text = stringResource(destination.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}
