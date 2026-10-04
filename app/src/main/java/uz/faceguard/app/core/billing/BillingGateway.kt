package uz.faceguard.app.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.domain.billing.BillingError

/** Google Play purchase state, mirrored without leaking the Play SDK type. */
enum class BillingPurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** A purchasable offer as returned by Google Play (dynamic price, never hardcoded). */
data class BillingProductDetails(
    val productId: String,
    val basePlanId: String?,
    val offerId: String?,
    val offerToken: String?,
    val formattedPrice: String?,
    val priceCurrencyCode: String?,
    val billingPeriod: String?,
    val isFreeTrial: Boolean,
    val freeTrialPeriod: String?,
)

/**
 * A Google Play purchase, reduced to what the app needs.
 *
 * The modern Billing Library (8/9) no longer exposes per-purchase offer details, so
 * base plan / offer / trial are not available from a [BillingPurchase]; trial status is
 * derived from the purchase time against the catalog trial duration (documented
 * heuristic). [purchaseToken] is a secret used only to acknowledge and re-query; it is
 * never logged, never persisted and never placed in UI state.
 */
data class BillingPurchase(
    val productId: String,
    val purchaseToken: String,
    val state: BillingPurchaseState,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    val purchaseTimeMillis: Long,
)

/** Result of a product-details query. */
data class BillingProductQuery(
    val products: List<BillingProductDetails>,
    val error: BillingError? = null,
)

/** Result of an active-subscription query. */
data class BillingPurchaseQuery(
    val purchases: List<BillingPurchase>,
    val error: BillingError? = null,
    /** True when Play answered; false when the service/connection was unavailable. */
    val available: Boolean = true,
)

/**
 * The seam between QALQON's entitlement logic and Google Play Billing.
 *
 * Nothing outside `data`/`core` depends on this interface's Android nature; the
 * entitlement repository and its tests use it, and a fake implementation drives the
 * deterministic lifecycle tests without Google Play.
 */
interface BillingGateway {

    /** Purchase updates pushed by Play (the `PurchasesUpdatedListener`). */
    val purchaseUpdates: Flow<List<BillingPurchase>>

    /** Connects (or returns true if already connected). False when unreachable. */
    suspend fun connect(): Boolean

    /** Dynamic product/offer details for [productId], including the trial offer. */
    suspend fun queryProduct(productId: String): BillingProductQuery

    /** Currently active subscriptions for this Play account. */
    suspend fun queryActiveSubscriptions(): BillingPurchaseQuery

    /** Launches the Play purchase sheet for [details]. Null on success, else the error. */
    suspend fun launchPurchase(activity: Activity, details: BillingProductDetails): BillingError?

    /** Acknowledges a purchase. Null on success, else the error. */
    suspend fun acknowledge(purchaseToken: String): BillingError?

    /** Releases the connection. */
    fun endConnection()
}
