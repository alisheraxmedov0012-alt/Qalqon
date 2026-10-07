package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonTheme

/**
 * QALQON card system — presentation only, no business logic.
 *
 * Every card draws its spacing, radius and colors from the design tokens, so the
 * cards look like one product instead of 22 hand-styled screens. Cards are
 * deliberately restrained: a hairline border plus flat/soft elevation carries the
 * hierarchy, not heavy shadows.
 */

/**
 * The base surface. Use directly for free-form content, or the named variants
 * below when the card has a known role.
 */
@Composable
fun QalqonCard(
    modifier: Modifier = Modifier,
    bordered: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = QalqonDimens.cardPadding,
    /**
     * Surface shadow. Defaults to flat, so every existing caller keeps its current
     * look; a screen may pass [QalqonDimens.elevation.raised] for a subtle lift.
     */
    elevation: Dp = QalqonDimens.elevation.flat,
    /**
     * Corner shape. Defaults to the shared [QalqonDimens.cardCorner], so existing
     * callers are unchanged; a screen may pass a softer radius (e.g.
     * [uz.faceguard.app.core.theme.QalqonShapes.xLargeShape]) for a premium surface.
     */
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(QalqonDimens.cardCorner),
    onClick: (() -> Unit)? = null,
    /**
     * Accessibility label for the card's click action. Defaults to null so existing
     * callers are unchanged; a clickable card passes a localized description so a screen
     * reader announces the action instead of only the role.
     */
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (bordered) {
        BorderStroke(QalqonDimens.cardBorder, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
    } else {
        Modifier
    }

    // OutlinedCard gives the hairline border; a plain Card is used when borderless.
    // Both share the same token elevation so a lifted card looks identical in either form.
    val cardElevation = CardDefaults.cardElevation(defaultElevation = elevation)
    if (bordered) {
        OutlinedCard(
            modifier = modifier.then(clickModifier),
            shape = shape,
            border = border!!,
            elevation = cardElevation,
            colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
            content = {
                Column(
                    modifier = Modifier.padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
                    content = content,
                )
            },
        )
    } else {
        Card(
            modifier = modifier.then(clickModifier),
            shape = shape,
            elevation = cardElevation,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            content = {
                Column(
                    modifier = Modifier.padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
                    content = content,
                )
            },
        )
    }
}

/**
 * A titled section — the workhorse replacement for the legacy `SectionCard`, with
 * the same visual language (title, optional subtitle, content slot) plus an
 * optional trailing action and an optional leading status color.
 */
@Composable
fun QalqonSectionCard(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    subtitle: String? = null,
    accent: Color? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    QalqonCard(modifier = modifier) {
        if (accent != null) {
            StatusAccentStrip(color = accent)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/** A thin accent line conveying a section's status without shouting. */
@Composable
private fun StatusAccentStrip(color: Color) {
    androidx.compose.material3.HorizontalDivider(
        color = color,
        thickness = QalqonDimens.cardBorder * 3,
    )
}

/**
 * Status card: icon + title + supporting text + status indicator + optional action.
 * Used for protection state, capability summaries and any "is it in the state I
 * expect" surface.
 */
@Composable
fun QalqonStatusCard(
    title: String,
    statusColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /**
     * Surface the card draws on. Defaults to the plain surface, so every existing
     * caller is unchanged; passing a tone-derived tint lets a screen make one status
     * card visually dominant without leaving the semantic palette.
     */
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    QalqonCard(modifier = modifier, containerColor = containerColor, onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            leadingIcon?.let {
                Box(modifier = Modifier.padding(end = QalqonDimens.spacing.md)) { it() }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = statusColor)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = QalqonDimens.spacing.sm),
                    )
                }
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = QalqonDimens.spacing.xs),
                    )
                }
            }
            trailing?.invoke()
        }
        content()
    }
}

/** The small round state indicator shared by status surfaces. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .width(QalqonDimens.sizes.indicator)
            .height(QalqonDimens.sizes.indicator),
    ) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

/**
 * Metric card: one large figure with a label, an optional supporting line and an
 * optional trend/status color. Intended for the dashboard ("today's screen time",
 * "protected apps", "pending requests").
 */
@Composable
fun QalqonMetricCard(
    metric: String,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    QalqonCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = metric,
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent ?: MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = QalqonDimens.spacing.xs),
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * A single list row: leading icon/avatar, title, optional subtitle and trailing
 * content. The whole row is the touch target when [onClick] is provided, and is at
 * least [uz.faceguard.app.core.theme.QalqonSizes.touchTarget] tall.
 */
@Composable
fun QalqonListRow(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .then(clickModifier)
            .padding(
                horizontal = QalqonDimens.cardPadding,
                vertical = QalqonDimens.rowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Box(modifier = Modifier.padding(end = QalqonDimens.spacing.md)) { it() }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.let {
            Box(modifier = Modifier.padding(start = QalqonDimens.spacing.sm)) { it() }
        }
    }
}

/** A card whose content is a stack of [QalqonListRow]s. */
@Composable
fun QalqonListCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    QalqonCard(modifier = modifier, contentPadding = 0.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs), content = content)
    }
}

/** Divider styled from the tokens, for use between list rows. */
@Composable
fun QalqonDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = QalqonDimens.cardBorder,
    )
}

/** Reads the semantic palette; kept internal-free so screens can preview tokens. */
@Composable
internal fun semantic() = QalqonTheme.colors
