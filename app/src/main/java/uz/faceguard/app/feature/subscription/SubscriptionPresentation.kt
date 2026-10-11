package uz.faceguard.app.feature.subscription

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.core.billing.BillingProductDetails
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementState

/**
 * Pure mapping from billing domain to localized UI strings. No Android, so every
 * lifecycle state and error is unit-testable on the JVM, and no screen has to know the
 * enum-to-string mapping.
 */
object SubscriptionPresentation {

    /**
     * Release Block 4 (trial truthfulness): whether the free-trial wording may be shown.
     *
     * The trial is a Google Play **offer**, so the app must never promise one it has not
     * seen. [BillingProductDetails.isFreeTrial] is derived from the *preferred offer's*
     * first pricing phase (`GooglePlayBillingGateway.toDto`), so the trial badge/CTA are
     * shown only when Play actually returned an offer with a free first phase. A null
     * (not-yet-loaded / unconfigured) product discloses nothing about a trial.
     */
    fun showsFreeTrial(product: BillingProductDetails?): Boolean = product?.isFreeTrial == true

    /**
     * The primary call-to-action label: the trial wording only when a free trial was
     * actually offered by Play, otherwise a neutral "subscribe" label. This keeps the CTA
     * from promising a trial the user may be ineligible for (Play enforces eligibility
     * server-side; the app cannot know it).
     */
    @StringRes
    fun startCtaLabelRes(product: BillingProductDetails?): Int =
        if (showsFreeTrial(product)) R.string.subscription_start_trial else R.string.subscription_start_subscribe

    /** The localized label for the current entitlement state. */
    @StringRes
    fun statusLabelRes(state: EntitlementState): Int = when (state) {
        EntitlementState.NONE -> R.string.subscription_status_none
        EntitlementState.TRIAL -> R.string.subscription_status_trial
        EntitlementState.ACTIVE -> R.string.subscription_status_active
        EntitlementState.CANCELED_ACTIVE -> R.string.subscription_status_canceled
        EntitlementState.GRACE_PERIOD -> R.string.subscription_status_grace
        EntitlementState.ACCOUNT_HOLD -> R.string.subscription_status_hold
        EntitlementState.EXPIRED -> R.string.subscription_status_expired
        EntitlementState.REVOKED -> R.string.subscription_status_revoked
        EntitlementState.PENDING -> R.string.subscription_status_pending
        EntitlementState.BILLING_UNAVAILABLE -> R.string.subscription_status_unavailable
        EntitlementState.UNKNOWN -> R.string.subscription_status_unknown
    }

    /** The localized, actionable message for a billing failure. */
    @StringRes
    fun errorLabelRes(error: BillingError): Int = when (error) {
        BillingError.USER_CANCELED -> R.string.subscription_error_user_canceled
        BillingError.BILLING_UNAVAILABLE -> R.string.subscription_error_billing_unavailable
        BillingError.ITEM_UNAVAILABLE -> R.string.subscription_error_item_unavailable
        BillingError.ITEM_ALREADY_OWNED -> R.string.subscription_error_already_owned
        BillingError.NETWORK_ERROR -> R.string.subscription_error_network
        BillingError.PENDING -> R.string.subscription_error_pending
        BillingError.UNEXPECTED -> R.string.subscription_error_unexpected
    }
}
