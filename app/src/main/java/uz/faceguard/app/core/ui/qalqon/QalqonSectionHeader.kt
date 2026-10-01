package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import uz.faceguard.app.core.theme.QalqonDimens

/**
 * Section header for the redesigned screens.
 *
 * ```
 * Farzandlar                    Ko'rish
 * 3 ta profil
 * ```
 *
 * Title (required), supporting text (optional) and a trailing action (optional).
 * The action slot takes a composable so callers can use a text button, an icon
 * button or a link without this component knowing about any of them.
 */
@Composable
fun QalqonSectionHeader(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    supportingText: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(
            top = QalqonDimens.spacing.sm,
            bottom = QalqonDimens.spacing.xs,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs / 2),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke()
    }
}
