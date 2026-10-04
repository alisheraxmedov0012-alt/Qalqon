package uz.faceguard.app.feature.subscription

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementState

/**
 * Pure mapping from billing domain to localized UI strings. No Android, so every
 * lifecycle state and error is unit-testable on the JVM, and no screen has to know the
 * enum-to-string mapping.
 */
object SubscriptionPresentation {

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
