package uz.faceguard.app.core.ui.qalqon

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.faceguard.app.R

/**
 * Release Block 3 (ACC-03): the single prominent-disclosure + affirmative-consent dialog
 * for Android Accessibility settings.
 *
 * Google Play's Accessibility API policy requires a clear in-app disclosure and affirmative
 * user consent *before* the user is sent to enable the service. Every screen that offers a
 * path to Accessibility settings renders **this** dialog, and the settings intent may be
 * launched **only** from [onConfirm] — never from the tap that opened the dialog, and never
 * from a requirements row, a degraded-banner "fix" action or any other shortcut directly.
 *
 * The wording is defined once here (backed by the `accessibility_disclosure_*` resources) so
 * the disclosure cannot drift between screens. Camera, Usage Access, Overlay and
 * notification flows deliberately do **not** use this gate: they keep their own existing
 * explanations and permission behaviour.
 *
 * The dialog records nothing: consent is the explicit [onConfirm] tap, and declining or
 * dismissing simply closes it ([onDismiss]).
 */
@Composable
fun AccessibilityDisclosureDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = { Text(stringResource(R.string.accessibility_disclosure_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.accessibility_disclosure_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.accessibility_disclosure_decline))
            }
        },
    )
}
