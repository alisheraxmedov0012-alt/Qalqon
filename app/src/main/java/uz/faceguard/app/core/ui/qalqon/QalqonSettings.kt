package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import uz.faceguard.app.core.theme.QalqonDimens

/**
 * Settings rows — the foundation for the future Settings redesign.
 *
 * Same structure everywhere: optional leading icon, title, optional description and
 * a trailing control. Presentation only: the switch/slider values come in, the
 * changes go out through callbacks; nothing here reads a repository or DataStore.
 */

/**
 * A row with any trailing control. The whole row can be clickable when the control
 * is not itself the target (e.g. a navigation row with a chevron).
 */
@Composable
fun QalqonSettingRow(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .heightIn(min = QalqonDimens.sizes.touchTarget)
        .padding(vertical = QalqonDimens.spacing.sm)

    val rowModifier = if (onClick != null) {
        base.then(Modifier.clickable(role = Role.Button, onClick = onClick))
    } else {
        base
    }

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        leading?.let {
            Box(modifier = Modifier.padding(end = QalqonDimens.spacing.md)) { it() }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs / 2),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
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

/**
 * A switch row. The switch carries the toggle role; [stateDescription] makes the
 * on/off value explicit for accessibility (the label alone is ambiguous).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QalqonSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    stateDescription: String? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = QalqonDimens.sizes.touchTarget)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
                enabled = true,
            )
            .padding(vertical = QalqonDimens.spacing.sm)
            .semantics {
                if (stateDescription != null) this.stateDescription = stateDescription
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Box(modifier = Modifier.padding(end = QalqonDimens.spacing.md)) { it() }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs / 2),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            // The row owns the toggle, so the switch must not be a second target.
            onCheckedChange = null,
            modifier = Modifier.padding(start = QalqonDimens.spacing.sm),
        )
    }
}

/**
 * A slider row for numeric settings. [valueLabel] renders the current value as
 * text, because a bare slider position is not accessible or readable.
 */
@Composable
fun QalqonSettingSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    description: String? = null,
    valueLabel: String? = null,
    steps: Int = 0,
) {
    Column(
        modifier = modifier.padding(vertical = QalqonDimens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.semantics {
                if (valueLabel != null) stateDescription = valueLabel
            },
        )
        if (valueLabel != null) {
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
