package uz.faceguard.app.core.billing

import uz.faceguard.app.domain.billing.BillingError

/**
 * Central mapping from a Google Play billing response code to the domain [BillingError].
 *
 * The integer values mirror `com.android.billingclient.api.BillingClient.BillingResponseCode`
 * (a stable, documented contract). Keeping the mapping here — pure and Android-free —
 * lets every error path be unit-tested on the JVM and guarantees the UI never sees a
 * raw Play code or debug message.
 */
object BillingErrorMapper {

    // Mirrors BillingClient.BillingResponseCode.
    const val OK = 0
    const val USER_CANCELED = 1
    const val SERVICE_UNAVAILABLE = 2
    const val BILLING_UNAVAILABLE = 3
    const val ITEM_UNAVAILABLE = 4
    const val DEVELOPER_ERROR = 5
    const val ERROR = 6
    const val ITEM_ALREADY_OWNED = 7
    const val ITEM_NOT_OWNED = 8
    const val NETWORK_ERROR = 12
    const val SERVICE_DISCONNECTED = -1
    const val FEATURE_NOT_SUPPORTED = -2
    const val SERVICE_TIMEOUT = -3

    fun fromResponseCode(code: Int): BillingError = when (code) {
        OK -> BillingError.UNEXPECTED // callers only map non-OK codes; OK must not reach here
        USER_CANCELED -> BillingError.USER_CANCELED
        SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED, SERVICE_TIMEOUT, FEATURE_NOT_SUPPORTED,
        BILLING_UNAVAILABLE,
        -> BillingError.BILLING_UNAVAILABLE

        NETWORK_ERROR -> BillingError.NETWORK_ERROR
        ITEM_UNAVAILABLE -> BillingError.ITEM_UNAVAILABLE
        ITEM_ALREADY_OWNED -> BillingError.ITEM_ALREADY_OWNED
        else -> BillingError.UNEXPECTED
    }

    /** True when [code] means "Play answered and we should trust an empty result". */
    fun isAuthoritative(code: Int): Boolean = code == OK
}
