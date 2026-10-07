package uz.faceguard.app.feature.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import uz.faceguard.app.R
import uz.faceguard.app.core.legal.LegalLinks
import uz.faceguard.app.core.legal.openLegalLink
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.qalqon.QalqonSectionCard
import uz.faceguard.app.core.ui.qalqon.QalqonSettingRow

/**
 * Release Block 2: the in-app legal and support area.
 *
 * Presentation only. It renders the app's public legal resources (Privacy Policy, Terms of
 * Service, external account deletion) and the support channels, all sourced from the single
 * [LegalLinks] object so no screen hardcodes a URL. When a resource is not hosted yet the
 * row says so plainly rather than opening an invented link.
 *
 * The rows are embedded in the existing Settings categories (Privacy, Support) rather than
 * introducing a new navigation architecture.
 */

/** Privacy Policy, Terms of Service and the external account-deletion resource. */
@Composable
fun LegalDocumentsSection(
    modifier: Modifier = Modifier.fillMaxWidth(),
    showDeleteAccount: Boolean = true,
) {
    val context = LocalContext.current
    QalqonSectionCard(
        title = stringResource(R.string.legal_section_title),
        subtitle = stringResource(R.string.legal_section_subtitle),
        modifier = modifier,
    ) {
        QalqonSettingRow(
            title = stringResource(R.string.legal_privacy_policy),
            description = when (LegalLinks.isConfigured(LegalLinks.PRIVACY_POLICY_URL)) {
                true -> null
                false -> stringResource(R.string.legal_link_unavailable)
            },
            onClick = { context.openLegalLink(LegalLinks.PRIVACY_POLICY_URL) },
        )
        QalqonSettingRow(
            title = stringResource(R.string.legal_terms_of_service),
            description = when (LegalLinks.isConfigured(LegalLinks.TERMS_OF_SERVICE_URL)) {
                true -> null
                false -> stringResource(R.string.legal_link_unavailable)
            },
            onClick = { context.openLegalLink(LegalLinks.TERMS_OF_SERVICE_URL) },
        )
        if (showDeleteAccount) {
            QalqonSettingRow(
                title = stringResource(R.string.legal_delete_account),
                description = when (LegalLinks.isConfigured(LegalLinks.DELETE_ACCOUNT_URL)) {
                    true -> null
                    false -> stringResource(R.string.legal_link_unavailable)
                },
                onClick = { context.openLegalLink(LegalLinks.DELETE_ACCOUNT_URL) },
            )
            Column(modifier = Modifier.padding(top = QalqonDimens.spacing.xs)) {
                // Account deletion and subscription cancellation are separate actions.
                Text(
                    text = stringResource(R.string.legal_delete_account_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Support channels: the hosted support page and the support mailbox. */
@Composable
fun SupportContactSection(modifier: Modifier = Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    QalqonSectionCard(
        title = stringResource(R.string.legal_support_title),
        modifier = modifier,
    ) {
        QalqonSettingRow(
            title = stringResource(R.string.legal_support_page),
            description = when (LegalLinks.isConfigured(LegalLinks.SUPPORT_URL)) {
                true -> null
                false -> stringResource(R.string.legal_link_unavailable)
            },
            onClick = { context.openLegalLink(LegalLinks.SUPPORT_URL) },
        )
        QalqonSettingRow(
            title = stringResource(R.string.legal_contact_support),
            description = LegalLinks.SUPPORT_EMAIL
                ?: stringResource(R.string.legal_contact_support_unavailable),
            onClick = { context.openLegalLink(LegalLinks.supportEmailUri()) },
        )
    }
}
