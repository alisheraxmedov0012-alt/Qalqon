package uz.faceguard.app.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonElevation
import uz.faceguard.app.core.theme.QalqonIconSize
import uz.faceguard.app.core.theme.QalqonShapes

/**
 * QALQON application shell (UI/UX redesign, Phase 2).
 *
 * A thin Material 3 [Scaffold] with a floating, rounded bottom navigation holding the
 * five primary destinations ([QalqonTopLevelDestination]). It deliberately owns *no*
 * navigation state: the caller supplies the currently selected destination and
 * receives the taps, so the single existing `NavHostController` in [FaceGuardNavHost]
 * stays the one source of truth and no second navigation framework is introduced.
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
                QalqonNavigationBar(selected = selected, onSelect = onSelect)
            }
        },
        content = content,
    )
}

/**
 * The QALQON bottom navigation: exactly the primary destinations, no more.
 *
 * Visual treatment — a full-width, compact bar anchored to the bottom edge: it spans
 * the whole width (no side insets), is separated from the content by a hairline, and
 * the selected item is emphasised with a soft-blue pill behind its icon and a blue
 * icon/label. Only the *presentation* changed: the destination list, the selected
 * tracking, the tap callbacks, the always-visible labels and the 48 dp touch target
 * are unchanged, and the bar still hides entirely on non-tab routes (the caller only
 * passes a non-null [selected] for the primary destinations).
 */
@Composable
fun QalqonNavigationBar(
    selected: QalqonTopLevelDestination?,
    onSelect: (QalqonTopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = QalqonElevation.flat,
        shadowElevation = QalqonElevation.raised,
        border = BorderStroke(QalqonDimens.cardBorder, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = QalqonDimens.spacing.xs,
                    vertical = QalqonDimens.spacing.xs,
                ),
            horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QalqonTopLevelDestination.entries.forEach { destination ->
                QalqonNavItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

/**
 * One bottom-navigation item: a soft-blue pill behind the icon when selected, the
 * localized label beneath it. The whole item is the touch target (>= 48 dp) and the
 * label is the accessible name, so exactly one name is announced.
 */
@Composable
private fun RowScope.QalqonNavItem(
    destination: QalqonTopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = QalqonDimens.sizes.touchTarget)
            .clip(QalqonShapes.mediumShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = QalqonDimens.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(QalqonShapes.pillShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                )
                .padding(
                    horizontal = QalqonDimens.spacing.md,
                    vertical = QalqonDimens.spacing.xs,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                // The always-visible label is the accessible name; the icon carries the
                // localized description so exactly one name is announced (the label's own
                // semantics are cleared below to avoid a duplicate "Home, Home").
                contentDescription = stringResource(destination.contentDescriptionRes),
                tint = contentColor,
                modifier = Modifier.size(QalqonIconSize.md),
            )
        }
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
