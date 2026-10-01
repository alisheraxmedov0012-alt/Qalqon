package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonShapes

/**
 * Confirmation dialog foundation.
 *
 * The app already has several hand-built `AlertDialog`s (full reset, schedule
 * delete, policy change). They are **not** migrated in this phase; this is the
 * shared foundation those migrations will use, so a destructive confirmation looks
 * and behaves the same everywhere.
 *
 * The confirm action is emphasised with the error color when [destructive] is true,
 * which is the only signal a user gets that an action cannot be undone.
 */
@Composable
fun QalqonConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
) {
    val resolvedConfirm = confirmLabel ?: stringResource(R.string.qal_action_confirm)
    val resolvedDismiss = dismissLabel ?: stringResource(R.string.qal_action_cancel)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(QalqonShapes.large),
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = resolvedConfirm,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(resolvedDismiss) }
        },
    )
}
