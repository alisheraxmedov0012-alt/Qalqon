package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonTheme

/**
 * Child card — the foundation for the future Children tab.
 *
 * Presentation only: it receives already-resolved values and never reads a
 * repository, ViewModel or navigation. Nothing currently links it into navigation;
 * it exists so the Children/Child-detail redesign has a ready, consistent unit.
 *
 * @param name child's display name (real data)
 * @param initial avatar letter (derived by the caller, e.g. `name.firstOrNull()`)
 * @param protectionStatus already-localized protection state, or null to hide it
 * @param faceStatus already-localized face-enrollment state, or null to hide it
 * @param screenTime today's usage summary, or null to hide it
 * @param scheduleStatus active schedule summary, or null to hide it
 */
@Composable
fun QalqonChildCard(
    name: String,
    initial: String,
    protectionConfigured: Boolean,
    faceEnrolled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    protectionStatus: String? = null,
    faceStatus: String? = null,
    screenTime: String? = null,
    scheduleStatus: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentDescription: String? = null,
) {
    val colors = QalqonTheme.colors

    QalqonCard(
        modifier = modifier.semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChildAvatar(
                initial = initial,
                configured = protectionConfigured && faceEnrolled,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = QalqonDimens.spacing.md),
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val setupColor = if (protectionConfigured && faceEnrolled) {
                    colors.childConfigured
                } else {
                    colors.childNeedsSetup
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (protectionStatus != null) {
                        QalqonStatusBadge(
                            label = protectionStatus,
                            tone = if (protectionConfigured) {
                                QalqonStatusTone.ACTIVE
                            } else {
                                QalqonStatusTone.WARNING
                            },
                        )
                    }
                    if (faceStatus != null) {
                        QalqonStatusBadge(
                            label = faceStatus,
                            tone = if (faceEnrolled) {
                                QalqonStatusTone.GRANTED
                            } else {
                                QalqonStatusTone.MISSING
                            },
                        )
                    }
                }
                if (screenTime != null) {
                    Text(
                        text = screenTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (scheduleStatus != null) {
                    Text(
                        text = scheduleStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Small setup hint dot so an incomplete child is obvious at a glance.
                Box(
                    modifier = Modifier
                        .size(QalqonDimens.sizes.indicator)
                        .background(color = setupColor, shape = CircleShape),
                )
            }
        }
    }
}

/** Circular avatar showing the child's initial on a token-colored disc. */
@Composable
private fun ChildAvatar(
    initial: String,
    configured: Boolean,
    size: Dp = QalqonDimens.sizes.avatar,
) {
    val colors = QalqonTheme.colors
    val background: Color = if (configured) {
        colors.successContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .size(size)
            .background(color = background, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
