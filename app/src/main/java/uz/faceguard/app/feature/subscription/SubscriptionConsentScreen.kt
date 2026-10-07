package uz.faceguard.app.feature.subscription

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader

/**
 * Release Block 1: the single Important Information / Consent screen between the Premium
 * Offer and Google Play Billing.
 *
 * ```
 * Premium Offer → Important Information / Consent → Google Play Billing
 * ```
 *
 * The screen states the trial, the dynamic localized price, monthly billing and automatic
 * renewal; the trial → paid conversion; how to cancel; the Premium benefits; the honest
 * technical limitations; and the Privacy Policy / Terms links. Billing can only start
 * after the affirmative consent checkbox is ticked and a real price is on screen
 * ([SubscriptionConsent.canStartPurchase]) — never before, and never from a generic
 * "Continue".
 *
 * The AccessibilityService consent stays in its own disclosure flow (the protection
 * screen); it is deliberately **not** part of this subscription consent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionConsentScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel(),
) {
    val product by viewModel.product.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var consentGiven by rememberSaveable { mutableStateOf(false) }

    val canStart = SubscriptionConsent.canStartPurchase(consentGiven, product)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subscription_consent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.subscription_consent_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.subscription_consent_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Subscription terms: trial, dynamic localized price, monthly billing, renewal.
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                QalqonSectionHeader(title = stringResource(R.string.subscription_consent_section_subscription))
                Text(
                    text = stringResource(R.string.subscription_trial_badge),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = product?.formattedPrice?.let {
                        stringResource(R.string.subscription_price_after_trial, it)
                    } ?: stringResource(R.string.subscription_price_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.subscription_billing_frequency),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.subscription_auto_renew_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.subscription_billing_google_play),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Trial → paid conversion.
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                QalqonSectionHeader(title = stringResource(R.string.subscription_consent_section_trial))
                Text(
                    text = stringResource(R.string.subscription_consent_trial_conversion),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Cancellation (Google Play) — never conflated with uninstall or account deletion.
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                QalqonSectionHeader(title = stringResource(R.string.subscription_consent_section_cancel))
                Text(
                    text = stringResource(R.string.subscription_consent_cancel_how),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Premium benefits — only features QALQON actually ships.
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                QalqonSectionHeader(title = stringResource(R.string.subscription_benefits_title))
                listOf(
                    R.string.subscription_benefit_multi_child,
                    R.string.subscription_benefit_schedules,
                    R.string.subscription_benefit_screen_time,
                    R.string.subscription_benefit_eye_safety,
                    R.string.subscription_benefit_requests,
                    R.string.subscription_benefit_activity,
                ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                Text(
                    text = stringResource(R.string.subscription_free_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Short, honest technical limitations.
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                QalqonSectionHeader(title = stringResource(R.string.subscription_consent_section_limits))
                listOf(
                    R.string.subscription_limit_accessibility,
                    R.string.subscription_limit_overlay,
                    R.string.subscription_limit_system,
                    R.string.subscription_limit_recognition,
                ).forEach {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Legal: Privacy Policy + Terms. URLs are supplied by Block 2; when a URL is not
            // configured an honest "not available yet" state is shown instead of a fake link.
            QalqonCard(modifier = Modifier.fillMaxWidth()) {
                QalqonSectionHeader(title = stringResource(R.string.subscription_consent_section_legal))
                TextButton(
                    onClick = { openOrReport(context, LegalLinks.PRIVACY_POLICY_URL) },
                ) {
                    Text(stringResource(R.string.subscription_consent_privacy_policy))
                }
                TextButton(
                    onClick = { openOrReport(context, LegalLinks.TERMS_OF_SERVICE_URL) },
                ) {
                    Text(stringResource(R.string.subscription_consent_terms_of_service))
                }
                Text(
                    text = stringResource(R.string.subscription_consent_google_play),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Affirmative subscription consent — separate from the AccessibilityService consent.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = consentGiven,
                        role = Role.Checkbox,
                        onValueChange = { consentGiven = it },
                    )
                    .padding(vertical = QalqonDimens.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = consentGiven, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.subscription_consent_agree),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = QalqonDimens.spacing.sm),
                )
            }

            ui.error?.let { error ->
                Text(
                    text = stringResource(SubscriptionPresentation.errorLabelRes(error)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    // Billing only starts when the gate passes: consent given AND a price shown.
                    if (SubscriptionConsent.canStartPurchase(consentGiven, product)) {
                        (context as? Activity)?.let { viewModel.subscribe(it) }
                    }
                },
                enabled = canStart && !ui.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.subscription_start_trial))
            }

            // The price and billing frequency sit with the CTA, so the terms are visible
            // exactly where the user commits.
            Text(
                text = product?.formattedPrice?.let {
                    stringResource(R.string.subscription_price_after_trial, it)
                } ?: stringResource(R.string.subscription_price_unavailable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.subscription_billing_frequency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Opens a legal document, or reports honestly that it is not published yet. No fake URL is
 * ever opened; an unhandled link falls back to the same message instead of crashing.
 */
private fun openOrReport(context: Context, url: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, R.string.subscription_consent_legal_unavailable, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.subscription_consent_legal_unavailable, Toast.LENGTH_LONG).show()
    }
}
