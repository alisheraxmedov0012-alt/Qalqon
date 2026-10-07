package uz.faceguard.app.feature.subscription

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.faceguard.app.R
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * The subscription screen. It shows the Play-provided price (never hardcoded), the
 * 3-day free trial, when the first paid charge happens and that it auto-renews, and it
 * gives a clear path to manage/cancel and to restore a purchase — the disclosures
 * Google Play requires, on the screen itself (not hidden behind another tap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    onStartTrial: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel(),
) {
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val product by viewModel.product.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val restoredMessage = stringResource(R.string.subscription_restored)
    LaunchedEffect(ui.restored) {
        if (ui.restored) {
            Toast.makeText(context, restoredMessage, Toast.LENGTH_LONG).show()
            viewModel.consumeRestored()
        }
    }
    val errorMessage = ui.error?.let { stringResource(SubscriptionPresentation.errorLabelRes(it)) }
    LaunchedEffect(ui.error) {
        if (errorMessage != null) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subscription_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val state = entitlement?.state ?: EntitlementState.NONE

            // Current status.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.subscription_plan_name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(SubscriptionPresentation.statusLabelRes(state)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // Trial + pricing disclosure (Google Play policy), all dynamic from Play.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.subscription_trial_badge), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = product?.formattedPrice?.let {
                            stringResource(R.string.subscription_price_after_trial, it)
                        } ?: stringResource(R.string.subscription_price_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(stringResource(R.string.subscription_billing_frequency), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.subscription_auto_renew_notice), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.subscription_cancel_notice), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.subscription_billing_google_play), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Premium benefits.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.subscription_benefits_title), style = MaterialTheme.typography.titleMedium)
                    listOf(
                        R.string.subscription_benefit_multi_child,
                        R.string.subscription_benefit_schedules,
                        R.string.subscription_benefit_screen_time,
                        R.string.subscription_benefit_eye_safety,
                        R.string.subscription_benefit_requests,
                        R.string.subscription_benefit_activity,
                    ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.subscription_free_note), style = MaterialTheme.typography.bodySmall)
                }
            }

            val isEntitled = state == EntitlementState.TRIAL ||
                state == EntitlementState.ACTIVE ||
                state == EntitlementState.CANCELED_ACTIVE ||
                state == EntitlementState.GRACE_PERIOD

            if (isEntitled) {
                Button(
                    onClick = { openPlaySubscriptions(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.subscription_manage))
                }
            } else {
                // Block 1: the offer never launches billing directly. It opens the single
                // Important Information / Consent screen, which starts the purchase only
                // after affirmative consent and with a real price on screen.
                Button(
                    onClick = onStartTrial,
                    enabled = !ui.loading && SubscriptionConsent.hasDisclosablePrice(product),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.subscription_start_trial))
                }
                Text(
                    stringResource(R.string.subscription_important_info_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.restore() },
                    enabled = !ui.loading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.subscription_restore))
                }
                OutlinedButton(
                    onClick = { viewModel.refresh() },
                    enabled = !ui.loading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.subscription_refresh))
                }
            }

            if (ui.loading) {
                Text(stringResource(R.string.subscription_loading), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.subscription_offline_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Opens Google Play's subscription management page, the easy-to-use online cancellation
 * path required by Play policy. A failure to open is surfaced, never silent.
 */
private fun openPlaySubscriptions(context: Context) {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=${ProductCatalog.PRODUCT_ID}&package=${context.packageName}",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.subscription_manage_open_failed, Toast.LENGTH_LONG).show()
    }
}
