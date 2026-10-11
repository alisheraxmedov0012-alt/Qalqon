package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonIconSize

/**
 * The three standard non-content states.
 *
 * Previously each screen hand-rolled its own "loading…" text, empty message and
 * error card, which is why the same situation looked different on different
 * screens. These give every screen one consistent, accessible presentation.
 *
 * All text is supplied by the caller (a `stringResource` lookup), so nothing here
 * hardcodes user-facing copy.
 */

/** A centred spinner with an optional message. */
@Composable
fun QalqonLoadingState(
    modifier: Modifier = Modifier.fillMaxWidth(),
    message: String? = null,
) {
    Column(
        modifier = modifier.padding(QalqonDimens.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(QalqonIconSize.lg))
        Text(
            text = message ?: stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Empty state: optional icon, title, optional description and optional action.
 * The whole block is one accessible node so a screen reader reads it as a unit.
 */
@Composable
fun QalqonEmptyState(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /**
     * Corner shape of the action button. Defaults to Material's standard button shape,
     * so existing callers are unchanged; a screen may pass a pill
     * ([uz.faceguard.app.core.theme.QalqonShapes.pillShape]) for a rounder CTA.
     */
    actionShape: Shape = ButtonDefaults.shape,
) {
    Column(
        modifier = modifier
            .padding(QalqonDimens.spacing.xl)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
    ) {
        icon?.let {
            Column(modifier = Modifier.padding(bottom = QalqonDimens.spacing.xs)) { it() }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                shape = actionShape,
                modifier = Modifier.padding(top = QalqonDimens.spacing.sm),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * Error state: title, optional message and a retry action. Retry is a primary
 * action because an error is a dead end the user needs to leave.
 */
@Composable
fun QalqonErrorState(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    message: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .padding(QalqonDimens.spacing.xl)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = QalqonDimens.spacing.sm),
            ) {
                Text(retryLabel ?: stringResource(R.string.qal_action_retry))
            }
        }
    }
}
