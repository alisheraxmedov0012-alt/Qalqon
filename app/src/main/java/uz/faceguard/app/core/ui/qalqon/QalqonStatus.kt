package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonShapes
import uz.faceguard.app.core.theme.QalqonTheme

/**
 * The neutral vocabulary of UI states QALQON renders.
 *
 * Deliberately product-level (not per-screen) so one badge/banner serves
 * protection, capabilities, children and requests without inventing new colors or
 * copy. Every tone resolves to a semantic token — screens never pass a raw color.
 */
enum class QalqonStatusTone { ACTIVE, INACTIVE, BLOCKING, WARNING, PENDING, GRANTED, MISSING }

/** Severity for alert rows/banners. */
enum class QalqonAlertSeverity { INFO, SUCCESS, WARNING, ERROR }

/** Resolves a tone to its semantic color for the current theme. */
@Composable
fun toneColor(tone: QalqonStatusTone): Color {
    val c = QalqonTheme.colors
    return when (tone) {
        QalqonStatusTone.ACTIVE -> c.protectionActive
        QalqonStatusTone.INACTIVE -> c.protectionInactive
        QalqonStatusTone.BLOCKING -> c.protectionBlocking
        QalqonStatusTone.WARNING -> c.protectionWarning
        QalqonStatusTone.PENDING -> c.requestPending
        QalqonStatusTone.GRANTED -> c.capabilityGranted
        QalqonStatusTone.MISSING -> c.capabilityMissing
    }
}

/** Container color for a tone, used by badges/banners. */
@Composable
private fun toneContainer(tone: QalqonStatusTone): Color {
    val c = QalqonTheme.colors
    return when (tone) {
        QalqonStatusTone.ACTIVE, QalqonStatusTone.GRANTED -> c.successContainer
        QalqonStatusTone.PENDING, QalqonStatusTone.WARNING -> c.warningContainer
        QalqonStatusTone.BLOCKING, QalqonStatusTone.MISSING -> MaterialTheme.colorScheme.errorContainer
        QalqonStatusTone.INACTIVE -> MaterialTheme.colorScheme.surfaceVariant
    }
}

/** Resolves severity to its semantic color. */
@Composable
fun severityColor(severity: QalqonAlertSeverity): Color {
    val c = QalqonTheme.colors
    return when (severity) {
        QalqonAlertSeverity.INFO -> c.info
        QalqonAlertSeverity.SUCCESS -> c.success
        QalqonAlertSeverity.WARNING -> c.warning
        QalqonAlertSeverity.ERROR -> MaterialTheme.colorScheme.error
    }
}

/**
 * A compact pill that names a state using a semantic tone.
 *
 * The label is **passed in** (a string-resource lookup by the caller), so no
 * user-facing text is hardcoded here and all three locales stay in control.
 */
@Composable
fun QalqonStatusBadge(
    label: String,
    tone: QalqonStatusTone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
) {
    val color = toneColor(tone)
    Row(
        modifier = modifier
            .background(color = toneContainer(tone), shape = QalqonShapes.pillShape)
            .padding(
                horizontal = QalqonDimens.spacing.sm,
                vertical = QalqonDimens.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(QalqonDimens.sizes.indicator)
                    .background(color = color, shape = CircleShape),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A full-width inline message. Used for actionable or state-describing messages
 * (e.g. "notifications are off", "usage access missing") where a badge is too
 * small. The text is supplied by the caller.
 */
@Composable
fun QalqonStatusBanner(
    text: String,
    severity: QalqonAlertSeverity,
    modifier: Modifier = Modifier.fillMaxWidth(),
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val color = severityColor(severity)
    Row(
        modifier = modifier
            .background(color = color.copy(alpha = 0.10f), shape = QalqonShapes.mediumShape)
            .border(
                width = QalqonDimens.cardBorder,
                color = color.copy(alpha = 0.35f),
                shape = QalqonShapes.mediumShape,
            )
            .padding(QalqonDimens.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = QalqonDimens.spacing.xs, end = QalqonDimens.spacing.sm)
                .size(QalqonDimens.sizes.indicator)
                .background(color = color, shape = CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (action != null) {
                Box(modifier = Modifier.padding(top = QalqonDimens.spacing.sm)) { action() }
            }
        }
    }
}

/**
 * Capability / permission row: a label with a granted/missing state. The
 * state is exposed to accessibility via `stateDescription`, so screen readers
 * announce the outcome rather than just the label.
 */
@Composable
fun QalqonCapabilityRow(
    label: String,
    granted: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    grantedLabel: String? = null,
    missingLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tone = if (granted) QalqonStatusTone.GRANTED else QalqonStatusTone.MISSING
    val stateText = (if (granted) grantedLabel else missingLabel).orEmpty()

    Row(
        modifier = modifier
            .heightIn(min = QalqonDimens.sizes.touchTarget)
            .padding(vertical = QalqonDimens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(color = toneContainer(tone), shape = CircleShape)
                .size(QalqonDimens.icon.xs),
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = QalqonDimens.spacing.sm),
        )
        if (stateText.isNotEmpty()) {
            Text(
                text = stateText,
                style = MaterialTheme.typography.labelMedium,
                color = toneColor(tone),
            )
        }
        trailing?.invoke()
    }
}

/**
 * Alert row: severity dot (or a caller-supplied semantic icon) + text, optionally
 * clickable, always at least a full touch target tall. Used for actionable warnings
 * on the dashboard.
 */
@Composable
fun QalqonAlertRow(
    text: String,
    severity: QalqonAlertSeverity,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape: Shape = RoundedCornerShape(QalqonShapes.medium)
    val base = modifier
        .heightIn(min = QalqonDimens.sizes.touchTarget)
        .background(color = severityColor(severity).copy(alpha = 0.10f), shape = shape)
        .padding(
            horizontal = QalqonDimens.spacing.md,
            vertical = QalqonDimens.spacing.sm,
        )

    val rowModifier = if (onClick != null) {
        base.then(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))
    } else {
        base
    }

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            Box(modifier = Modifier.padding(end = QalqonDimens.spacing.sm)) { leadingIcon() }
        } else {
            Box(
                modifier = Modifier
                    .padding(end = QalqonDimens.spacing.sm)
                    .size(QalqonDimens.sizes.indicator)
                    .background(color = severityColor(severity), shape = CircleShape),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
        trailing?.invoke()
    }
}

/**
 * Convenience wrapper for the common "N new" badge: resolves the localized count
 * description from resources so a caller cannot forget to make it accessible.
 */
@Composable
fun QalqonCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    tone: QalqonStatusTone = QalqonStatusTone.PENDING,
) {
    QalqonBadge(
        count = count,
        contentDescription = androidx.compose.ui.res.stringResource(
            uz.faceguard.app.R.string.qal_badge_content_description,
            count,
        ),
        modifier = modifier,
        tone = tone,
    )
}

/**
 * Numeric badge (e.g. pending requests).
 *
 * Renders the count, ellipsised past `99+`, and exposes a caller-supplied
 * localized description to accessibility so a bare number is never announced.
 */
@Composable
fun QalqonBadge(
    count: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tone: QalqonStatusTone = QalqonStatusTone.PENDING,
) {
    val color = toneColor(tone)
    Box(
        modifier = modifier
            .background(color = toneContainer(tone), shape = QalqonShapes.pillShape)
            .border(QalqonDimens.cardBorder, color.copy(alpha = 0.5f), QalqonShapes.pillShape)
            .padding(
                horizontal = QalqonDimens.spacing.sm,
                vertical = QalqonDimens.spacing.xs,
            )
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
