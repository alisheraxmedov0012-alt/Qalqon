package uz.faceguard.app.core.ui.qalqon

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import uz.faceguard.app.core.theme.FaceGuardTheme
import uz.faceguard.app.core.theme.QalqonDimens

/**
 * Design-tooling previews for the QALQON component library.
 *
 * One place that renders the shared components in both themes, so a change to a card,
 * a section header or a state can be verified in Android Studio without running the app.
 * Preview-only: nothing here is referenced by production code, and no user-facing text
 * from `strings.xml` is duplicated into the app — the sample strings below exist solely
 * for the preview surface.
 */
@Preview(name = "Cards — light", showBackground = true)
@Preview(name = "Cards — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewQalqonCards() {
    FaceGuardTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
        ) {
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                Text("Base card", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Supporting line for the base surface.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QalqonSectionCard(
                title = "Section card",
                subtitle = "Optional supporting subtitle",
                accent = MaterialTheme.colorScheme.primary,
            ) {
                Text("Section content", style = MaterialTheme.typography.bodyMedium)
            }
            QalqonMetricCard(metric = "1 soat 24 daqiqa", label = "Screen time", supportingText = "Today")
            QalqonStatusCard(
                title = "Protection active",
                statusColor = MaterialTheme.colorScheme.primary,
                supportingText = "Watching protected apps",
            )
            QalqonListRow(
                title = "List row",
                subtitle = "With a supporting line",
                onClick = {},
            )
        }
    }
}

@Preview(name = "Status + states — light", showBackground = true)
@Preview(name = "Status + states — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewQalqonStatusAndStates() {
    FaceGuardTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
        ) {
            QalqonStatusBadge(label = "On", tone = QalqonStatusTone.ACTIVE)
            QalqonStatusBadge(label = "Off", tone = QalqonStatusTone.INACTIVE)
            QalqonStatusBanner(text = "Notifications are off", severity = QalqonAlertSeverity.WARNING)
            QalqonAlertRow(text = "A child still needs face setup", severity = QalqonAlertSeverity.INFO, onClick = {})
            QalqonSectionHeader(title = "Section header", supportingText = "Optional supporting text")
            QalqonEmptyState(title = "Nothing here yet", description = "Add an item to get started", actionLabel = "Add")
            QalqonLoadingState()
            QalqonErrorState(title = "Something went wrong", message = "Try again", retryLabel = "Reload", onRetry = {})
        }
    }
}

@Preview(name = "Child + settings rows — light", showBackground = true)
@Preview(name = "Child + settings rows — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewQalqonChildAndRows() {
    FaceGuardTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
        ) {
            QalqonChildCard(
                name = "Alisher",
                initial = "A",
                protectionConfigured = true,
                faceEnrolled = true,
                faceStatus = "Face enrolled",
                screenTime = "Today: 1h 24m",
                onClick = {},
            )
            QalqonSettingRow(
                title = "Protection",
                description = "Turn protection on or off",
                onClick = {},
            )
            QalqonSettingSwitchRow(
                title = "Notifications",
                checked = true,
                onCheckedChange = {},
                description = "Alert me about requests",
            )
        }
    }
}
